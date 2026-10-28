import { jwtExpiryMs } from './jwt.js'
import {
  PushClientError,
  type ClientFrame,
  type PublishOptions,
  type PublishResult,
  type PushClient,
  type PushClientOptions,
  type PushEvent,
  type PushState,
  type ServerFrame,
  type SubscribeOptions,
  type Subscription,
  type WebSocketConstructor,
  type WebSocketLike,
} from './types.js'

const SUBPROTOCOL = 'push.v1.json'
const CLOSE_REBALANCE = 1012
const CLOSE_POLICY = 1008
const CLOSE_TOKEN_EXPIRED = 4401
/** The BFF session behind a cookie connection ended: terminal, no reconnect, no token function. */
const CLOSE_SESSION_ENDED = 4440
const DEFAULT_PING_MS = 25_000

type Timer = ReturnType<typeof setTimeout>

interface SubState {
  topics: string[]
  /** The last id delivered per topic: the resume point after a reconnect. Signal frames never set it. */
  last: Map<string, string>
  options: SubscribeOptions
  closed: boolean
}

interface Pending {
  topic: string
  id?: string
  resolve: (value: never) => void
  reject: (error: PushClientError) => void
  timer: Timer
}

/**
 * A push client: one WebSocket at a time, reopened with capped exponential backoff, every live
 * subscription resent with `after` set to the last id it delivered.
 */
