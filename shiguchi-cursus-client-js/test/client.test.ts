import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createPushClient,
  type PushClientError,
  type PushClientOptions,
  type PushEvent,
  type PushGap,
  type WebSocketConstructor,
  type WebSocketLike,
} from '../src/index.js'

const fixture = JSON.parse(readFileSync(new URL('./fixtures/frames.json', import.meta.url), 'utf-8')) as {
  client: Record<string, Record<string, unknown>>
  server: Record<string, Record<string, unknown>>
}

/** An in-process WebSocket: the test plays the gateway by calling serverOpen / serverSend / serverClose. */
class MockWebSocket implements WebSocketLike {
  static instances: MockWebSocket[] = []
  static last(): MockWebSocket {
    return MockWebSocket.instances[MockWebSocket.instances.length - 1]
  }

  onopen: ((ev: never) => unknown) | null = null
  onmessage: ((ev: never) => unknown) | null = null
  onclose: ((ev: never) => unknown) | null = null
  onerror: ((ev: never) => unknown) | null = null
  readonly sent: string[] = []
  closeCalls: Array<{ code?: number; reason?: string }> = []
  private done = false

  constructor(
    readonly url: string,
    readonly protocols?: string | string[],
  ) {
    MockWebSocket.instances.push(this)
  }

  send(data: string): void {
    this.sent.push(data)
  }

  close(code?: number, reason?: string): void {
    this.closeCalls.push({ code, reason })
    this.serverClose(code ?? 1005)
  }

  frames(): Array<Record<string, unknown>> {
    return this.sent.map((s) => JSON.parse(s) as Record<string, unknown>)
  }

  types(): string[] {
    return this.frames().map((f) => f.type as string)
  }

  async serverOpen(): Promise<void> {
    ;(this.onopen as ((ev: unknown) => void) | null)?.({})
    await vi.advanceTimersByTimeAsync(0)
  }

  serverSend(frame: unknown): void {
    ;(this.onmessage as (ev: { data: unknown }) => void)({ data: JSON.stringify(frame) })
  }

  serverClose(code: number, reason = ''): void {
    if (this.done) return
    this.done = true
    ;(this.onclose as ((ev: { code: number; reason: string }) => void) | null)?.({ code, reason })
  }
}

