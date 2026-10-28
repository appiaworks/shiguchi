import type { AuthProviderDto } from '../types/schema'
import { buildAuthHeaders } from './grpcUiApi'

/** What `GET <adminPath>` answers: this instance's push surface at a glance. */
export interface PushOverview {
  instance: string
  uptimeSeconds: number
  connections: { total: number; sse: number; ws: number; grpc: number; graphql?: number }
  subscriptions: number
  activeTopics: number
  namespaces: number
  engine: { broker: string; store: string; history: boolean; archive?: PushArchiveInfo }
  totals: { published: number; delivered: number; dropped: number }
  perSecond: { published: number; delivered: number; dropped: number }
  ws: { enabled: boolean; path: string }
  path: string
}

/** The archive behind the Messages view: off unless `archive.retention` is set. */
export interface PushArchiveInfo {
  enabled: boolean
  retention: string | null
  attributes: boolean
  fullText: boolean
  payloadVisibility: 'NONE' | 'METADATA' | 'FULL'
}

export interface PushNamespace {
  name: string
  qos: string
  historySize: number
  historyTtl: string | null
  clientQueueSize: number
  auth: string
  publishAuth: string
  clientPublish: boolean
  activeTopics: number
  subscribers: number
}

export interface PushTopic {
  topic: string
  subscribers: number
  history: number
  /** `owner` or `waiting` when an upstream stream backs the topic, else absent. */
  upstream?: string | null
}

export interface PushConnection {
  id: string
  transport: string
  principal: string | null
  since: string
  topics: string[]
  /** The instance holding the connection, from the cluster-wide registry. */
  instance?: string | null
  /** Optional device id sent at connect (`X-Push-Device`). */
  deviceId?: string | null
}

/** One archived event, as much of it as `admin.payload-visibility` lets through. */
export interface PushMessage {
  topic: string
  id: string
  time: string
  bytes: number
  typeUrl: string | null
  tombstoned: boolean
  tombstonedAt?: string
  tombstonedBy?: string | null
  key?: string | null
  attributes?: Record<string, string>
  data?: string
}

/** The search parameters of `GET <adminPath>/messages`; empty strings do not filter. */
export interface PushMessageQuery {
  topic?: string
  from?: string
  to?: string
  after?: string
  before?: string
  attr?: string
  type?: string
  q?: string
  limit?: number
}

export interface PushJob {
  id: string
  state: 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED'
  principal: string
  target: string | null
  reason: string | null
  matched: number
  published: number
  failed: number
  startedAt: string
  finishedAt: string | null
  error: string | null
}

export interface PushRepublishResult {
  dryRun: boolean
  origin: { topic: string; id: string }
  target: string
  id?: string
}

export interface PushBulkRepublishResult {
  dryRun: boolean
  matched: number
  capped: boolean
  maxBatch: number
  target: string | null
  job?: PushJob
}

export interface PushAuditEntry {
  time: string
  principal: string
  action: string
  arguments: Record<string, unknown>
  result: string
}

/** One consumer group's state on one topic. */
export interface PushGroupStatus {
  group: string
  topic: string
  pending: number
  members: number
  cursor: string | null
}

/** One persistent session's cursor on one topic. */
export interface PushSessionCursor {
  session: string
  topic: string
  cursor: string
  expiresAt: string | null
}

/** One source as the gateway runs it. */
export interface PushSourceInfo {
  name: string
  kind: string
  shared: boolean
  state: 'owner' | 'waiting' | 'running' | 'stopped'
  published: number
  failed: number
  lastEventAt: string | null
  lastError: string | null
}

/** One dead-lettered record. */
export interface PushDlqEntry {
  id: string
  time: string
  source: string
  reason: string
  topic: string | null
  bytes: number
  typeUrl: string | null
  key?: string | null
  attributes?: Record<string, string>
  data?: string
}