export function createPushClient(options: PushClientOptions): PushClient {
  const WS: WebSocketConstructor | undefined =
    options.webSocket ?? (globalThis as { WebSocket?: WebSocketConstructor }).WebSocket
  if (!WS) {
    throw new Error('No WebSocket implementation: pass one as the `webSocket` option (for example the `ws` package).')
  }
  const auth = options.auth
  const backoff = {
    min: options.backoff?.min ?? 500,
    max: options.backoff?.max ?? 30_000,
    factor: options.backoff?.factor ?? 2,
    jitter: options.backoff?.jitter ?? 1,
  }
  const requestTimeoutMs = options.requestTimeoutMs ?? 10_000
  const stableAfterMs = options.stableAfterMs ?? 10_000
  const refreshSkewMs = options.refreshSkewMs ?? 30_000

  let state: PushState = 'closed'
  let socket: WebSocketLike | null = null
  let closedByUser = false
  let attempt = 0
  let reconnectTimer: Timer | undefined
  let refreshTimer: Timer | undefined
  let pingTimer: Timer | undefined
  let stableTimer: Timer | undefined
  let pingTuned = false
  const subs = new Set<SubState>()
  const pendingPubs = new Map<string, Pending[]>()
  const pendingAcks: Pending[] = []
  const stateListeners = new Set<(state: PushState, info?: { reason?: string }) => void>()
  const errorListeners = new Set<(error: PushClientError) => void>()
  const frameListeners = new Set<(frame: ServerFrame) => void>()

  // ---- state and errors ----

  function setState(next: PushState, info?: { reason?: string }): void {
    if (state === next && !info) return
    state = next
    for (const l of Array.from(stateListeners)) l(next, info)
  }

  function emitError(error: PushClientError): void {
    for (const l of Array.from(errorListeners)) l(error)
  }

  function notifySub(sub: SubState, error: PushClientError): void {
    sub.options.onError?.(error)
  }

  // ---- sending ----

  function sendRaw(ws: WebSocketLike, frame: ClientFrame): void {
    ws.send(JSON.stringify(frame))
  }

  function subFrame(sub: SubState, topics: string[] = sub.topics): ClientFrame {
    const after: Record<string, string> = {}
    for (const t of topics) {
      const id = sub.last.get(t)
      if (id) after[t] = id
    }
    const frame: ClientFrame = { type: 'sub', topics: [...topics] }
    if (Object.keys(after).length > 0) frame.after = after
    if (options.origin) frame.origin = options.origin
    return frame
  }

  function sendSubs(ws: WebSocketLike): void {
    for (const sub of subs) {
      if (!sub.closed && sub.topics.length > 0) sendRaw(ws, subFrame(sub))
    }
  }

  // ---- connection ----

  function connect(): void {
    if (closedByUser) throw new Error('The push client is closed.')
    if (socket || reconnectTimer !== undefined) return
    open()
  }

  function open(): void {
    reconnectTimer = undefined
    setState('connecting')
    const ws = new WS!(options.url, SUBPROTOCOL)
    socket = ws
    pingTuned = false
    ws.onopen = () => {
      if (socket === ws) void onOpen(ws)
    }
    ws.onmessage = (ev: { data: unknown }) => {
      if (socket === ws) handleMessage(ev.data)
    }
    ws.onclose = (ev: { code: number; reason?: string }) => {
      if (socket === ws) onClose(ev.code, ev.reason)
    }
    ws.onerror = () => {
      // A close event follows; the reconnect logic lives there.
    }
  }

  async function onOpen(ws: WebSocketLike): Promise<void> {
    let token: string | undefined
    if (auth.mode === 'token') {
      try {
        token = await auth.token()
      } catch (e) {
        if (socket !== ws) return
        emitError(new PushClientError('token', `The token function failed: ${String(e)}`))
        ws.close(1000, 'token unavailable')
        return
      }
      if (socket !== ws) return
    }
    if (token !== undefined) {
      const connect: ClientFrame = { type: 'connect', token }
      if (options.origin) connect.origin = options.origin
      sendRaw(ws, connect)
      scheduleRefresh(ws, token)
    } else if (options.origin) {
      sendRaw(ws, { type: 'connect', origin: options.origin })
    }
    sendSubs(ws)
    setState('open')
    startPing(ws, options.pingIntervalMs ?? DEFAULT_PING_MS)
    stableTimer = setTimeout(() => {
      attempt = 0
    }, stableAfterMs)
  }

  function startPing(ws: WebSocketLike, intervalMs: number): void {
    clearTimeout(pingTimer)
    pingTimer = undefined
    if (intervalMs <= 0) return
    const tick = (): void => {
      if (socket !== ws) return
      sendRaw(ws, { type: 'ping' })
      pingTimer = setTimeout(tick, intervalMs)
    }
    pingTimer = setTimeout(tick, intervalMs)
  }

  function scheduleRefresh(ws: WebSocketLike, token: string): void {
    clearTimeout(refreshTimer)
    refreshTimer = undefined
    const exp = jwtExpiryMs(token)
    if (exp === undefined || auth.mode !== 'token') return
    const remaining = exp - Date.now()
    const skew = Math.min(refreshSkewMs, Math.max(remaining, 0) / 2)
    const delay = Math.max(1000, remaining - skew)
    refreshTimer = setTimeout(async () => {
      if (socket !== ws || auth.mode !== 'token') return
      try {
        const fresh = await auth.token()
        if (socket !== ws) return
        sendRaw(ws, { type: 'refresh', token: fresh })
        scheduleRefresh(ws, fresh)
      } catch (e) {
        // The gateway closes with 4401 when the old token expires; the reconnect asks for a fresh one.
        if (socket === ws) emitError(new PushClientError('token', `The token function failed: ${String(e)}`))
      }
    }, delay)
  }

  function clearConnectionTimers(): void {
    clearTimeout(refreshTimer)
    clearTimeout(pingTimer)
    clearTimeout(stableTimer)
    refreshTimer = pingTimer = stableTimer = undefined
  }

  function onClose(code: number, reason?: string): void {
    socket = null
    clearConnectionTimers()
    failPending(new PushClientError('closed', 'The connection closed before the gateway answered.'))
    if (closedByUser) return
    if (code === CLOSE_SESSION_ENDED) {
      stop('session-ended', reason || 'The session ended (4440). Sign in again.')
    } else if (code === CLOSE_POLICY) {
      stop('policy', reason || 'The gateway refused the connection (1008).')
    } else if (code === CLOSE_TOKEN_EXPIRED && auth.mode !== 'token') {
      stop('signed-out', reason || 'The session ended (4401).')
    } else if (code === CLOSE_TOKEN_EXPIRED || code === CLOSE_REBALANCE) {
      // Immediate, once: a second drop before the connection proved stable falls back to backoff.
      scheduleReconnect(attempt === 0)
    } else {
      scheduleReconnect(false)
    }
  }

  /** A close the client does not retry: every subscription hears why. */
  function stop(code: string, message: string): void {
    setState('closed', { reason: code })
    const error = new PushClientError(code, message)
    for (const sub of Array.from(subs)) notifySub(sub, error)
    emitError(error)
  }

  function scheduleReconnect(immediate: boolean): void {
    const n = attempt++
    if (immediate) {
      open()
      return
    }
    const base = Math.min(backoff.max, backoff.min * Math.pow(backoff.factor, n))
    const delay = base * (1 - backoff.jitter * Math.random())
    setState('waiting')
    reconnectTimer = setTimeout(open, delay)
  }

  // ---- inbound ----

  function handleMessage(raw: unknown): void {
    let frame: ServerFrame
    try {
      const text = typeof raw === 'string' ? raw : new TextDecoder().decode(raw as ArrayBuffer)
      frame = JSON.parse(text) as ServerFrame
    } catch {
      return
    }
    for (const l of Array.from(frameListeners)) {
      try {
        l(frame)
      } catch (e) {
        emitError(new PushClientError('handler', `A frame listener threw: ${String(e)}`))
      }
    }
    switch (frame.type) {
      case 'connected':
        // The gateway's own keepalive period is the default ping interval, once it has told us.
        if (!pingTuned && socket && options.pingIntervalMs === undefined && (frame.keepaliveMs ?? 0) > 0) {
          pingTuned = true
          startPing(socket, frame.keepaliveMs!)
        }
        break
      case 'event': {
        const { type: _type, ...event } = frame
        deliver(event)
        break
      }
      case 'reset':
      case 'overflow':
        gap(frame.topic, frame.reason, frame.type === 'overflow' ? frame.dropped : undefined)
        break
      case 'published': {
        const waiting = pendingPubs.get(frame.topic)?.shift()
        if (waiting) settle(waiting, { topic: frame.topic, id: frame.id } satisfies PublishResult)
        break
      }
      case 'acked': {
        const i = pendingAcks.findIndex((p) => p.topic === frame.topic && p.id === frame.cursor)
        if (i >= 0) settle(pendingAcks.splice(i, 1)[0], undefined)
        break
      }
      case 'error':
        handleError(frame.code, frame.message, frame.topic)
        break
      default:
        break
    }
  }

  function deliver(event: PushEvent): void {
    for (const sub of Array.from(subs)) {
      if (sub.closed || !sub.topics.includes(event.topic)) continue
      // A signal carries no id and is never a resume point.
      if (event.id) sub.last.set(event.topic, event.id)
      try {
        sub.options.onEvent(event)
      } catch (e) {
        notifySub(sub, new PushClientError('handler', `onEvent threw: ${String(e)}`, event.topic))
      }
    }
  }

  function gap(topic: string, reason: string, dropped?: number): void {
    const gapInfo = dropped === undefined ? { topic, reason } : { topic, reason, dropped }
    for (const sub of Array.from(subs)) {
      if (!sub.closed && sub.topics.includes(topic)) sub.options.onGap?.(gapInfo)
    }
    // queue_full: the gateway unsubscribed the topic, so ask for it again from the last id seen.
    if (reason === 'queue_full' && socket && state === 'open') {
      for (const sub of subs) {
        if (!sub.closed && sub.topics.includes(topic)) sendRaw(socket, subFrame(sub, [topic]))
      }
    }
  }

  function handleError(code: string, message: string, topic?: string): void {
    const error = new PushClientError(code, message, topic)
    if (topic === undefined) {
      for (const sub of Array.from(subs)) notifySub(sub, error)
      emitError(error)
      return
    }
    const waiting = pendingPubs.get(topic)?.shift()
    if (waiting) {
      clearTimeout(waiting.timer)
      waiting.reject(error)
      return
    }
    // A refused subscription is not retried: the topic leaves the subscription for good.
    let routed = false
    for (const sub of Array.from(subs)) {
      if (sub.closed || !sub.topics.includes(topic)) continue
      routed = true
      sub.topics = sub.topics.filter((t) => t !== topic)
      sub.last.delete(topic)
      notifySub(sub, error)
    }
    if (!routed) emitError(error)
  }

  // ---- requests ----

  function settle(p: Pending, value: unknown): void {
    clearTimeout(p.timer)
    p.resolve(value as never)
  }

  function request(list: Pending[], topic: string, id: string | undefined, send: () => void): Promise<never> {
    return new Promise<never>((resolve, reject) => {
      const p: Pending = {
        topic,
        id,
        resolve,
        reject,
        timer: setTimeout(() => {
          const i = list.indexOf(p)
          if (i >= 0) list.splice(i, 1)
          reject(new PushClientError('timeout', `No answer from the gateway within ${requestTimeoutMs} ms.`, topic))
        }, requestTimeoutMs),
      }
      list.push(p)
      try {
        send()
      } catch (e) {
        list.splice(list.indexOf(p), 1)
        clearTimeout(p.timer)
        reject(new PushClientError('not_open', String(e), topic))
      }
    })
  }

  function failPending(error: PushClientError): void {
    for (const list of [pendingAcks, ...pendingPubs.values()]) {
      for (const p of list.splice(0)) {
        clearTimeout(p.timer)
        p.reject(error)
      }
    }
    pendingPubs.clear()
  }

  function openSocket(): WebSocketLike | undefined {
    return state === 'open' && socket ? socket : undefined
  }

  // ---- public API ----

  function subscribe(topics: string | string[], subOptions: SubscribeOptions): Subscription {
    if (closedByUser) throw new Error('The push client is closed.')
    const list = [...new Set(typeof topics === 'string' ? [topics] : topics)]
    if (list.length === 0) throw new Error('subscribe needs at least one topic.')
    const last = new Map<string, string>()
    const after = subOptions.after
    if (typeof after === 'string') {
      for (const t of list) last.set(t, after)
    } else if (after) {
      for (const t of list) if (after[t]) last.set(t, after[t])
    }
    const sub: SubState = { topics: list, last, options: subOptions, closed: false }
    subs.add(sub)
    const ws = openSocket()
    if (ws) sendRaw(ws, subFrame(sub))
    else connect()

    return {
      get topics() {
        return [...sub.topics]
      },
      ack(id: string, topic?: string): Promise<void> {
        const target = topic ?? (sub.topics.length === 1 ? sub.topics[0] : undefined)
        if (target === undefined) {
          return Promise.reject(new PushClientError('bad_request', 'ack needs a topic when the subscription has several.'))
        }
        const ws = openSocket()
        if (!ws) return Promise.reject(new PushClientError('not_open', 'The connection is not open.', target))
        return request(pendingAcks, target, id, () => sendRaw(ws, { type: 'ack', topic: target, id }))
      },
      close(): void {
        if (sub.closed) return
        sub.closed = true
        subs.delete(sub)
        const ws = openSocket()
        if (!ws) return
        const stillUsed = new Set<string>()
        for (const other of subs) for (const t of other.topics) stillUsed.add(t)
        const gone = sub.topics.filter((t) => !stillUsed.has(t))
        if (gone.length > 0) sendRaw(ws, { type: 'unsub', topics: gone })
      },
    }
  }

  function publish(topic: string, data: unknown, publishOptions?: PublishOptions): Promise<PublishResult> {
    const ws = openSocket()
    if (!ws) return Promise.reject(new PushClientError('not_open', 'The connection is not open.', topic))
    const frame: ClientFrame = { type: 'pub', topic, data }
    if (publishOptions?.attributes) frame.attributes = publishOptions.attributes
    if (publishOptions?.key !== undefined) frame.key = publishOptions.key
    let list = pendingPubs.get(topic)
    if (!list) pendingPubs.set(topic, (list = []))
    return request(list, topic, undefined, () => sendRaw(ws, frame))
  }

  function close(): void {
    closedByUser = true
    clearTimeout(reconnectTimer)
    reconnectTimer = undefined
    clearConnectionTimers()
    failPending(new PushClientError('closed', 'The push client was closed.'))
    const ws = socket
    socket = null
    ws?.close(1000, 'client closed')
    setState('closed')
  }

  function on(event: 'state' | 'error' | 'frame', listener: never): () => void {
    if (event === 'state') {
      const l = listener as (state: PushState, info?: { reason?: string }) => void
      stateListeners.add(l)
      return () => void stateListeners.delete(l)
    }
    if (event === 'frame') {
      const l = listener as (frame: ServerFrame) => void
      frameListeners.add(l)
      return () => void frameListeners.delete(l)
    }
    const l = listener as (error: PushClientError) => void
    errorListeners.add(l)
    return () => void errorListeners.delete(l)
  }

  return {
    get state() {
      return state
    },
    connect,
    subscribe,
    publish,
    on: on as PushClient['on'],
    close,
  }
}
