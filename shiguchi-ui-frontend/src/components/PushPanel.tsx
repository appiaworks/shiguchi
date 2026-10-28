import { useCallback, useEffect, useRef, useState } from 'react'
import {
  cancelPushJob,
  disconnectPushConnection,
  fetchPushAudit,
  fetchPushConnections,
  fetchPushJob,
  fetchPushNamespaces,
  fetchPushOverview,
  fetchPushTopics,
  publishPushEvent,
  pushWebSocketUrl,
  republishPushMessage,
  republishPushMessages,
  searchPushMessages,
  sendToPushConnection,
  tombstonePushMessage,
  exportPushMessages,
  fetchPushDeliveries,
  fetchPushSources,
  fetchPushSessions,
  dropPushSession,
  fetchPushGroups,
  dropPushGroup,
  seekPushGroup,
  seekPushSession,
  type PushGroupStatus,
  type PushSessionCursor,
  fetchPushDlq,
  retryPushDlq,
  deletePushDlq,
  type PushSourceInfo,
  type PushDlqEntry,
  type PushDeliveries,
  type PushArchiveInfo,
  type PushAuditEntry,
  type PushAuth,
  type PushConnection,
  type PushJob,
  type PushMessage,
  type PushMessageQuery,
  type PushNamespace,
  type PushOverview,
  type PushTopic,
} from '../api/pushAdminApi'
import { createPushClient } from '@appiaworks/shiguchi-cursus-client'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import type { AuthProviderDto } from '../types/schema'

type View = 'overview' | 'topics' | 'connections' | 'messages' | 'publish' | 'sources' | 'sessions' | 'audit'

interface Props {
  adminPath: string
  pushPath: string
}

const REFRESH_MS = 2000

// A stable empty list: a selector that returned a fresh `[]` each call would re-render forever.
const NO_PROVIDERS: AuthProviderDto[] = []

const INPUT =
  'bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded outline-none placeholder-slate-700 border border-slate-700 focus:border-slate-500'
const SMALL_BUTTON = 'text-[10px] px-1.5 py-0.5 rounded border border-slate-700 text-slate-400 hover:text-slate-200 hover:border-slate-500 disabled:opacity-50'
const DANGER_BUTTON = 'text-[10px] px-1.5 py-0.5 rounded border border-red-900 text-red-400 hover:border-red-700 disabled:opacity-50'

/**
 * The push surface's admin views over the `gateway-push` actuator endpoint: the instance at a
 * glance, the namespaces, the topics with live subscribers (and a live tail of one over the
 * WebSocket transport), the open connections (with disconnect), the archived messages (search,
 * detail with its deliveries, republish, tombstone, bulk republish as a job, NDJSON export), a test publish, the sources with the dead-letter queue, the persistent sessions and consumer groups, and the audit log of
 * the operator's writes. Reads refresh every two seconds; writes carry the console's global auth
 * token.
 */
export function PushPanel({ adminPath, pushPath }: Props) {
  const token = useGrpcUiStore((s) => s.globalAuthToken)
  const providerId = useGrpcUiStore((s) => s.globalProviderId)
  const providers = useGrpcUiStore((s) => s.links?.authProviders ?? NO_PROVIDERS)
  const auth: PushAuth = { token, providerId, providers }

  const [view, setView] = useState<View>('overview')
  const [error, setError] = useState<string | null>(null)

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center gap-1 px-3 py-1.5 border-b border-slate-700 shrink-0">
        <span className="text-[10px] text-slate-600 uppercase tracking-wider mr-2">Push</span>
        {(['overview', 'topics', 'connections', 'messages', 'publish', 'sources', 'sessions', 'audit'] as View[]).map((v) => (
          <button
            key={v}
            onClick={() => setView(v)}
            className={`text-xs px-2 py-0.5 rounded border transition-colors ${
              view === v
                ? 'border-blue-600 text-blue-300 bg-blue-900/20'
                : 'border-slate-700 text-slate-500 hover:text-slate-300 hover:border-slate-600'
            }`}
          >
            {v}
          </button>
        ))}
        <div className="flex-1" />
        {error && <span className="text-xs text-red-400 truncate max-w-md">{error}</span>}
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto p-3">
        {view === 'overview' && <Overview adminPath={adminPath} auth={auth} onError={setError} />}
        {view === 'topics' && <Topics adminPath={adminPath} pushPath={pushPath} auth={auth} onError={setError} />}
        {view === 'connections' && <Connections adminPath={adminPath} auth={auth} onError={setError} />}
        {view === 'messages' && <Messages adminPath={adminPath} pushPath={pushPath} auth={auth} onError={setError} />}
        {view === 'publish' && <Publish adminPath={adminPath} auth={auth} onError={setError} />}
        {view === 'sources' && <Sources adminPath={adminPath} auth={auth} onError={setError} />}
        {view === 'sessions' && <Sessions adminPath={adminPath} auth={auth} onError={setError} />}
        {view === 'audit' && <Audit adminPath={adminPath} auth={auth} onError={setError} />}
      </div>
    </div>
  )
}