/** One connection's outcome for one event, from the delivery log of a `delivery-log: true` namespace. */
export interface PushDeliveryEntry {
  connectionId: string
  principal: string | null
  transport: string
  time: string
  outcome: string
}

/** What happened to one event: counts, and the per-connection log when the namespace keeps one. */
export interface PushDeliveries {
  topic: string
  id: string
  logged: boolean
  scope: 'cluster' | 'instance'
  delivered: number
  dropped: number
  log: PushDeliveryEntry[]
}

export interface PushAuth {
  token: string
  providerId: string | null
  providers: AuthProviderDto[]
}

function headers(auth: PushAuth, json = false): Record<string, string> {
  const h = buildAuthHeaders(auth.token, auth.providerId, auth.providers)
  if (json) h['Content-Type'] = 'application/json'
  return h
}

async function read<T>(url: string, auth: PushAuth): Promise<T> {
  const r = await fetch(url, { headers: headers(auth) })
  if (!r.ok) throw new Error(await errorText(r))
  return r.json() as Promise<T>
}

async function write<T>(url: string, auth: PushAuth, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
  const r = await fetch(url, {
    method,
    headers: headers(auth, body !== undefined),
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!r.ok) throw new Error(await errorText(r))
  return r.json() as Promise<T>
}

export function fetchPushOverview(adminPath: string, auth: PushAuth): Promise<PushOverview> {
  return read(adminPath, auth)
}

export async function fetchPushNamespaces(adminPath: string, auth: PushAuth): Promise<PushNamespace[]> {
  const body = await read<{ namespaces: PushNamespace[] }>(`${adminPath}/namespaces`, auth)
  return body.namespaces
}

export async function fetchPushTopics(adminPath: string, auth: PushAuth, q = ''): Promise<PushTopic[]> {
  const query = q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ''
  const body = await read<{ topics: PushTopic[] }>(`${adminPath}/topics${query}`, auth)
  return body.topics
}

export async function fetchPushConnections(adminPath: string, auth: PushAuth): Promise<PushConnection[]> {
  const body = await read<{ connections: PushConnection[] }>(`${adminPath}/connections`, auth)
  return body.connections
}

/** Ends one connection; the gateway sends it a `close` frame first. */
export async function disconnectPushConnection(adminPath: string, auth: PushAuth, id: string): Promise<void> {
  await write(`${adminPath}/connections/${encodeURIComponent(id)}`, auth, 'DELETE')
}

/** How a targeted send fared on one connection. */
export interface PushSendDelivery {
  connectionId: string
  instance?: string | null
  status: 'DELIVERED' | 'NOT_CONNECTED' | 'TIMEOUT' | 'REJECTED' | 'SUPPRESSED'
}

/**
 * Sends a JSON payload (as text) to one connection, wherever in the cluster it lives. With `requireAck`
 * the answer waits (up to `timeoutMs`) for the client to acknowledge the event.
 */
export function sendToPushConnection(
  adminPath: string,
  auth: PushAuth,
  id: string,
  data: string,
  requireAck = false,
  timeoutMs?: number,
): Promise<{ id: string; deliveries: PushSendDelivery[] }> {
  return write(`${adminPath}/connections/${encodeURIComponent(id)}/send`, auth, 'POST', { data, requireAck, timeoutMs })
}

/** Publishes a test event as the operator; `data` is the JSON payload as text. */
export function publishPushEvent(
  adminPath: string,
  auth: PushAuth,
  topic: string,
  data: string,
): Promise<{ topic: string; id: string }> {
  return write(`${adminPath}/publish`, auth, 'POST', { topic, data })
}

/** The query string of a message search, without the leading `?`. */
export function messageQueryString(query: PushMessageQuery): string {
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || String(v).trim() === '') continue
    params.set(k, String(v).trim())
  }
  return params.toString()
}

/** Archived events matching the query, newest first. */
export async function searchPushMessages(adminPath: string, auth: PushAuth, query: PushMessageQuery): Promise<PushMessage[]> {
  const qs = messageQueryString(query)
  const body = await read<{ messages: PushMessage[] }>(`${adminPath}/messages${qs ? `?${qs}` : ''}`, auth)
  return body.messages
}

