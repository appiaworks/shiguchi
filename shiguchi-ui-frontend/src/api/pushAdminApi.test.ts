import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  deletePushDlq,
  dropPushSession,
  dropPushGroup,
  seekPushGroup,
  seekPushSession,
  fetchPushGroups,
  fetchPushSessions,
  disconnectPushConnection,
  fetchPushDlq,
  fetchPushSources,
  retryPushDlq,
  exportPushMessages,
  fetchPushDeliveries,
  pushExportUrl,
  fetchPushOverview,
  fetchPushTopics,
  messageQueryString,
  publishPushEvent,
  pushWebSocketUrl,
  republishPushMessage,
  republishPushMessages,
  searchPushMessages,
  tombstonePushMessage,
  type PushAuth,
} from './pushAdminApi'

const anonymous: PushAuth = { token: '', providerId: null, providers: [] }
const bearer: PushAuth = { token: 'abc', providerId: null, providers: [] }

function okJson(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('pushAdminApi', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the overview from the admin path without an Authorization header when there is no token', async () => {
    fetchMock.mockResolvedValue(okJson({ instance: 'gw-1', connections: { total: 2, sse: 1, ws: 1 } }))
    const overview = await fetchPushOverview('/actuator/gateway-push', anonymous)
    expect(overview.instance).toBe('gw-1')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push')
    expect((init as RequestInit).headers).toEqual({})
  })

  it('carries the console token as a bearer header and the filter as a query parameter on the topics read', async () => {
    fetchMock.mockResolvedValue(okJson({ topics: [{ topic: 'orders:1', subscribers: 3, history: 0 }] }))
    const topics = await fetchPushTopics('/actuator/gateway-push', bearer, ' orders ')
    expect(topics).toHaveLength(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/topics?q=orders')
    expect((init as RequestInit).headers).toEqual({ Authorization: 'Bearer abc' })
  })

  it('disconnects with DELETE on the connection id and surfaces the gateway’s error message', async () => {
    fetchMock.mockResolvedValue(okJson({ id: 'c1', disconnected: true }))
    await disconnectPushConnection('/actuator/gateway-push', bearer, 'c1')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/connections/c1')
    expect((init as RequestInit).method).toBe('DELETE')

    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ error: 'forbidden', message: 'role PUSH_ADMIN required' }), { status: 403 }),
    )
    await expect(disconnectPushConnection('/actuator/gateway-push', bearer, 'c2')).rejects.toThrow(
      '403: role PUSH_ADMIN required',
    )
  })

  it('publishes with POST, the payload travelling as a JSON string in data', async () => {
    fetchMock.mockResolvedValue(okJson({ topic: 'orders:1', id: '7' }))
    const r = await publishPushEvent('/actuator/gateway-push', bearer, 'orders:1', '{"a":1}')
    expect(r.id).toBe('7')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/publish')
    expect((init as RequestInit).method).toBe('POST')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ topic: 'orders:1', data: '{"a":1}' })
    expect((init as RequestInit).headers).toEqual({ Authorization: 'Bearer abc', 'Content-Type': 'application/json' })
  })

  it('derives the WebSocket URL from the page’s scheme and host', () => {
    expect(pushWebSocketUrl('/push', { protocol: 'https:', host: 'gw.example' })).toBe('wss://gw.example/push/ws')
    expect(pushWebSocketUrl('/push/', { protocol: 'http:', host: 'localhost:8080' })).toBe('ws://localhost:8080/push/ws')
  })

  it('builds the message search query string from the non-empty filters only', () => {
    expect(messageQueryString({ topic: ' orders:* ', from: '', attr: 'region:eu', limit: 50 })).toBe(
      'topic=orders%3A*&attr=region%3Aeu&limit=50',
    )
    expect(messageQueryString({})).toBe('')
  })

  it('searches the archive at /messages with the query string and unwraps the messages list', async () => {
    fetchMock.mockResolvedValue(
      okJson({ messages: [{ topic: 'orders:1', id: '9', time: '2026-10-09T00:00:00Z', bytes: 7, typeUrl: null, tombstoned: false }] }),
    )
    const found = await searchPushMessages('/actuator/gateway-push', bearer, { topic: 'orders:1', q: 'shipped' })
    expect(found).toHaveLength(1)
    const [url] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/messages?topic=orders%3A1&q=shipped')
  })

  it('republishes one event with POST on messages/{id}/republish, the topic, dryRun and the optional target in the body', async () => {
    fetchMock.mockResolvedValue(okJson({ dryRun: true, origin: { topic: 'orders:1', id: '9' }, target: 'orders:2' }))
    const r = await republishPushMessage('/actuator/gateway-push', bearer, 'orders:1', '9', { target: ' orders:2 ', reason: '', dryRun: true })
    expect(r.target).toBe('orders:2')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/messages/9/republish')
    expect((init as RequestInit).method).toBe('POST')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ topic: 'orders:1', dryRun: true, target: 'orders:2' })
  })

  it('republishes a query with POST on messages/republish, the filters flattened into the body without the limit', async () => {
    fetchMock.mockResolvedValue(
      okJson({ dryRun: false, matched: 2, capped: false, maxBatch: 10000, target: null, job: { id: 'j1', state: 'RUNNING' } }),
    )
    const r = await republishPushMessages('/actuator/gateway-push', bearer, { topic: 'orders:*', limit: 100, from: '' }, { reason: 'replay', dryRun: false })
    expect(r.job?.id).toBe('j1')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/messages/republish')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ dryRun: false, topic: 'orders:*', reason: 'replay' })
  })

  it('reads the deliveries of one event on messages/{id}/deliveries?topic=', async () => {
    fetchMock.mockResolvedValue(okJson({ topic: 'orders:1', id: '9', logged: true, scope: 'cluster', delivered: 2, dropped: 0, log: [] }))
    const d = await fetchPushDeliveries('/actuator/gateway-push', bearer, 'orders:1', '9')
    expect(fetchMock.mock.calls[0][0]).toBe('/actuator/gateway-push/messages/9/deliveries?topic=orders%3A1&limit=200')
    expect(d.delivered).toBe(2)
    expect(d.scope).toBe('cluster')
  })

  it('builds the export URL on the push path from the query and downloads it with the bearer token', async () => {
    expect(pushExportUrl('/push', { topic: 'orders:*', attr: 'region:eu' })).toBe('/push/export?topic=orders%3A*&attr=region%3Aeu')
    expect(pushExportUrl('/push', {})).toBe('/push/export')
    fetchMock.mockResolvedValue(new Response('{"id":"1"}\n{"id":"2"}\n', { status: 200, headers: { 'Content-Type': 'application/x-ndjson' } }))
    const text = await exportPushMessages('/push', bearer, { topic: 'orders:*' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/push/export?topic=orders%3A*')
    expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer abc' })
    expect(text).toBe('{"id":"1"}\n{"id":"2"}\n')
  })

  it('reads the sources and the dead-letter queue, retries an entry with POST dlq/{id}/retry and deletes one', async () => {
    fetchMock.mockResolvedValueOnce(okJson({ sources: [{ name: 'orders', kind: 'KAFKA orders -> orders:{key}', shared: true, state: 'owner', published: 3, failed: 1, lastEventAt: null, lastError: null }] }))
    const sources = await fetchPushSources('/actuator/gateway-push', bearer)
    expect(fetchMock.mock.calls[0][0]).toBe('/actuator/gateway-push/sources')
    expect(sources[0].state).toBe('owner')
    fetchMock.mockResolvedValueOnce(okJson({ dlq: [{ id: 'd1', time: '2026-10-10T00:00:00Z', source: 'orders', reason: 'topic: {key} has no value', topic: null, bytes: 4, typeUrl: null }] }))
    const dlq = await fetchPushDlq('/actuator/gateway-push', bearer, 'orders')
    expect(fetchMock.mock.calls[1][0]).toBe('/actuator/gateway-push/dlq?limit=200&q=orders')
    expect(dlq[0].id).toBe('d1')
    fetchMock.mockResolvedValueOnce(okJson({ id: 'd1', topic: 'orders:9', eventId: '77' }))
    const retried = await retryPushDlq('/actuator/gateway-push', bearer, 'd1', 'orders:9')
    const [url, init] = fetchMock.mock.calls[2]
    expect(url).toBe('/actuator/gateway-push/dlq/d1/retry')
    expect((init as RequestInit).method).toBe('POST')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ topic: 'orders:9' })
    expect(retried.eventId).toBe('77')
    fetchMock.mockResolvedValueOnce(okJson({ id: 'd1', deleted: true }))
    await deletePushDlq('/actuator/gateway-push', bearer, 'd1')
    expect(fetchMock.mock.calls[3][0]).toBe('/actuator/gateway-push/dlq/d1')
    expect((fetchMock.mock.calls[3][1] as RequestInit).method).toBe('DELETE')
  })

  it('lists the consumer groups by prefix and drops one with DELETE groups/{group}', async () => {
    fetchMock.mockResolvedValueOnce(okJson({ groups: [{ group: 'workers', topic: 'jobs:q', pending: 3, members: 2, cursor: '9' }] }))
    const groups = await fetchPushGroups('/actuator/gateway-push', bearer, 'work')
    expect(fetchMock.mock.calls[0][0]).toBe('/actuator/gateway-push/groups?limit=200&q=work')
    expect(groups[0].pending).toBe(3)
    fetchMock.mockResolvedValueOnce(okJson({ group: 'workers', dropped: 1 }))
    await dropPushGroup('/actuator/gateway-push', bearer, 'workers')
    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe('/actuator/gateway-push/groups/workers')
    expect((init as RequestInit).method).toBe('DELETE')
  })

  it('lists the sessions by prefix and drops one with DELETE sessions/{session}', async () => {
    fetchMock.mockResolvedValueOnce(okJson({ sessions: [{ session: 'dev-7@alice', topic: 'alerts:dev', cursor: '42', expiresAt: null }] }))
    const sessions = await fetchPushSessions('/actuator/gateway-push', bearer, 'dev-7')
    expect(fetchMock.mock.calls[0][0]).toBe('/actuator/gateway-push/sessions?limit=200&q=dev-7')
    expect(sessions[0].cursor).toBe('42')
    fetchMock.mockResolvedValueOnce(okJson({ session: 'dev-7@alice', dropped: 1 }))
    await dropPushSession('/actuator/gateway-push', bearer, 'dev-7@alice')
    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe('/actuator/gateway-push/sessions/dev-7%40alice')
    expect((init as RequestInit).method).toBe('DELETE')
  })

  it('seeks a group and a session with POST .../seek and a {topic, to} body', async () => {
    fetchMock.mockResolvedValueOnce(okJson({ seeked: true, topic: 'jobs:q', to: 'first' }))
    await seekPushGroup('/actuator/gateway-push', bearer, 'workers', 'jobs:q', 'first')
    const [groupUrl, groupInit] = fetchMock.mock.calls[0]
    expect(groupUrl).toBe('/actuator/gateway-push/groups/workers/seek')
    expect((groupInit as RequestInit).method).toBe('POST')
    expect(JSON.parse((groupInit as RequestInit).body as string)).toEqual({ topic: 'jobs:q', to: 'first' })
    fetchMock.mockResolvedValueOnce(okJson({ seeked: true, topic: 'alerts:dev', to: '41' }))
    await seekPushSession('/actuator/gateway-push', bearer, 'dev-7@alice', 'alerts:dev', '41')
    const [sessionUrl, sessionInit] = fetchMock.mock.calls[1]
    expect(sessionUrl).toBe('/actuator/gateway-push/sessions/dev-7%40alice/seek')
    expect(JSON.parse((sessionInit as RequestInit).body as string)).toEqual({ topic: 'alerts:dev', to: '41' })
  })

  it('tombstones with DELETE on messages/{id}?topic=', async () => {
    fetchMock.mockResolvedValue(okJson({ topic: 'orders:1', id: '9', tombstoned: true }))
    await tombstonePushMessage('/actuator/gateway-push', bearer, 'orders:1', '9')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/actuator/gateway-push/messages/9?topic=orders%3A1')
    expect((init as RequestInit).method).toBe('DELETE')
  })
})
