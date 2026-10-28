/** Frames and options of the push WebSocket protocol (`push.v1.json`). See docs/push.md in the gateway repo. */

/** One event as the gateway delivers it. `time` is epoch milliseconds. */
export interface PushEvent {
  topic: string
  id: string
  data: unknown
  attributes?: Record<string, string>
  key?: string
  time: number
}

/** Why events may be missing for a topic. */
export type GapReason =
  /** The topic's queue filled and the gateway unsubscribed it; the client has resubscribed from the last id. */
  | 'queue_full'
  /** A persistent session resumed past its queue limit; `dropped` events were lost, the subscription continues. */
  | 'session_overflow'
  /** The resume point is older than the retained history; the retained events follow. */
  | 'history_gap'
  /** The topic keeps no history, so nothing could be replayed. */
  | 'no_history'
  | (string & {})

export interface PushGap {
  topic: string
  reason: GapReason
  /** Only for `session_overflow`. */
  dropped?: number
}

/** A failure the client reports through `onError` or `client.on('error')`. */
export class PushClientError extends Error {
  /**
   * A gateway error code (`forbidden`, `unknown_namespace`, `bad_request`, ...) or one of the client's own:
   * `policy` (close 1008, not retried), `signed-out` (cookie mode, 4401), `session-ended` (close 4440: the browser session behind a cookie connection ended, not retried), `token` (the token function failed),
   * `not_open`, `timeout`, `closed`.
   */
  readonly code: string
  readonly topic?: string

  constructor(code: string, message: string, topic?: string) {
    super(message)
    this.name = 'PushClientError'
    this.code = code
    this.topic = topic
  }
}

export type PushAuth =
  /** Sends a `connect` frame with the token on every open and a `refresh` before the JWT's `exp`. */
  | { mode: 'token'; token: () => string | Promise<string> }
  /** Sends no `connect` frame: the handshake (the browser's cookies) is the credential. */
  | { mode: 'cookie' }
  | { mode: 'none' }

export interface BackoffOptions {
  /** Base delay in ms (default 500). */
  min?: number
  /** Cap in ms (default 30000). */
  max?: number
  /** Growth per attempt (default 2). */
  factor?: number
  /** Share of the delay randomised away, 0 to 1 (default 1: full jitter). */
  jitter?: number
}

/** The part of the WebSocket API the client uses; satisfied by the browser, Node's global and `ws`. */
export interface WebSocketLike {
  send(data: string): void
  close(code?: number, reason?: string): void
  // `never` parameters: any handler the client assigns fits, and the DOM's own event types fit too.
  onopen: ((ev: never) => unknown) | null
  onmessage: ((ev: never) => unknown) | null
  onclose: ((ev: never) => unknown) | null
  onerror: ((ev: never) => unknown) | null
}

export type WebSocketConstructor = new (url: string, protocols?: string | string[]) => WebSocketLike

export interface PushClientOptions {
  /** `ws(s)://host/push/ws` */
  url: string
  auth: PushAuth
  /** This client's origin id: events published with the same origin are not delivered back (echo suppression). */
  origin?: string
  backoff?: BackoffOptions
  /** Defaults to `globalThis.WebSocket`. Required on a runtime without one (pass `ws`'s WebSocket). */
  webSocket?: WebSocketConstructor
  /** Client `ping` interval in ms; 0 turns it off. Default: the gateway's `keepaliveMs`, else 25000. */
  pingIntervalMs?: number
  /** How long a publish or ack waits for its answer, ms (default 10000). */
  requestTimeoutMs?: number
  /** A connection open this long counts as stable and resets the backoff, ms (default 10000). */
  stableAfterMs?: number
  /** Refresh the token this long before its `exp`, ms (default 30000, at most half the remaining lifetime). */
  refreshSkewMs?: number
}

export type PushState = 'connecting' | 'open' | 'waiting' | 'closed'

export interface SubscribeOptions {
  /** Resume point: one id for every topic, or `{topic: id}`. */
  after?: string | Record<string, string>
  onEvent: (event: PushEvent) => void
  onGap?: (gap: PushGap) => void
  onError?: (error: PushClientError) => void
}

export interface Subscription {
  readonly topics: readonly string[]
  /** Acknowledges an event on a persistent session or consumer group; resolves on the gateway's `acked`. */
  ack(id: string, topic?: string): Promise<void>
  /** Unsubscribes. The topics stay subscribed while another subscription of this client uses them. */
  close(): void
}

export interface PublishOptions {
  attributes?: Record<string, string>
  key?: string
}

export interface PublishResult {
  topic: string
  id: string
}

export interface PushClient {
  readonly state: PushState
  /** Opens the connection now. `subscribe` does it too; call this to publish without subscribing. */
  connect(): void
  subscribe(topics: string | string[], options: SubscribeOptions): Subscription
  /** Needs an open connection; an offline publish is rejected, not queued. */
  publish(topic: string, data: unknown, options?: PublishOptions): Promise<PublishResult>
  /** Returns a function that removes the listener. */
  on(event: 'state', listener: (state: PushState, info?: { reason?: string }) => void): () => void
  on(event: 'error', listener: (error: PushClientError) => void): () => void
  /**
   * Every frame the gateway sends, as parsed, before the client acts on it: for logging and
   * debugging views. Frames that are not JSON never reach it.
   */
  on(event: 'frame', listener: (frame: ServerFrame) => void): () => void
  /** Stops reconnecting and timers, closes the socket. */
  close(): void
}

// ---- frames ----

export type ClientFrame =
  | { type: 'connect'; token?: string; origin?: string; session?: string }
  | { type: 'refresh'; token: string }
  | { type: 'sub'; topics: string[]; after?: Record<string, string>; origin?: string }
  | { type: 'unsub'; topics: string[] }
  | { type: 'pub'; topic: string; data: unknown; attributes?: Record<string, string>; key?: string }
  | { type: 'ack'; topic: string; id: string }
  | { type: 'ping' }

export type ServerFrame =
  | { type: 'connected'; connectionId: string; authenticated: boolean; keepaliveMs?: number }
  | { type: 'subscribed'; topic: string; position?: string; qos?: string }
  | { type: 'unsubscribed'; topic: string }
  | ({ type: 'event' } & PushEvent)
  | { type: 'published'; topic: string; id: string }
  | { type: 'acked'; topic: string; cursor: string }
  | { type: 'error'; code: string; message: string; topic?: string }
  | { type: 'overflow'; topic: string; reason: string; dropped?: number }
  | { type: 'reset'; topic: string; reason: string }
  | { type: 'pong' }
  | { type: 'close'; code: number; reason: string }