/** One archived event; the topic travels as a parameter since a topic name may contain `/`. */
export function fetchPushMessage(adminPath: string, auth: PushAuth, topic: string, id: string): Promise<PushMessage> {
  return read(`${adminPath}/messages/${encodeURIComponent(id)}?topic=${encodeURIComponent(topic)}`, auth)
}

/** Republishes one archived event; a dry run says what would be sent. */
export function republishPushMessage(
  adminPath: string,
  auth: PushAuth,
  topic: string,
  id: string,
  options: { target?: string; reason?: string; dryRun: boolean },
): Promise<PushRepublishResult> {
  const body: Record<string, unknown> = { topic, dryRun: options.dryRun }
  if (options.target?.trim()) body.target = options.target.trim()
  if (options.reason?.trim()) body.reason = options.reason.trim()
  return write(`${adminPath}/messages/${encodeURIComponent(id)}/republish`, auth, 'POST', body)
}

/** Republishes every event a query matches: a dry run counts, a real run starts a job. */
export function republishPushMessages(
  adminPath: string,
  auth: PushAuth,
  query: PushMessageQuery,
  options: { target?: string; reason?: string; dryRun: boolean },
): Promise<PushBulkRepublishResult> {
  const body: Record<string, unknown> = { dryRun: options.dryRun }
  for (const [k, v] of Object.entries(query)) {
    if (k === 'limit' || v === undefined || v === null || String(v).trim() === '') continue
    body[k] = String(v).trim()
  }
  if (options.target?.trim()) body.target = options.target.trim()
  if (options.reason?.trim()) body.reason = options.reason.trim()
  return write(`${adminPath}/messages/republish`, auth, 'POST', body)
}

export function fetchPushJob(adminPath: string, auth: PushAuth, id: string): Promise<PushJob> {
  return read(`${adminPath}/jobs/${encodeURIComponent(id)}`, auth)
}

export async function fetchPushJobs(adminPath: string, auth: PushAuth): Promise<PushJob[]> {
  const body = await read<{ jobs: PushJob[] }>(`${adminPath}/jobs`, auth)
  return body.jobs
}

export async function cancelPushJob(adminPath: string, auth: PushAuth, id: string): Promise<void> {
  await write(`${adminPath}/jobs/${encodeURIComponent(id)}`, auth, 'DELETE')
}

/** Redacts one archived event: payload gone, metadata kept, out of the history. */
export async function tombstonePushMessage(adminPath: string, auth: PushAuth, topic: string, id: string): Promise<void> {
  await write(`${adminPath}/messages/${encodeURIComponent(id)}?topic=${encodeURIComponent(topic)}`, auth, 'DELETE')
}

/** Delivered and dropped counts of one event, plus the per-connection log for a logged namespace. */
export function fetchPushDeliveries(adminPath: string, auth: PushAuth, topic: string, id: string, limit = 200): Promise<PushDeliveries> {
  return read(`${adminPath}/messages/${encodeURIComponent(id)}/deliveries?topic=${encodeURIComponent(topic)}&limit=${limit}`, auth)
}

/** The export URL of a query on the push path: NDJSON, one archived event per line. */
export function pushExportUrl(pushPath: string, query: PushMessageQuery): string {
  const qs = messageQueryString(query)
  return `${pushPath}/export${qs ? `?${qs}` : ''}`
}

/** Downloads the export of a query: the NDJSON text, one event per line (the caller saves it). */
export async function exportPushMessages(pushPath: string, auth: PushAuth, query: PushMessageQuery): Promise<string> {
  const r = await fetch(pushExportUrl(pushPath, query), { headers: headers(auth) })
  if (!r.ok) throw new Error(await errorText(r))
  return r.text()
}