function jwt(expSeconds: number): string {
  const enc = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${enc({ alg: 'none' })}.${enc({ exp: expSeconds })}.sig`
}

const NOW = Date.UTC(2026, 9, 17, 12, 0, 0)

function make(overrides: Partial<PushClientOptions> = {}) {
  return createPushClient({
    url: 'wss://gw.example/push/ws',
    auth: { mode: 'none' },
    webSocket: MockWebSocket as unknown as WebSocketConstructor,
    backoff: { min: 1000, max: 8000, factor: 2, jitter: 0 },
    pingIntervalMs: 0,
    ...overrides,
  })
}

function event(topic: string, id: string, data: unknown = {}): PushEvent & { type: 'event' } {
  return { type: 'event', topic, id, data, time: NOW }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  MockWebSocket.instances = []
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('connecting', () => {
  it('GIVEN token mode WHEN the socket opens SHOULD send connect with the token and origin, then sub', async () => {
    const client = make({ auth: { mode: 'token', token: async () => 'tok-1' }, origin: 'web-1' })
    client.subscribe(['orders:1', 'orders:2'], { onEvent: () => {} })

    expect(MockWebSocket.last().protocols).toBe('push.v1.json')
    await MockWebSocket.last().serverOpen()

    expect(MockWebSocket.last().frames()).toEqual([
      { type: 'connect', token: 'tok-1', origin: 'web-1' },
      { type: 'sub', topics: ['orders:1', 'orders:2'], origin: 'web-1' },
    ])
    expect(client.state).toBe('open')
    client.close()
  })

  it('GIVEN cookie mode WHEN the socket opens SHOULD send only sub and never call a token function', async () => {
    const client = make({ auth: { mode: 'cookie' } })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    expect(MockWebSocket.last().types()).toEqual(['sub'])
    client.close()
  })

  it('GIVEN cookie mode with an origin WHEN the socket opens SHOULD send connect with the origin only', async () => {
    const client = make({ auth: { mode: 'cookie' }, origin: 'web-1' })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    expect(MockWebSocket.last().frames()[0]).toEqual({ type: 'connect', origin: 'web-1' })
    client.close()
  })

  it('GIVEN an explicit after WHEN subscribing SHOULD send it as a per-topic map', async () => {
    const client = make()
    client.subscribe(['a:1', 'b:2'], { after: '77', onEvent: () => {} })
    client.subscribe('c:3', { after: { 'c:3': '88' }, onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    expect(MockWebSocket.last().frames()).toEqual([
      { type: 'sub', topics: ['a:1', 'b:2'], after: { 'a:1': '77', 'b:2': '77' } },
      { type: 'sub', topics: ['c:3'], after: { 'c:3': '88' } },
    ])
    client.close()
  })

  it('GIVEN no WebSocket implementation WHEN creating the client SHOULD throw a pointed error', () => {
    vi.stubGlobal('WebSocket', undefined)
    expect(() => createPushClient({ url: 'ws://x', auth: { mode: 'none' } })).toThrow(/webSocket/)
    vi.unstubAllGlobals()
  })

  it('GIVEN the state listener WHEN connecting and closing SHOULD report connecting, open and closed', async () => {
    const client = make()
    const states: string[] = []
    client.on('state', (s) => states.push(s))
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    client.close()

    expect(states).toEqual(['connecting', 'open', 'closed'])
  })
})

describe('reconnecting', () => {
  it('GIVEN a dropped socket WHEN it reconnects SHOULD wait with doubling backoff and resubscribe from the last ids', async () => {
    const client = make()
    const received: string[] = []
    client.subscribe(['orders:1', 'orders:2'], { onEvent: (e) => received.push(e.id) })
    const first = MockWebSocket.last()
    await first.serverOpen()
    first.serverSend(event('orders:1', '10'))
    first.serverSend(event('orders:1', '11'))
    first.serverClose(1006)

    expect(client.state).toBe('waiting')
    await vi.advanceTimersByTimeAsync(999)
    expect(MockWebSocket.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(MockWebSocket.instances).toHaveLength(2)

    const second = MockWebSocket.last()
    await second.serverOpen()
    expect(second.frames()).toEqual([{ type: 'sub', topics: ['orders:1', 'orders:2'], after: { 'orders:1': '11' } }])

    // A second drop before the connection proved stable waits twice as long.
    second.serverClose(1006)
    await vi.advanceTimersByTimeAsync(1999)
    expect(MockWebSocket.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(MockWebSocket.instances).toHaveLength(3)
    expect(received).toEqual(['10', '11'])
    client.close()
  })

  it('GIVEN a stable connection WHEN it drops SHOULD start the backoff over', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)
    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(2000)
    await MockWebSocket.last().serverOpen()
    await vi.advanceTimersByTimeAsync(10_000)

    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)

    expect(MockWebSocket.instances).toHaveLength(4)
    client.close()
  })

  it('GIVEN a long outage WHEN the backoff grows SHOULD cap at max', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    for (const wait of [1000, 2000, 4000, 8000, 8000]) {
      MockWebSocket.last().serverClose(1006)
      const before = MockWebSocket.instances.length
      await vi.advanceTimersByTimeAsync(wait - 1)
      expect(MockWebSocket.instances).toHaveLength(before)
      await vi.advanceTimersByTimeAsync(1)
      expect(MockWebSocket.instances).toHaveLength(before + 1)
    }
    client.close()
  })

  it('GIVEN jitter WHEN the socket drops SHOULD wait a random share of the delay', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const client = make({ backoff: { min: 1000, max: 8000, factor: 2, jitter: 1 } })
    client.subscribe('orders:1', { onEvent: () => {} })
    MockWebSocket.last().serverClose(1006)

    await vi.advanceTimersByTimeAsync(499)
    expect(MockWebSocket.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(MockWebSocket.instances).toHaveLength(2)
    client.close()
  })

  it('GIVEN close code 1012 WHEN the socket closes SHOULD reconnect at once without backoff', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'close', code: 1012, reason: 'rebalance' })
    MockWebSocket.last().serverClose(1012, 'rebalance')

    expect(MockWebSocket.instances).toHaveLength(2)
    expect(client.state).toBe('connecting')
    client.close()
  })

  it('GIVEN a rebalance right after a rebalance WHEN the connection never proved stable SHOULD back off the second time', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    MockWebSocket.last().serverClose(1012)
    expect(MockWebSocket.instances).toHaveLength(2)
    MockWebSocket.last().serverClose(1012)

    expect(MockWebSocket.instances).toHaveLength(2)
    expect(client.state).toBe('waiting')
    client.close()
  })

  it('GIVEN close code 1008 WHEN the socket closes SHOULD not reconnect and report policy through onError', async () => {
    const client = make()
    const errors: PushClientError[] = []
    client.subscribe('orders:1', { onEvent: () => {}, onError: (e) => errors.push(e) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(1008, 'origin not allowed')
    await vi.advanceTimersByTimeAsync(120_000)

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(client.state).toBe('closed')
    expect(errors.map((e) => e.code)).toEqual(['policy'])
    expect(errors[0].message).toBe('origin not allowed')
  })

  it('GIVEN a refused subscription WHEN the socket reconnects SHOULD report onError and not ask for that topic again', async () => {
    const client = make()
    const errors: PushClientError[] = []
    client.subscribe(['orders:1', 'secret:1'], { onEvent: () => {}, onError: (e) => errors.push(e) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'error', code: 'forbidden', message: 'no', topic: 'secret:1' })
    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)
    await MockWebSocket.last().serverOpen()

    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatchObject({ code: 'forbidden', topic: 'secret:1' })
    expect(MockWebSocket.last().frames()).toEqual([{ type: 'sub', topics: ['orders:1'] }])
    client.close()
  })
})

describe('tokens', () => {
  it('GIVEN close code 4401 in token mode WHEN the socket closes SHOULD reconnect at once with a fresh token', async () => {
    const token = vi.fn().mockResolvedValueOnce('old').mockResolvedValueOnce('fresh')
    const client = make({ auth: { mode: 'token', token } })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(4401, 'token expired')

    expect(MockWebSocket.instances).toHaveLength(2)
    await MockWebSocket.last().serverOpen()
    expect(token).toHaveBeenCalledTimes(2)
    expect(MockWebSocket.last().frames()[0]).toEqual({ type: 'connect', token: 'fresh' })
    client.close()
  })

  it('GIVEN a JWT with exp WHEN the expiry nears SHOULD send refresh with a new token before it', async () => {
    const first = jwt(NOW / 1000 + 120)
    const second = jwt(NOW / 1000 + 240)
    const token = vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second)
    const client = make({ auth: { mode: 'token', token }, refreshSkewMs: 30_000 })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    await vi.advanceTimersByTimeAsync(89_999)
    expect(MockWebSocket.last().types()).not.toContain('refresh')
    await vi.advanceTimersByTimeAsync(1)
    expect(MockWebSocket.last().frames().at(-1)).toEqual({ type: 'refresh', token: second })

    // And the next refresh is scheduled from the new token's exp.
    await vi.advanceTimersByTimeAsync(120_000)
    expect(MockWebSocket.last().types().filter((t) => t === 'refresh')).toHaveLength(2)
    client.close()
  })

  it('GIVEN an opaque token WHEN connected SHOULD not schedule any refresh', async () => {
    const client = make({ auth: { mode: 'token', token: () => 'opaque' } })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    await vi.advanceTimersByTimeAsync(3_600_000)

    expect(MockWebSocket.last().types()).toEqual(['connect', 'sub'])
    client.close()
  })

  it('GIVEN cookie mode WHEN the socket closes with 4401 SHOULD stop and report signed-out', async () => {
    const client = make({ auth: { mode: 'cookie' } })
    const errors: PushClientError[] = []
    const states: Array<[string, string | undefined]> = []
    client.on('state', (s, info) => states.push([s, info?.reason]))
    client.subscribe('orders:1', { onEvent: () => {}, onError: (e) => errors.push(e) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(4401)
    await vi.advanceTimersByTimeAsync(120_000)

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(errors.map((e) => e.code)).toEqual(['signed-out'])
    expect(states.at(-1)).toEqual(['closed', 'signed-out'])
  })

  it('GIVEN cookie mode WHEN the socket closes with 4440 SHOULD stop, report session-ended and never reconnect', async () => {
    const client = make({ auth: { mode: 'cookie' } })
    const errors: PushClientError[] = []
    const states: Array<[string, string | undefined]> = []
    client.on('state', (s, info) => states.push([s, info?.reason]))
    client.on('error', (e) => errors.push(e))
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(4440, 'session ended')
    await vi.advanceTimersByTimeAsync(120_000)

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(errors.map((e) => e.code)).toEqual(['session-ended'])
    expect(states.at(-1)).toEqual(['closed', 'session-ended'])
  })

  it('GIVEN token mode WHEN the socket closes with 4440 SHOULD stop without asking for another token', async () => {
    const token = vi.fn().mockResolvedValue('t1')
    const client = make({ auth: { mode: 'token', token } })
    const errors: PushClientError[] = []
    client.on('error', (e) => errors.push(e))
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    const calls = token.mock.calls.length
    MockWebSocket.last().serverClose(4440, 'session ended')
    await vi.advanceTimersByTimeAsync(120_000)

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(token.mock.calls.length).toBe(calls)
    expect(errors.map((e) => e.code)).toEqual(['session-ended'])
  })

  it('GIVEN a token function that rejects WHEN the socket opens SHOULD report token, close and retry with backoff', async () => {
    const token = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce('ok')
    const client = make({ auth: { mode: 'token', token } })
    const errors: PushClientError[] = []
    client.on('error', (e) => errors.push(e))
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    expect(errors.map((e) => e.code)).toEqual(['token'])
    expect(MockWebSocket.last().sent).toEqual([])
    await vi.advanceTimersByTimeAsync(1000)
    await MockWebSocket.last().serverOpen()
    expect(MockWebSocket.last().frames()[0]).toEqual({ type: 'connect', token: 'ok' })
    client.close()
  })
})

describe('events and gaps', () => {
  it('GIVEN overflow and reset frames WHEN they arrive SHOULD call onGap with topic and reason', async () => {
    const client = make()
    const gaps: PushGap[] = []
    client.subscribe(['orders:1', 'orders:2'], { onEvent: () => {}, onGap: (g) => gaps.push(g) })
    await MockWebSocket.last().serverOpen()
    const ws = MockWebSocket.last()
    ws.serverSend({ type: 'reset', topic: 'orders:1', reason: 'history_gap' })
    ws.serverSend({ type: 'reset', topic: 'orders:2', reason: 'no_history' })
    ws.serverSend({ type: 'overflow', topic: 'orders:2', reason: 'session_overflow', dropped: 4 })

    expect(gaps).toEqual([
      { topic: 'orders:1', reason: 'history_gap' },
      { topic: 'orders:2', reason: 'no_history' },
      { topic: 'orders:2', reason: 'session_overflow', dropped: 4 },
    ])
    expect(client.state).toBe('open')
    client.close()
  })

  it('GIVEN a reset followed by its replay WHEN the events arrive SHOULD keep delivering and resume after the last real id', async () => {
    const client = make()
    const received: string[] = []
    client.subscribe('orders:1', { after: '5', onEvent: (e) => received.push(e.id) })
    await MockWebSocket.last().serverOpen()
    const ws = MockWebSocket.last()
    ws.serverSend({ type: 'reset', topic: 'orders:1', reason: 'history_gap' })
    ws.serverSend(event('orders:1', '9'))
    ws.serverSend(event('orders:1', '10'))
    ws.serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)
    await MockWebSocket.last().serverOpen()

    expect(received).toEqual(['9', '10'])
    expect(MockWebSocket.last().frames()).toEqual([{ type: 'sub', topics: ['orders:1'], after: { 'orders:1': '10' } }])
    client.close()
  })

  it('GIVEN a signal frame with an empty id WHEN it arrives SHOULD not replace the resume point', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend(event('orders:1', '10'))
    MockWebSocket.last().serverSend(event('orders:1', ''))
    MockWebSocket.last().serverSend({ type: 'reset', topic: 'orders:1', reason: 'history_gap' })
    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)
    await MockWebSocket.last().serverOpen()

    expect(MockWebSocket.last().frames()[0]).toMatchObject({ after: { 'orders:1': '10' } })
    client.close()
  })

  it('GIVEN a queue_full overflow WHEN it arrives SHOULD report the gap and resubscribe the topic from the last id', async () => {
    const client = make()
    const gaps: PushGap[] = []
    client.subscribe(['orders:1', 'orders:2'], { onEvent: () => {}, onGap: (g) => gaps.push(g) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend(event('orders:1', '3'))
    MockWebSocket.last().serverSend({ type: 'overflow', topic: 'orders:1', reason: 'queue_full' })

    expect(gaps).toEqual([{ topic: 'orders:1', reason: 'queue_full' }])
    expect(MockWebSocket.last().frames().at(-1)).toEqual({ type: 'sub', topics: ['orders:1'], after: { 'orders:1': '3' } })
    client.close()
  })

  it('GIVEN a reconnect with nothing missed WHEN it resubscribes SHOULD not report a gap', async () => {
    const client = make()
    const gaps: PushGap[] = []
    client.subscribe('orders:1', { onEvent: () => {}, onGap: (g) => gaps.push(g) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(1006)
    await vi.advanceTimersByTimeAsync(1000)
    await MockWebSocket.last().serverOpen()

    expect(gaps).toEqual([])
    client.close()
  })

  it('GIVEN an onEvent that throws WHEN an event arrives SHOULD report it through onError and keep the connection', async () => {
    const client = make()
    const errors: PushClientError[] = []
    client.subscribe('orders:1', {
      onEvent: () => {
        throw new Error('render failed')
      },
      onError: (e) => errors.push(e),
    })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend(event('orders:1', '1'))

    expect(errors.map((e) => e.code)).toEqual(['handler'])
    expect(client.state).toBe('open')
    client.close()
  })
})

describe('frame listener', () => {
  it('GIVEN a frame listener WHEN the gateway sends frames SHOULD pass each one as parsed, before the event reaches onEvent', async () => {
    const client = make()
    const seen: string[] = []
    client.on('frame', (f) => seen.push(`frame:${f.type}`))
    client.subscribe('orders:1', { onEvent: (e) => seen.push(`event:${e.id}`) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'connected', connectionId: 'c1', authenticated: false })
    MockWebSocket.last().serverSend({ type: 'subscribed', topic: 'orders:1' })
    MockWebSocket.last().serverSend(event('orders:1', '7'))
    MockWebSocket.last().serverSend({ type: 'pong' })

    expect(seen).toEqual(['frame:connected', 'frame:subscribed', 'frame:event', 'event:7', 'frame:pong'])
    client.close()
  })

  it('GIVEN a frame that is not JSON WHEN it arrives SHOULD not reach the frame listener', async () => {
    const client = make()
    const seen: unknown[] = []
    client.on('frame', (f) => seen.push(f))
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    ;(MockWebSocket.last().onmessage as (ev: { data: unknown }) => void)({ data: 'not json' })

    expect(seen).toEqual([])
    client.close()
  })

  it('GIVEN a frame listener that throws WHEN a frame arrives SHOULD report handler through on(error) and still deliver the event', async () => {
    const client = make()
    const errors: PushClientError[] = []
    const events: string[] = []
    client.on('error', (e) => errors.push(e))
    client.on('frame', () => {
      throw new Error('log failed')
    })
    client.subscribe('orders:1', { onEvent: (e) => events.push(e.id) })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend(event('orders:1', '1'))

    expect(errors.map((e) => e.code)).toEqual(['handler'])
    expect(events).toEqual(['1'])
    expect(client.state).toBe('open')
    client.close()
  })

  it('GIVEN a removed frame listener WHEN frames arrive SHOULD not call it', async () => {
    const client = make()
    const seen: unknown[] = []
    const off = client.on('frame', (f) => seen.push(f))
    off()
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'pong' })

    expect(seen).toEqual([])
    client.close()
  })
})

describe('acknowledging, publishing, closing', () => {
  it('GIVEN an ack WHEN the acked frame arrives SHOULD resolve the promise', async () => {
    const client = make()
    const sub = client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    let resolved = false
    const p = sub.ack('42').then(() => (resolved = true))
    expect(MockWebSocket.last().frames().at(-1)).toEqual({ type: 'ack', topic: 'orders:1', id: '42' })
    await vi.advanceTimersByTimeAsync(0)
    expect(resolved).toBe(false)
    MockWebSocket.last().serverSend({ type: 'acked', topic: 'orders:1', cursor: '42' })
    await p

    expect(resolved).toBe(true)
    client.close()
  })

  it('GIVEN an ack nobody answers WHEN the request timeout passes SHOULD reject with timeout', async () => {
    const client = make({ requestTimeoutMs: 5000 })
    const sub = client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    const p = sub.ack('42')
    const assertion = expect(p).rejects.toMatchObject({ code: 'timeout' })
    await vi.advanceTimersByTimeAsync(5000)
    await assertion
    client.close()
  })

  it('GIVEN a multi-topic subscription WHEN ack has no topic SHOULD reject', async () => {
    const client = make()
    const sub = client.subscribe(['a:1', 'b:2'], { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    await expect(sub.ack('1')).rejects.toMatchObject({ code: 'bad_request' })
    client.close()
  })

  it('GIVEN a publish WHEN the published frame arrives SHOULD resolve with topic and id', async () => {
    const client = make()
    client.connect()
    await MockWebSocket.last().serverOpen()

    const p = client.publish('orders:1', { n: 1 }, { attributes: { tag: 't' }, key: 'k' })
    expect(MockWebSocket.last().frames().at(-1)).toEqual({
      type: 'pub',
      topic: 'orders:1',
      data: { n: 1 },
      attributes: { tag: 't' },
      key: 'k',
    })
    MockWebSocket.last().serverSend({ type: 'published', topic: 'orders:1', id: '99' })

    await expect(p).resolves.toEqual({ topic: 'orders:1', id: '99' })
    client.close()
  })

  it('GIVEN a publish the gateway refuses WHEN the error frame arrives SHOULD reject the publish and leave subscriptions alone', async () => {
    const client = make()
    const errors: PushClientError[] = []
    client.subscribe('orders:1', { onEvent: () => {}, onError: (e) => errors.push(e) })
    await MockWebSocket.last().serverOpen()
    const p = client.publish('orders:1', {})
    MockWebSocket.last().serverSend({ type: 'error', code: 'forbidden', message: 'client publishing is off', topic: 'orders:1' })

    await expect(p).rejects.toMatchObject({ code: 'forbidden' })
    expect(errors).toEqual([])
    client.close()
  })

  it('GIVEN no open connection WHEN publishing SHOULD reject instead of queueing', async () => {
    const client = make()
    client.connect()

    await expect(client.publish('orders:1', {})).rejects.toMatchObject({ code: 'not_open' })
    client.close()
  })

  it('GIVEN a pending publish WHEN the socket drops SHOULD reject it', async () => {
    const client = make()
    client.connect()
    await MockWebSocket.last().serverOpen()
    const p = client.publish('orders:1', {})
    const assertion = expect(p).rejects.toMatchObject({ code: 'closed' })
    MockWebSocket.last().serverClose(1006)

    await assertion
    client.close()
  })

  it('GIVEN two subscriptions on a topic WHEN one closes SHOULD unsubscribe only when the last one closes', async () => {
    const client = make()
    const a = client.subscribe(['orders:1', 'orders:2'], { onEvent: () => {} })
    const b = client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()

    a.close()
    expect(MockWebSocket.last().frames().at(-1)).toEqual({ type: 'unsub', topics: ['orders:2'] })
    b.close()
    expect(MockWebSocket.last().frames().at(-1)).toEqual({ type: 'unsub', topics: ['orders:1'] })
    client.close()
  })

  it('GIVEN a closed subscription WHEN events arrive SHOULD not deliver them', async () => {
    const client = make()
    const received: string[] = []
    const sub = client.subscribe('orders:1', { onEvent: (e) => received.push(e.id) })
    await MockWebSocket.last().serverOpen()
    sub.close()
    MockWebSocket.last().serverSend(event('orders:1', '1'))

    expect(received).toEqual([])
    client.close()
  })

  it('GIVEN a waiting client WHEN close() is called SHOULD never reconnect and leave no timers', async () => {
    const client = make()
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverClose(1006)
    expect(client.state).toBe('waiting')

    client.close()
    await vi.advanceTimersByTimeAsync(600_000)

    expect(MockWebSocket.instances).toHaveLength(1)
    expect(client.state).toBe('closed')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('GIVEN an open client with refresh, ping and stability timers WHEN close() is called SHOULD close the socket and clear every timer', async () => {
    const client = make({
      auth: { mode: 'token', token: () => jwt(NOW / 1000 + 600) },
      pingIntervalMs: 1000,
    })
    client.subscribe('orders:1', { onEvent: () => {} })
    const ws = MockWebSocket.last()
    await ws.serverOpen()
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    client.close()

    expect(ws.closeCalls).toEqual([{ code: 1000, reason: 'client closed' }])
    expect(vi.getTimerCount()).toBe(0)
    expect(() => client.subscribe('orders:1', { onEvent: () => {} })).toThrow(/closed/)
  })

  it('GIVEN a ping interval WHEN the connection stays open SHOULD send ping frames', async () => {
    const client = make({ pingIntervalMs: 20_000 })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    await vi.advanceTimersByTimeAsync(45_000)

    expect(MockWebSocket.last().types().filter((t) => t === 'ping')).toHaveLength(2)
    client.close()
  })

  it('GIVEN no ping option WHEN connected reports keepaliveMs SHOULD ping at that interval', async () => {
    const client = make({ pingIntervalMs: undefined })
    client.subscribe('orders:1', { onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'connected', connectionId: 'c', authenticated: false, keepaliveMs: 5000 })
    await vi.advanceTimersByTimeAsync(5000)

    expect(MockWebSocket.last().types()).toContain('ping')
    client.close()
  })
})

describe('frame fixture', () => {
  it('GIVEN the shared fixture WHEN the client sends frames SHOULD use only fields the fixture lists', async () => {
    const client = make({ auth: { mode: 'token', token: () => 'eyJ.e30.sig' }, origin: 'web-7f3a', pingIntervalMs: 1000 })
    const sub = client.subscribe('orders:42', { after: { 'orders:42': '1' }, onEvent: () => {} })
    await MockWebSocket.last().serverOpen()
    MockWebSocket.last().serverSend({ type: 'connected', connectionId: 'c', authenticated: true })
    void client.publish('orders:42', {}, { attributes: { a: 'b' }, key: 'k' }).catch(() => {})
    void sub.ack('1').catch(() => {})
    sub.close()
    await vi.advanceTimersByTimeAsync(1000)

    const seen = new Set<string>()
    for (const frame of MockWebSocket.last().frames()) {
      const type = frame.type as string
      seen.add(type)
      const allowed = Object.keys(fixture.client[type] ?? {})
      expect(allowed, `fixture has no client frame '${type}'`).not.toEqual([])
      for (const key of Object.keys(frame)) expect(allowed).toContain(key)
    }
    expect([...seen].sort()).toEqual(['ack', 'connect', 'ping', 'pub', 'sub', 'unsub'])
    client.close()
  })

  it('GIVEN the fixture server frames WHEN the client receives them SHOULD handle every one without leaving the open state', async () => {
    const client = make()
    const events: PushEvent[] = []
    const gaps: PushGap[] = []
    client.subscribe('orders:42', { onEvent: (e) => events.push(e), onGap: (g) => gaps.push(g), onError: () => {} })
    await MockWebSocket.last().serverOpen()

    for (const name of ['connected', 'subscribed', 'event', 'reset', 'overflowSession', 'pong', 'unsubscribed']) {
      MockWebSocket.last().serverSend(fixture.server[name])
    }

    const { type: _type, ...expected } = fixture.server.event
    expect(events).toEqual([expected])
    expect(gaps.map((g) => g.reason)).toEqual(['history_gap', 'session_overflow'])
    expect(client.state).toBe('open')
    client.close()
  })
})