/** Polls `load` every two seconds while mounted; errors go to the panel's error line. */
function usePolling<T>(load: () => Promise<T>, onError: (e: string | null) => void): T | null {
  const [value, setValue] = useState<T | null>(null)
  useEffect(() => {
    let alive = true
    const tick = () =>
      load()
        .then((v) => {
          if (!alive) return
          setValue(v)
          onError(null)
        })
        .catch((e: unknown) => alive && onError(e instanceof Error ? e.message : String(e)))
    tick()
    const timer = setInterval(tick, REFRESH_MS)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [load, onError])
  return value
}

function Stat({ label, value, tone = 'text-slate-200' }: { label: string; value: string | number; tone?: string }) {
  return (
    <div className="bg-slate-800 rounded px-3 py-2 min-w-[7rem]">
      <div className="text-[10px] uppercase tracking-wider text-slate-500">{label}</div>
      <div className={`text-lg font-semibold ${tone}`}>{value}</div>
    </div>
  )
}

function Overview({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const load = useCallback(
    () => Promise.all([fetchPushOverview(adminPath, auth), fetchPushNamespaces(adminPath, auth)]),
    // The token is the only part of `auth` that changes; the arrays are rebuilt each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId],
  )
  const data = usePolling<[PushOverview, PushNamespace[]]>(load, onError)
  if (!data) return <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
  const [o, namespaces] = data
  const archive = o.engine.archive
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Stat label="connections" value={o.connections.total} />
        <Stat label="sse" value={o.connections.sse} tone="text-slate-400" />
        <Stat label="ws" value={o.connections.ws} tone="text-slate-400" />
        <Stat label="grpc" value={o.connections.grpc} tone="text-slate-400" />
        <Stat label="graphql" value={o.connections.graphql ?? 0} tone="text-slate-400" />
        <Stat label="subscriptions" value={o.subscriptions} />
        <Stat label="active topics" value={o.activeTopics} />
        <Stat label="published /s" value={o.perSecond.published} tone="text-emerald-400" />
        <Stat label="delivered /s" value={o.perSecond.delivered} tone="text-emerald-400" />
        <Stat label="dropped" value={o.totals.dropped} tone={o.totals.dropped > 0 ? 'text-yellow-400' : 'text-slate-400'} />
      </div>
      <div className="text-xs text-slate-500">
        instance <span className="text-slate-300">{o.instance}</span> · up {formatDuration(o.uptimeSeconds)} · broker{' '}
        <span className="text-slate-300">{o.engine.broker}</span> · store <span className="text-slate-300">{o.engine.store}</span>
        {' · archive '}
        <span className="text-slate-300">{archive?.enabled ? `${archive.retention} (payload ${archive.payloadVisibility})` : 'off'}</span>
        {o.engine.broker === 'MEMORY' && (
          <span className="text-yellow-600"> · per instance: this view covers this instance only</span>
        )}
        {' · '}
        {o.totals.published} published, {o.totals.delivered} delivered in total
      </div>
      <table className="w-full text-xs">
        <thead>
          <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
            <th className="py-1">namespace</th>
            <th>qos</th>
            <th>history</th>
            <th>queue</th>
            <th>subscribe auth</th>
            <th>publish auth</th>
            <th>client publish</th>
            <th className="text-right">topics</th>
            <th className="text-right">subscribers</th>
          </tr>
        </thead>
        <tbody>
          {namespaces.map((n) => (
            <tr key={n.name} className="border-t border-slate-800 text-slate-300">
              <td className="py-1 font-mono">{n.name}</td>
              <td>{n.qos}</td>
              <td>{n.historySize > 0 ? `${n.historySize} / ${n.historyTtl}` : '—'}</td>
              <td>{n.clientQueueSize}</td>
              <td>{n.auth}</td>
              <td>{n.publishAuth}</td>
              <td>{n.clientPublish ? 'yes' : 'no'}</td>
              <td className="text-right">{n.activeTopics}</td>
              <td className="text-right">{n.subscribers}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Topics({ adminPath, pushPath, auth, onError }: { adminPath: string; pushPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const [q, setQ] = useState('')
  const [tail, setTail] = useState<string | null>(null)
  const load = useCallback(
    () => fetchPushTopics(adminPath, auth, q),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId, q],
  )
  const topics = usePolling<PushTopic[]>(load, onError)
  return (
    <div className="space-y-3">
      <input
        type="search"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder="Filter topics…"
        aria-label="Filter topics"
        className={`w-full max-w-sm ${INPUT}`}
      />
      {topics === null ? (
        <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
      ) : topics.length === 0 ? (
        <div className="text-xs text-slate-500">No topic has a subscriber on this instance.</div>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
              <th className="py-1">topic</th>
              <th className="text-right">subscribers</th>
              <th className="text-right">history</th>
              <th>upstream</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {topics.map((t) => (
              <tr key={t.topic} className="border-t border-slate-800 text-slate-300">
                <td className="py-1 font-mono">{t.topic}</td>
                <td className="text-right">{t.subscribers}</td>
                <td className="text-right">{t.history}</td>
                <td className={t.upstream === 'owner' ? 'text-emerald-400' : 'text-slate-500'}>{t.upstream ?? ''}</td>
                <td className="text-right">
                  <button onClick={() => setTail(tail === t.topic ? null : t.topic)} className={SMALL_BUTTON}>
                    {tail === t.topic ? 'stop' : 'tail'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tail && <Tail topic={tail} pushPath={pushPath} token={auth.token} />}
    </div>
  )
}

type TailState = 'connecting' | 'open' | 'waiting' | 'closed'
interface TailEntry {
  kind: 'event' | 'gap' | 'error' | 'frame'
  text: string
}

/** Frames the tail already shows as events, gaps or errors, or that would only add noise. */
const QUIET_FRAMES = new Set(['event', 'overflow', 'reset', 'error', 'pong'])

/**
 * The last 50 entries of one topic over the WebSocket transport, through `@appiaworks/shiguchi-cursus-client`:
 * it reconnects with backoff, resumes from the last delivered id, refreshes the console's token
 * before it expires, and reports gaps (overflow, reset) as their own entries. Protocol frames such
 * as `connected` and `subscribed` are listed too, dimmed.
 */
function Tail({ topic, pushPath, token }: { topic: string; pushPath: string; token: string }) {
  const [entries, setEntries] = useState<TailEntry[]>([])
  const [state, setState] = useState<TailState>('connecting')
  // The token function reads the latest value, so a changed token is used on the next connect or
  // refresh without tearing the connection down; only signing in or out reconnects.
  const tokenRef = useRef(token)
  useEffect(() => {
    tokenRef.current = token
  }, [token])
  const signedIn = token.trim().length > 0
  useEffect(() => {
    setEntries([])
    setState('connecting')
    // The client reports a refusal to the subscription and to the client; show it once.
    const add = (entry: TailEntry) =>
      setEntries((prev) => (entry.kind === 'error' && prev[prev.length - 1]?.text === entry.text ? prev : [...prev.slice(-49), entry]))
    const client = createPushClient({
      url: pushWebSocketUrl(pushPath),
      auth: signedIn ? { mode: 'token', token: () => tokenRef.current.trim() } : { mode: 'none' },
    })
    client.on('state', (next, info) => {
      setState(next)
      if (info?.reason) add({ kind: 'error', text: info.reason })
    })
    client.on('error', (e) => add({ kind: 'error', text: `${e.code}: ${e.message}` }))
    client.on('frame', (f) => {
      if (!QUIET_FRAMES.has(f.type)) add({ kind: 'frame', text: JSON.stringify(f) })
    })
    client.subscribe(topic, {
      onEvent: (e) => add({ kind: 'event', text: JSON.stringify(e) }),
      onGap: (g) =>
        add({
          kind: 'gap',
          text: `gap on ${g.topic}: ${g.reason}${g.dropped !== undefined ? ` (${g.dropped} dropped)` : ''}`,
        }),
      onError: (e) => add({ kind: 'error', text: `${e.code}: ${e.message}` }),
    })
    return () => client.close()
  }, [topic, pushPath, signedIn])
  return (
    <div className="border border-slate-700 rounded">
      <div className="flex items-center gap-2 px-2 py-1 border-b border-slate-700 text-xs">
        <span className="text-slate-400">tail</span>
        <span className="font-mono text-slate-200">{topic}</span>
        <span
          className={`text-[10px] ${
            state === 'open' ? 'text-green-500' : state === 'closed' ? 'text-red-400' : state === 'waiting' ? 'text-yellow-400' : 'text-slate-500'
          }`}
        >
          {state}
        </span>
        <span className="text-slate-600 text-[10px]">{entries.filter((e) => e.kind === 'event').length} events</span>
      </div>
      <div className="max-h-64 overflow-y-auto p-1 space-y-0.5">
        {entries.map((e, i) => (
          <pre
            key={i}
            className={`text-[11px] rounded px-1 py-0.5 overflow-x-auto whitespace-pre-wrap break-all ${
              e.kind === 'event'
                ? 'text-slate-300 bg-slate-800'
                : e.kind === 'gap'
                  ? 'text-yellow-300 bg-yellow-900/20 border border-yellow-800'
                  : e.kind === 'frame'
                    ? 'text-slate-500'
                    : 'text-red-300 bg-red-900/20'
            }`}
          >
            {e.text}
          </pre>
        ))}
      </div>
    </div>
  )
}

function Connections({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const load = useCallback(
    () => fetchPushConnections(adminPath, auth),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId],
  )
  const connections = usePolling<PushConnection[]>(load, onError)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const send = async (c: PushConnection, data: string, requireAck: boolean) => {
    setBusy(c.id)
    try {
      const out = await sendToPushConnection(adminPath, auth, c.id, data, requireAck)
      setNotice(`sent ${out.id.slice(0, 8)}: ${out.deliveries.map((d) => d.status).join(', ')}`)
      onError(null)
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }
  const disconnect = async (c: PushConnection) => {
    setBusy(c.id)
    try {
      await disconnectPushConnection(adminPath, auth, c.id)
      onError(null)
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }
  if (connections === null) return <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
  if (connections.length === 0) return <div className="text-xs text-slate-500">No connection is open.</div>
  const instances = new Set(connections.map((c) => c.instance).filter(Boolean))
  return (
    <>
    {instances.size > 0 && (
      <div className="mb-2 text-[10px] text-slate-500">
        cluster-wide registry: {connections.length} connection{connections.length === 1 ? '' : 's'} on {instances.size} instance
        {instances.size === 1 ? '' : 's'}
      </div>
    )}
    {notice && <div className="mb-2 text-[10px] text-emerald-400">{notice}</div>}
    <table className="w-full text-xs">
      <thead>
        <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
          <th className="py-1">id</th>
          <th>instance</th>
          <th>transport</th>
          <th>principal</th>
          <th>device</th>
          <th>topics</th>
          <th>since</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {connections.map((c) => (
          <tr key={c.id} className="border-t border-slate-800 text-slate-300">
            <td className="py-1 font-mono text-[10px] text-slate-400">{c.id.slice(0, 8)}</td>
            <td className="font-mono text-[10px]">{c.instance ?? <span className="text-slate-600">local</span>}</td>
            <td>{c.transport}</td>
            <td>{c.principal ?? <span className="text-slate-600">anonymous</span>}</td>
            <td className="font-mono text-[10px]">{c.deviceId ?? <span className="text-slate-600">-</span>}</td>
            <td className="font-mono">{c.topics.join(', ')}</td>
            <td className="text-slate-500">{new Date(c.since).toLocaleTimeString()}</td>
            <td className="text-right">
              <span className="inline-flex items-center gap-1">
                <SendControl disabled={busy === c.id} run={(data, ack) => send(c, data, ack)} />
                <button onClick={() => disconnect(c)} disabled={busy === c.id} className={DANGER_BUTTON}>
                  disconnect
                </button>
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
    </>
  )
}

const EMPTY_QUERY: PushMessageQuery = { topic: '', from: '', to: '', attr: '', type: '', q: '', limit: 100 }

/**
 * The archive: a query builder over `GET .../messages`, the results newest first, a detail
 * drawer with the actions on one event (republish, republish to another topic, tombstone), and
 * the bulk republish of the query (dry run first, then a job with progress and cancel).
 */
function Messages({
  adminPath,
  pushPath,
  auth,
  onError,
}: {
  adminPath: string
  pushPath: string
  auth: PushAuth
  onError: (e: string | null) => void
}) {
  const [archive, setArchive] = useState<PushArchiveInfo | null | undefined>(undefined)
  const [draft, setDraft] = useState<PushMessageQuery>(EMPTY_QUERY)
  const [query, setQuery] = useState<PushMessageQuery | null>(null)
  const [results, setResults] = useState<PushMessage[] | null>(null)
  const [selected, setSelected] = useState<PushMessage | null>(null)
  const [job, setJob] = useState<PushJob | null>(null)
  const [bulk, setBulk] = useState<{ matched: number; capped: boolean; maxBatch: number } | null>(null)
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    fetchPushOverview(adminPath, auth)
      .then((o) => alive && setArchive(o.engine.archive ?? null))
      .catch((e: unknown) => alive && onError(e instanceof Error ? e.message : String(e)))
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adminPath, auth.token, auth.providerId])

  const run = useCallback(
    async (q: PushMessageQuery) => {
      setBusy(true)
      try {
        const found = await searchPushMessages(adminPath, auth, q)
        setResults(found)
        setQuery(q)
        setBulk(null)
        onError(null)
      } catch (e: unknown) {
        onError(e instanceof Error ? e.message : String(e))
      } finally {
        setBusy(false)
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId, onError],
  )

  const act = async (work: () => Promise<string | null>) => {
    setBusy(true)
    try {
      const note = await work()
      onError(null)
      if (note) setNotice(note)
      if (query) await run(query)
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }
  const [notice, setNotice] = useState<string | null>(null)

  if (archive === undefined) return <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
  if (!archive || !archive.enabled) {
    return (
      <div className="text-xs text-slate-500 max-w-2xl">
        No archive: set <span className="font-mono">shiguchi.cursus.archive.retention</span> above zero (store{' '}
        <span className="font-mono">POSTGRES</span> keeps the archive in its table; <span className="font-mono">MEMORY</span> keeps a
        per-instance ring) to search, republish and tombstone events here.
      </div>
    )
  }

  const field = (key: keyof PushMessageQuery, placeholder: string, extra = '') => (
    <input
      key={key}
      value={String(draft[key] ?? '')}
      onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
      placeholder={placeholder}
      aria-label={placeholder}
      className={`${INPUT} font-mono ${extra}`}
    />
  )

  return (
    <div className="space-y-3">
      <div className="text-xs text-slate-500">
        archive {archive.retention} · payload {archive.payloadVisibility}
        {archive.payloadVisibility !== 'FULL' && (
          <span className="text-slate-600"> (set admin.payload-visibility FULL to see payloads)</span>
        )}
        {!archive.fullText && <span className="text-slate-600"> · text search not available on this store</span>}
      </div>
      <form
        className="flex flex-wrap gap-2 items-center"
        onSubmit={(e) => {
          e.preventDefault()
          void run(draft)
        }}
      >
        {field('topic', 'topic or pattern (orders:*)', 'w-48')}
        {field('from', 'from (ISO instant)', 'w-44')}
        {field('to', 'to (ISO instant)', 'w-44')}
        {archive.attributes && field('attr', 'attr k:v,k2:v2', 'w-40')}
        {field('type', 'type URL or json', 'w-40')}
        {archive.fullText && field('q', 'text', 'w-40')}
        <input
          type="number"
          min={1}
          max={1000}
          value={draft.limit ?? 100}
          onChange={(e) => setDraft({ ...draft, limit: Number(e.target.value) })}
          aria-label="limit"
          className={`${INPUT} w-20`}
        />
        <button type="submit" disabled={busy} className="text-xs px-3 py-1 rounded bg-blue-700 text-white hover:bg-blue-600 disabled:opacity-50">
          Search
        </button>
        {notice && <span className="text-xs text-emerald-400">{notice}</span>}
      </form>

      {results !== null && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-slate-500">{results.length} event(s)</span>
          <button
            disabled={busy}
            className={SMALL_BUTTON}
            title="download every event matching this query as NDJSON (up to the export limit)"
            onClick={() =>
              act(async () => {
                const text = await exportPushMessages(pushPath, auth, { ...query!, limit: undefined })
                saveText(text, 'push-export.ndjson')
                return `exported ${text.split('\n').filter((l) => l.length > 0).length} event(s)`
              })
            }
          >
            export NDJSON
          </button>
          <span className="text-slate-700">|</span>
          <span className="text-slate-500">bulk republish of this query:</span>
          <input value={target} onChange={(e) => setTarget(e.target.value)} placeholder="target topic (optional)" aria-label="Bulk target topic" className={`${INPUT} font-mono w-44`} />
          <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="reason (optional)" aria-label="Bulk reason" className={`${INPUT} w-40`} />
          <button
            disabled={busy || !query}
            className={SMALL_BUTTON}
            onClick={() =>
              act(async () => {
                const r = await republishPushMessages(adminPath, auth, query!, { target, reason, dryRun: true })
                setBulk({ matched: r.matched, capped: r.capped, maxBatch: r.maxBatch })
                return null
              })
            }
          >
            dry run query
          </button>
          {bulk && (
            <>
              <span className={bulk.capped ? 'text-yellow-400' : 'text-slate-300'}>
                would republish {bulk.matched}
                {bulk.capped ? ` (more than max-batch ${bulk.maxBatch}: narrow the query)` : ''}
                {target.trim() ? ` to ${target.trim()}` : ' in place'}
              </span>
              {!bulk.capped && bulk.matched > 0 && (
                <button
                  disabled={busy}
                  className={DANGER_BUTTON}
                  onClick={() =>
                    act(async () => {
                      const r = await republishPushMessages(adminPath, auth, query!, { target, reason, dryRun: false })
                      if (r.job) setJob(r.job)
                      setBulk(null)
                      return `job ${r.job?.id.slice(0, 8) ?? '?'} started`
                    })
                  }
                >
                  confirm republish {bulk.matched}
                </button>
              )}
            </>
          )}
        </div>
      )}

      {job && <JobPanel adminPath={adminPath} auth={auth} job={job} onChange={setJob} onError={onError} />}

      {results === null ? null : results.length === 0 ? (
        <div className="text-xs text-slate-500">No archived event matches.</div>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
              <th className="py-1">time</th>
              <th>topic</th>
              <th>id</th>
              <th>type</th>
              <th className="text-right">bytes</th>
              <th>attributes</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {results.map((m) => (
              <tr
                key={`${m.topic}/${m.id}`}
                className={`border-t border-slate-800 text-slate-300 cursor-pointer hover:bg-slate-800/50 ${selected?.id === m.id && selected.topic === m.topic ? 'bg-slate-800' : ''}`}
                onClick={() => setSelected(m)}
              >
                <td className="py-1 text-slate-500 whitespace-nowrap">{new Date(m.time).toLocaleString()}</td>
                <td className="font-mono">{m.topic}</td>
                <td className="font-mono text-[10px] text-slate-400">{m.id}</td>
                <td className="font-mono text-[10px] text-slate-400">{m.typeUrl ? m.typeUrl.split('/').pop() : 'json'}</td>
                <td className="text-right">{m.bytes}</td>
                <td className="text-slate-500 truncate max-w-xs">{m.attributes ? Object.entries(m.attributes).map(([k, v]) => `${k}=${v}`).join(' ') : ''}</td>
                <td className="text-right">{m.tombstoned && <span className="text-[10px] text-red-400">tombstone</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {selected && (
        <MessageDetail
          adminPath={adminPath}
          auth={auth}
          message={selected}
          busy={busy}
          onClose={() => setSelected(null)}
          onAct={act}
        />
      )}
    </div>
  )
}

function MessageDetail({
  adminPath,
  auth,
  message: m,
  busy,
  onClose,
  onAct,
}: {
  adminPath: string
  auth: PushAuth
  message: PushMessage
  busy: boolean
  onClose: () => void
  onAct: (work: () => Promise<string | null>) => Promise<void>
}) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<string | null>(null)
  const [confirmTombstone, setConfirmTombstone] = useState(false)
  let pretty = m.data
  if (m.data) {
    try {
      pretty = JSON.stringify(JSON.parse(m.data), null, 2)
    } catch {
      // shown as is
    }
  }
  return (
    <div className="border border-slate-700 rounded p-2 space-y-2 text-xs">
      <div className="flex items-center gap-2">
        <span className="font-mono text-slate-200">{m.topic}</span>
        <span className="font-mono text-[10px] text-slate-400">{m.id}</span>
        <span className="text-slate-500">{new Date(m.time).toLocaleString()}</span>
        {m.tombstoned && (
          <span className="text-red-400">
            tombstoned {m.tombstonedAt ? new Date(m.tombstonedAt).toLocaleString() : ''} by {m.tombstonedBy ?? '?'}
          </span>
        )}
        <div className="flex-1" />
        <button onClick={onClose} className={SMALL_BUTTON}>
          close
        </button>
      </div>
      <div className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-slate-400">
        <span>type</span>
        <span className="font-mono text-slate-300">{m.typeUrl ?? 'json'}</span>
        <span>bytes</span>
        <span className="text-slate-300">{m.bytes}</span>
        {m.key !== undefined && (
          <>
            <span>key</span>
            <span className="font-mono text-slate-300">{m.key ?? '—'}</span>
          </>
        )}
        {m.attributes && (
          <>
            <span>attributes</span>
            <span className="font-mono text-slate-300">
              {Object.keys(m.attributes).length === 0 ? '—' : Object.entries(m.attributes).map(([k, v]) => `${k}=${v}`).join('  ')}
            </span>
          </>
        )}
      </div>
      {pretty !== undefined ? (
        <pre className="text-[11px] text-slate-300 bg-slate-800 rounded px-2 py-1 overflow-x-auto max-h-64 whitespace-pre-wrap break-all">{pretty}</pre>
      ) : (
        <div className="text-slate-600">payload hidden by admin.payload-visibility</div>
      )}
      <Deliveries adminPath={adminPath} auth={auth} topic={m.topic} id={m.id} />
      {!m.tombstoned && (
        <div className="flex flex-wrap items-center gap-2">
          <input value={target} onChange={(e) => setTarget(e.target.value)} placeholder="republish to (optional)" aria-label="Republish target topic" className={`${INPUT} font-mono w-44`} />
          <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="reason (optional)" aria-label="Republish reason" className={`${INPUT} w-40`} />
          <button
            disabled={busy}
            className={SMALL_BUTTON}
            onClick={() =>
              onAct(async () => {
                const r = await republishPushMessage(adminPath, auth, m.topic, m.id, { target, reason, dryRun: true })
                setPreview(`would republish ${r.origin.topic} ${r.origin.id} to ${r.target}`)
                return null
              })
            }
          >
            dry run
          </button>
          {preview && (
            <>
              <span className="text-slate-300">{preview}</span>
              <button
                disabled={busy}
                className={DANGER_BUTTON}
                onClick={() =>
                  onAct(async () => {
                    const r = await republishPushMessage(adminPath, auth, m.topic, m.id, { target, reason, dryRun: false })
                    setPreview(null)
                    return `republished as ${r.id} on ${r.target}`
                  })
                }
              >
                confirm republish
              </button>
            </>
          )}
          <div className="flex-1" />
          {!confirmTombstone ? (
            <button disabled={busy} className={DANGER_BUTTON} onClick={() => setConfirmTombstone(true)}>
              tombstone
            </button>
          ) : (
            <>
              <span className="text-red-400">redact the payload and drop it from the history?</span>
              <button
                disabled={busy}
                className={DANGER_BUTTON}
                onClick={() =>
                  onAct(async () => {
                    await tombstonePushMessage(adminPath, auth, m.topic, m.id)
                    setConfirmTombstone(false)
                    onClose()
                    return `tombstoned ${m.topic} ${m.id}`
                  })
                }
              >
                confirm tombstone
              </button>
              <button className={SMALL_BUTTON} onClick={() => setConfirmTombstone(false)}>
                keep
              </button>
            </>
          )}
        </div>
      )}
    </div>
  )
}

/** The delivered and dropped counts of one event, and the per-connection log when its namespace keeps one. */
function Deliveries({ adminPath, auth, topic, id }: { adminPath: string; auth: PushAuth; topic: string; id: string }) {
  const [state, setState] = useState<PushDeliveries | null | undefined>(undefined)
  useEffect(() => {
    let live = true
    setState(undefined)
    fetchPushDeliveries(adminPath, auth, topic, id)
      .then((d) => live && setState(d))
      .catch(() => live && setState(null))
    return () => {
      live = false
    }
  }, [adminPath, auth, topic, id])
  if (state === undefined) return <div className="text-slate-600 animate-pulse">deliveries…</div>
  if (state === null) return <div className="text-slate-600">deliveries unavailable</div>
  const scope = state.logged
    ? `${state.scope}, logged`
    : 'counts of this instance only; set delivery-log on the namespace for a per-subscriber log'
  return (
    <div className="space-y-1">
      <div className="text-slate-400">
        deliveries: <span className="text-slate-200">{state.delivered}</span> delivered ·{' '}
        <span className={state.dropped > 0 ? 'text-amber-400' : 'text-slate-200'}>{state.dropped}</span> dropped{' '}
        <span className="text-slate-600">({scope})</span>
      </div>
      {state.log.length > 0 && (
        <table className="w-full text-[11px]">
          <thead>
            <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
              <th className="py-0.5">time</th>
              <th>connection</th>
              <th>principal</th>
              <th>transport</th>
              <th>outcome</th>
            </tr>
          </thead>
          <tbody>
            {state.log.map((d, i) => (
              <tr key={`${d.connectionId}-${i}`} className="border-t border-slate-800 text-slate-300">
                <td className="py-0.5 text-slate-500 whitespace-nowrap">{new Date(d.time).toLocaleTimeString()}</td>
                <td className="font-mono text-[10px]">{d.connectionId}</td>
                <td>{d.principal ?? <span className="text-slate-600">anonymous</span>}</td>
                <td>{d.transport}</td>
                <td className={d.outcome === 'delivered' ? '' : 'text-amber-400'}>{d.outcome}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

/** Hands a text to the browser as a download; a no-op where object URLs are missing or mismatched (tests). */
function saveText(text: string, name: string) {
  try {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/x-ndjson' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    // no download outside a browser
  }
}

/** One bulk republish job, polled while it runs. */
function JobPanel({
  adminPath,
  auth,
  job,
  onChange,
  onError,
}: {
  adminPath: string
  auth: PushAuth
  job: PushJob
  onChange: (job: PushJob | null) => void
  onError: (e: string | null) => void
}) {
  useEffect(() => {
    if (job.state !== 'RUNNING') return
    let alive = true
    const timer = setInterval(() => {
      fetchPushJob(adminPath, auth, job.id)
        .then((j) => alive && onChange(j))
        .catch((e: unknown) => alive && onError(e instanceof Error ? e.message : String(e)))
    }, 1000)
    return () => {
      alive = false
      clearInterval(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adminPath, auth.token, auth.providerId, job.id, job.state])
  const pct = job.matched === 0 ? 100 : Math.round(((job.published + job.failed) / job.matched) * 100)
  return (
    <div className="border border-slate-700 rounded px-2 py-1 text-xs flex items-center gap-3">
      <span className="text-slate-400">job</span>
      <span className="font-mono text-[10px] text-slate-400">{job.id.slice(0, 8)}</span>
      <span className={job.state === 'RUNNING' ? 'text-blue-300' : job.state === 'DONE' ? 'text-emerald-400' : 'text-yellow-400'}>{job.state}</span>
      <span className="text-slate-300">
        {job.published} / {job.matched} published{job.failed > 0 ? `, ${job.failed} failed` : ''}
        {job.target ? ` → ${job.target}` : ''}
      </span>
      <div className="flex-1 h-1.5 bg-slate-800 rounded overflow-hidden">
        <div className="h-full bg-blue-600" style={{ width: `${pct}%` }} />
      </div>
      {job.error && <span className="text-red-400 truncate max-w-xs">{job.error}</span>}
      {job.state === 'RUNNING' && (
        <button
          className={DANGER_BUTTON}
          onClick={() =>
            cancelPushJob(adminPath, auth, job.id)
              .then(() => fetchPushJob(adminPath, auth, job.id))
              .then(onChange)
              .catch((e: unknown) => onError(e instanceof Error ? e.message : String(e)))
          }
        >
          cancel
        </button>
      )}
      <button className={SMALL_BUTTON} onClick={() => onChange(null)}>
        dismiss
      </button>
    </div>
  )
}

function Publish({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const [topic, setTopic] = useState('')
  const [data, setData] = useState('{\n  "hello": "world"\n}')
  const [result, setResult] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const send = async () => {
    setBusy(true)
    setResult(null)
    try {
      JSON.parse(data)
      const r = await publishPushEvent(adminPath, auth, topic.trim(), data)
      setResult(`published ${r.topic} as ${r.id}`)
      onError(null)
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-2 max-w-2xl">
      <div className="text-xs text-slate-500">
        Publishes as the operator, to any declared namespace; the event carries <span className="font-mono">from=admin</span>.
      </div>
      <input value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="namespace:topic" aria-label="Topic" className={`w-full ${INPUT} font-mono`} />
      <textarea value={data} onChange={(e) => setData(e.target.value)} rows={8} aria-label="Payload JSON" className={`w-full ${INPUT} font-mono`} />
      <div className="flex items-center gap-3">
        <button onClick={send} disabled={busy || !topic.trim()} className="text-xs px-3 py-1 rounded bg-blue-700 text-white hover:bg-blue-600 disabled:opacity-50">
          Publish
        </button>
        {result && <span className="text-xs text-emerald-400">{result}</span>}
      </div>
    </div>
  )
}

/** The operator's writes on this instance, newest first. */
/** Persistent sessions: each session's cursor per topic, filtered by a name prefix, with a drop. */
function Sessions({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const [prefix, setPrefix] = useState('')
  const [applied, setApplied] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const load = useCallback(
    () => Promise.all([fetchPushSessions(adminPath, auth, applied), fetchPushGroups(adminPath, auth, applied)]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId, applied],
  )
  const data = usePolling<[PushSessionCursor[], PushGroupStatus[]]>(load, onError)
  const sessions = data === null ? null : data[0]
  const groups = data === null ? null : data[1]
  return (
    <div className="space-y-2">
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          setApplied(prefix.trim())
        }}
      >
        <input value={prefix} onChange={(e) => setPrefix(e.target.value)} placeholder="session or group prefix (alice, dev-7@, workers)" aria-label="Session prefix" className={`${INPUT} font-mono w-72`} />
        <button type="submit" className={SMALL_BUTTON}>
          filter
        </button>
        {notice && <span className="text-xs text-emerald-400">{notice}</span>}
      </form>
      {groups !== null && groups.length > 0 && (
        <div>
          <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">consumer groups</div>
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
                <th className="py-1">group</th>
                <th>topic</th>
                <th className="text-right">pending</th>
                <th className="text-right">members</th>
                <th>position</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={`${g.group}/${g.topic}`} className="border-t border-slate-800 text-slate-300">
                  <td className="py-1 font-mono">{g.group}</td>
                  <td className="font-mono">{g.topic}</td>
                  <td className={`text-right ${g.pending > 0 ? 'text-amber-400' : ''}`}>{g.pending}</td>
                  <td className="text-right">{g.members}</td>
                  <td className="font-mono text-[10px] text-slate-400">{g.cursor ?? '—'}</td>
                  <td className="text-right whitespace-nowrap">
                    <SeekControl
                      disabled={busy}
                      run={async (to) => {
                        setBusy(true)
                        try {
                          await seekPushGroup(adminPath, auth, g.group, g.topic, to)
                          setNotice(`seeked group ${g.group} on ${g.topic} to ${to}`)
                          onError(null)
                        } catch (e: unknown) {
                          onError(e instanceof Error ? e.message : String(e))
                        } finally {
                          setBusy(false)
                        }
                      }}
                    />{' '}
                    <button
                      disabled={busy}
                      className={DANGER_BUTTON}
                      title="forget this group on every topic; its next member starts at the head"
                      onClick={async () => {
                        setBusy(true)
                        try {
                          await dropPushGroup(adminPath, auth, g.group)
                          setNotice(`dropped group ${g.group}`)
                          onError(null)
                        } catch (e: unknown) {
                          onError(e instanceof Error ? e.message : String(e))
                        } finally {
                          setBusy(false)
                        }
                      }}
                    >
                      drop
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {sessions === null ? (
        <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
      ) : sessions.length === 0 ? (
        <div className="text-xs text-slate-500 max-w-2xl">
          No session: a namespace with <span className="font-mono">session-expiry</span> above zero keeps each subscriber's cursor per topic
          (<span className="font-mono">session-key</span> <span className="font-mono">PRINCIPAL</span> or <span className="font-mono">CLIENT</span>).
        </div>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
              <th className="py-1">session</th>
              <th>topic</th>
              <th>cursor</th>
              <th>expires</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {sessions.map((s) => (
              <tr key={`${s.session}/${s.topic}`} className="border-t border-slate-800 text-slate-300">
                <td className="py-1 font-mono">{s.session}</td>
                <td className="font-mono">{s.topic}</td>
                <td className="font-mono text-[10px] text-slate-400">{s.cursor}</td>
                <td className="text-slate-500 whitespace-nowrap">{s.expiresAt ? new Date(s.expiresAt).toLocaleString() : '—'}</td>
                <td className="text-right whitespace-nowrap">
                  <SeekControl
                    disabled={busy}
                    run={async (to) => {
                      setBusy(true)
                      try {
                        await seekPushSession(adminPath, auth, s.session, s.topic, to)
                        setNotice(`seeked ${s.session} on ${s.topic} to ${to}`)
                        onError(null)
                      } catch (e: unknown) {
                        onError(e instanceof Error ? e.message : String(e))
                      } finally {
                        setBusy(false)
                      }
                    }}
                  />{' '}
                  <button
                    disabled={busy}
                    className={DANGER_BUTTON}
                    title="forget this session on every topic; it starts at the head next time"
                    onClick={async () => {
                      setBusy(true)
                      try {
                        await dropPushSession(adminPath, auth, s.session)
                        setNotice(`dropped ${s.session}`)
                        onError(null)
                      } catch (e: unknown) {
                        onError(e instanceof Error ? e.message : String(e))
                      } finally {
                        setBusy(false)
                      }
                    }}
                  >
                    drop
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

/** "send…" on a connection row: a JSON payload for that connection alone, optionally waiting for its acknowledgement. */
function SendControl({ disabled, run }: { disabled: boolean; run: (data: string, requireAck: boolean) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState('')
  const [ack, setAck] = useState(false)
  if (!open) {
    return (
      <button disabled={disabled} className={SMALL_BUTTON} title="send an event to this connection only" onClick={() => setOpen(true)}>
        send…
      </button>
    )
  }
  const go = async () => {
    await run(data.trim(), ack)
    setOpen(false)
    setData('')
  }
  return (
    <span className="inline-flex items-center gap-1">
      <input value={data} onChange={(e) => setData(e.target.value)} placeholder='{"hello":"you"}' aria-label="Send payload" className={`${INPUT} font-mono w-40`} />
      <label className="text-[10px] text-slate-500 inline-flex items-center gap-0.5">
        <input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} aria-label="Wait for acknowledgement" />
        ack
      </label>
      <button disabled={disabled || !data.trim()} className={SMALL_BUTTON} onClick={go}>
        send
      </button>
      <button disabled={disabled} className={SMALL_BUTTON} onClick={() => setOpen(false)}>
        ×
      </button>
    </span>
  )
}

/** "seek…" on a group or session row: an id, or the first / head quick buttons; `run` does the POST. */
function SeekControl({ disabled, run }: { disabled: boolean; run: (to: string) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [to, setTo] = useState('')
  if (!open) {
    return (
      <button disabled={disabled} className={SMALL_BUTTON} title="move the position to an id (or first / head)" onClick={() => setOpen(true)}>
        seek…
      </button>
    )
  }
  const go = async (target: string) => {
    if (!target.trim()) return
    await run(target.trim())
    setOpen(false)
    setTo('')
  }
  return (
    <span className="inline-flex items-center gap-1">
      <input value={to} onChange={(e) => setTo(e.target.value)} placeholder="id" aria-label="Seek to id" className={`${INPUT} font-mono w-28`} />
      <button disabled={disabled || !to.trim()} className={SMALL_BUTTON} onClick={() => go(to)}>
        go
      </button>
      <button disabled={disabled} className={SMALL_BUTTON} onClick={() => go('first')}>
        first
      </button>
      <button disabled={disabled} className={SMALL_BUTTON} onClick={() => go('head')}>
        head
      </button>
      <button disabled={disabled} className={SMALL_BUTTON} onClick={() => setOpen(false)}>
        ×
      </button>
    </span>
  )
}

/** The sources (Kafka topics, backend streams, beans) and the dead-letter queue with retry and delete. */
function Sources({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const load = useCallback(
    () => Promise.all([fetchPushSources(adminPath, auth), fetchPushDlq(adminPath, auth)]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId],
  )
  const data = usePolling<[PushSourceInfo[], PushDlqEntry[]]>(load, onError)
  const [target, setTarget] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (data === null) return <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
  const [sources, dlq] = data
  const act = async (work: () => Promise<string>) => {
    setBusy(true)
    try {
      setNotice(await work())
      onError(null)
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-4">
      <div>
        <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">sources</div>
        {sources.length === 0 ? (
          <div className="text-xs text-slate-500">
            No source: declare one under <span className="font-mono">shiguchi.cursus.sources</span> (<span className="font-mono">KAFKA</span>,{' '}
            <span className="font-mono">GRPC_STREAM</span>) or a <span className="font-mono">PushSource</span> bean.
          </div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
                <th className="py-1">name</th>
                <th>kind</th>
                <th>state</th>
                <th className="text-right">published</th>
                <th className="text-right">failed</th>
                <th>last event</th>
                <th>last error</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.name} className="border-t border-slate-800 text-slate-300">
                  <td className="py-1 font-mono">{s.name}</td>
                  <td className="font-mono text-[10px] text-slate-400 break-all">{s.kind}</td>
                  <td className={s.state === 'owner' || s.state === 'running' ? 'text-emerald-400' : 'text-slate-500'}>
                    {s.state}
                    {!s.shared && <span className="text-slate-600"> (every instance)</span>}
                  </td>
                  <td className="text-right">{s.published}</td>
                  <td className={`text-right ${s.failed > 0 ? 'text-amber-400' : ''}`}>{s.failed}</td>
                  <td className="text-slate-500 whitespace-nowrap">{s.lastEventAt ? new Date(s.lastEventAt).toLocaleTimeString() : '—'}</td>
                  <td className="text-amber-400 text-[10px] break-all">{s.lastError ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div>
        <div className="flex items-center gap-2 mb-1">
          <span className="text-[10px] uppercase tracking-wider text-slate-500">dead-letter queue</span>
          <span className="text-xs text-slate-500">{dlq.length} entr{dlq.length === 1 ? 'y' : 'ies'} on this instance</span>
          <input value={target} onChange={(e) => setTarget(e.target.value)} placeholder="retry to topic (optional)" aria-label="Retry target topic" className={`${INPUT} font-mono w-48`} />
          {notice && <span className="text-xs text-emerald-400">{notice}</span>}
        </div>
        {dlq.length === 0 ? (
          <div className="text-xs text-slate-500">Nothing dead-lettered.</div>
        ) : (
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
                <th className="py-1">time</th>
                <th>source</th>
                <th>reason</th>
                <th>topic</th>
                <th className="text-right">bytes</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {dlq.map((e) => (
                <tr key={e.id} className="border-t border-slate-800 text-slate-300">
                  <td className="py-1 text-slate-500 whitespace-nowrap">{new Date(e.time).toLocaleTimeString()}</td>
                  <td className="font-mono">{e.source}</td>
                  <td className="text-amber-400 text-[10px] break-all">{e.reason}</td>
                  <td className="font-mono">{e.topic ?? <span className="text-slate-600">none</span>}</td>
                  <td className="text-right">{e.bytes}</td>
                  <td className="text-right whitespace-nowrap">
                    <button
                      disabled={busy || (!e.topic && !target)}
                      className={SMALL_BUTTON}
                      title={e.topic ? `publish again to ${e.topic}` : 'mapping failed: give a topic to retry to'}
                      onClick={() =>
                        act(async () => {
                          const r = await retryPushDlq(adminPath, auth, e.id, target)
                          return `retried ${e.id.slice(0, 8)} as ${r.eventId} on ${r.topic}`
                        })
                      }
                    >
                      retry
                    </button>{' '}
                    <button
                      disabled={busy}
                      className={DANGER_BUTTON}
                      onClick={() =>
                        act(async () => {
                          await deletePushDlq(adminPath, auth, e.id)
                          return `deleted ${e.id.slice(0, 8)}`
                        })
                      }
                    >
                      delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

function Audit({ adminPath, auth, onError }: { adminPath: string; auth: PushAuth; onError: (e: string | null) => void }) {
  const load = useCallback(
    () => fetchPushAudit(adminPath, auth),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [adminPath, auth.token, auth.providerId],
  )
  const entries = usePolling<PushAuditEntry[]>(load, onError)
  if (entries === null) return <div className="text-xs text-slate-500 animate-pulse">Loading…</div>
  if (entries.length === 0) return <div className="text-xs text-slate-500">No admin write on this instance yet.</div>
  return (
    <table className="w-full text-xs">
      <thead>
        <tr className="text-left text-[10px] uppercase tracking-wider text-slate-500">
          <th className="py-1">time</th>
          <th>principal</th>
          <th>action</th>
          <th>arguments</th>
          <th>result</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((e, i) => (
          <tr key={`${e.time}-${i}`} className="border-t border-slate-800 text-slate-300">
            <td className="py-1 text-slate-500 whitespace-nowrap">{new Date(e.time).toLocaleString()}</td>
            <td>{e.principal}</td>
            <td className="font-mono">{e.action}</td>
            <td className="font-mono text-[10px] text-slate-400 break-all">
              {Object.entries(e.arguments)
                .filter(([, v]) => v !== null && v !== undefined && v !== '')
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' ')}
            </td>
            <td className={e.result.startsWith('ok') || e.result.startsWith('job') ? 'text-emerald-400' : 'text-yellow-400'}>{e.result}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
  return `${Math.floor(seconds / 86400)}d ${Math.floor((seconds % 86400) / 3600)}h`
}