export async function fetchPushGroups(adminPath: string, auth: PushAuth, prefix = '', limit = 200): Promise<PushGroupStatus[]> {
  const q = prefix ? `&q=${encodeURIComponent(prefix)}` : ''
  const body = await read<{ groups: PushGroupStatus[] }>(`${adminPath}/groups?limit=${limit}${q}`, auth)
  return body.groups
}

/** Forgets a consumer group on every topic; its next member starts at the head. */
export async function dropPushGroup(adminPath: string, auth: PushAuth, group: string): Promise<void> {
  await write(`${adminPath}/groups/${encodeURIComponent(group)}`, auth, 'DELETE')
}

/** Moves a group's position on `topic` to `to` (an id, `first` or `head`); its claims are dropped and it redelivers from there. */
export function seekPushGroup(adminPath: string, auth: PushAuth, group: string, topic: string, to: string): Promise<{ seeked: boolean }> {
  return write(`${adminPath}/groups/${encodeURIComponent(group)}/seek`, auth, 'POST', { topic, to })
}

/** Moves a persistent session's cursor on `topic` to `to` (an id, `first` or `head`), forward or back. */
export function seekPushSession(adminPath: string, auth: PushAuth, session: string, topic: string, to: string): Promise<{ seeked: boolean }> {
  return write(`${adminPath}/sessions/${encodeURIComponent(session)}/seek`, auth, 'POST', { topic, to })
}

export async function fetchPushSessions(adminPath: string, auth: PushAuth, prefix = '', limit = 200): Promise<PushSessionCursor[]> {
  const q = prefix ? `&q=${encodeURIComponent(prefix)}` : ''
  const body = await read<{ sessions: PushSessionCursor[] }>(`${adminPath}/sessions?limit=${limit}${q}`, auth)
  return body.sessions
}

/** Forgets a session on every topic; it starts at the head next time. */
export async function dropPushSession(adminPath: string, auth: PushAuth, session: string): Promise<void> {
  await write(`${adminPath}/sessions/${encodeURIComponent(session)}`, auth, 'DELETE')
}

export async function fetchPushSources(adminPath: string, auth: PushAuth): Promise<PushSourceInfo[]> {
  const body = await read<{ sources: PushSourceInfo[] }>(`${adminPath}/sources`, auth)
  return body.sources
}

export async function fetchPushDlq(adminPath: string, auth: PushAuth, source = '', limit = 200): Promise<PushDlqEntry[]> {
  const q = source ? `&q=${encodeURIComponent(source)}` : ''
  const body = await read<{ dlq: PushDlqEntry[] }>(`${adminPath}/dlq?limit=${limit}${q}`, auth)
  return body.dlq
}

/** Publishes a dead-lettered record again, to its intended topic or `topic`. */
export function retryPushDlq(adminPath: string, auth: PushAuth, id: string, topic = ''): Promise<{ id: string; topic: string; eventId: string }> {
  return write(`${adminPath}/dlq/${encodeURIComponent(id)}/retry`, auth, 'POST', topic ? { topic } : {})
}

export async function deletePushDlq(adminPath: string, auth: PushAuth, id: string): Promise<void> {
  await write(`${adminPath}/dlq/${encodeURIComponent(id)}`, auth, 'DELETE')
}

export async function fetchPushAudit(adminPath: string, auth: PushAuth, limit = 200): Promise<PushAuditEntry[]> {
  const body = await read<{ audit: PushAuditEntry[] }>(`${adminPath}/audit?limit=${limit}`, auth)
  return body.audit
}

async function errorText(r: Response): Promise<string> {
  try {
    const body = (await r.json()) as { error?: string; message?: string }
    if (body.message) return `${r.status}: ${body.message}`
  } catch {
    // not JSON
  }
  return `${r.status} ${r.statusText}`
}

/** The WebSocket URL of the push surface for this page's host, for the live tail. */
export function pushWebSocketUrl(pushPath: string, location: { protocol: string; host: string } = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}${pushPath.replace(/\/+$/, '')}/ws`
}
