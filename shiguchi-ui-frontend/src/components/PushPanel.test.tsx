import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PushPanel } from './PushPanel'
import { useGrpcUiStore } from '../store/useGrpcUiStore'

const overview = {
  instance: 'gw-1',
  uptimeSeconds: 125,
  connections: { total: 4, sse: 2, ws: 1, grpc: 1 },
  subscriptions: 4,
  activeTopics: 2,
  namespaces: 1,
  engine: {
    broker: 'MEMORY',
    store: 'MEMORY',
    history: true,
    archive: { enabled: true, retention: 'PT1H', attributes: true, fullText: true, payloadVisibility: 'FULL' },
  },
  totals: { published: 10, delivered: 30, dropped: 0 },
  perSecond: { published: 1.5, delivered: 4.5, dropped: 0 },
  ws: { enabled: true, path: '/push/ws' },
  path: '/push',
}

const namespaces = {
  namespaces: [
    {
      name: 'orders',
      qos: 'AT_LEAST_ONCE',
      historySize: 100,
      historyTtl: 'PT1H',
      clientQueueSize: 256,
      auth: 'NONE',
      publishAuth: 'AUTHENTICATED',
      clientPublish: false,
      activeTopics: 2,
      subscribers: 4,
    },
  ],
}

const connections = {
  connections: [
    { id: 'abcdef12-3456', transport: 'sse', principal: 'alice', since: new Date().toISOString(), topics: ['orders:1'], instance: 'gw-b', deviceId: 'phone-1' },
  ],
}

const messages = {
  messages: [
    {
      topic: 'orders:1',
      id: '41',
      time: '2026-10-09T10:00:00Z',
      bytes: 9,
      typeUrl: null,
      tombstoned: false,
      key: null,
      attributes: { region: 'eu' },
      data: '{"n":41}',
    },
  ],
}

/** A WebSocket that records what the client sends and lets a test play the gateway's side. */
class FakeSocket {
  static instances: FakeSocket[] = []
  sent: Array<Record<string, unknown>> = []
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: ((ev: { code: number; reason: string }) => void) | null = null
  onerror: (() => void) | null = null
  constructor(
    readonly url: string,
    readonly protocols?: string | string[],
  ) {
    FakeSocket.instances.push(this)
  }
  send(data: string) {
    this.sent.push(JSON.parse(data))
  }
  close() {}
  open() {
    this.onopen?.()
  }
  receive(frame: unknown) {
    this.onmessage?.({ data: JSON.stringify(frame) })
  }
  drop(code: number) {
    this.onclose?.({ code, reason: '' })
  }
}

function okJson(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('PushPanel', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    fetchMock.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/namespaces')) return Promise.resolve(okJson(namespaces))
      if (url.includes('/topics')) return Promise.resolve(okJson({ topics: [{ topic: 'orders:1', subscribers: 1, history: 3 }] }))
      if (url.endsWith('/connections')) return Promise.resolve(okJson(connections))
      if (url.endsWith('/send')) {
        return Promise.resolve(okJson({ id: '0123456789', deliveries: [{ connectionId: 'abcdef12-3456', instance: 'gw-b', status: 'DELIVERED' }] }))
      }
      if (url.includes('/connections/')) return Promise.resolve(okJson({ disconnected: true }))
      if (url.includes('/groups/')) return Promise.resolve(okJson({ group: 'workers', dropped: 1 }))
      if (url.includes('/groups?')) return Promise.resolve(okJson({ groups: [{ group: 'workers', topic: 'jobs:q', pending: 3, members: 2, cursor: '9' }] }))
      if (url.includes('/sessions/')) return Promise.resolve(okJson({ session: 'alice', dropped: 1 }))
      if (url.includes('/sessions?')) {
        return Promise.resolve(okJson({ sessions: [{ session: 'alice', topic: 'inbox:alice', cursor: '1759953600000000042', expiresAt: '2026-10-17T00:00:00Z' }] }))
      }
      if (url.endsWith('/sources')) {
        return Promise.resolve(okJson({ sources: [{ name: 'order-events', kind: 'KAFKA order-events -> orders:{key}', shared: true, state: 'owner', published: 12, failed: 1, lastEventAt: '2026-10-10T00:00:00Z', lastError: null }] }))
      }
      if (url.includes('/dlq/') && url.endsWith('/retry')) return Promise.resolve(okJson({ id: 'd1', topic: 'orders:9', eventId: '77' }))
      if (url.includes('/dlq?')) {
        return Promise.resolve(okJson({ dlq: [{ id: 'd1', time: '2026-10-10T00:00:00Z', source: 'order-events', reason: 'topic: {key} has no value for orders:{key}', topic: null, bytes: 9, typeUrl: null }] }))
      }
      if (url.includes('/deliveries?')) {
        return Promise.resolve(
          okJson({
            topic: 'orders:1',
            id: '41',
            logged: true,
            scope: 'cluster',
            delivered: 3,
            dropped: 1,
            log: [{ connectionId: 'c-1', principal: 'alice', transport: 'sse', time: '2026-10-09T00:00:01Z', outcome: 'dropped:queue_full' }],
          }),
        )
      }
      if (url.includes('/export')) {
        return Promise.resolve(new Response('{"id":"41"}\n', { status: 200, headers: { 'Content-Type': 'application/x-ndjson' } }))
      }
      if (url.includes('/messages/') && url.endsWith('/republish')) {
        return Promise.resolve(okJson({ dryRun: true, origin: { topic: 'orders:1', id: '41' }, target: 'orders:1' }))
      }
      if (url.includes('/messages')) return Promise.resolve(okJson(messages))
      return Promise.resolve(okJson(overview))
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    FakeSocket.instances = []
  })

  async function openTail(user: ReturnType<typeof userEvent.setup>) {
    vi.stubGlobal('WebSocket', FakeSocket)
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'topics' }))
    await user.click(await screen.findByRole('button', { name: 'tail' }))
    await waitFor(() => expect(FakeSocket.instances).toHaveLength(1))
    return FakeSocket.instances[0]
  }

  it('tails a topic through the push client: subscribes, lists events, and shows a gap as its own entry', async () => {
    const user = userEvent.setup()
    const socket = await openTail(user)
    expect(socket.url).toBe(`ws://${window.location.host}/push/ws`)
    expect(socket.protocols).toBe('push.v1.json')
    act(() => socket.open())
    await waitFor(() => expect(socket.sent).toContainEqual({ type: 'sub', topics: ['orders:1'] }))
    expect(await screen.findByText('open')).toBeInTheDocument()
    act(() => {
      socket.receive({ type: 'event', topic: 'orders:1', id: '7', data: { n: 7 }, time: 1 })
      socket.receive({ type: 'overflow', topic: 'orders:1', reason: 'session_overflow', dropped: 12 })
    })
    expect(await screen.findByText(/"id":"7"/)).toBeInTheDocument()
    expect(screen.getByText('gap on orders:1: session_overflow (12 dropped)')).toBeInTheDocument()
    expect(screen.getByText('1 events')).toBeInTheDocument()
  })

  it('resumes the tail from the last delivered id when the gateway asks the client to reconnect', async () => {
    const user = userEvent.setup()
    const first = await openTail(user)
    act(() => first.open())
    await waitFor(() => expect(first.sent.length).toBeGreaterThan(0))
    act(() => first.receive({ type: 'event', topic: 'orders:1', id: '7', data: {}, time: 1 }))
    // 1012 (service restart / rebalance) reconnects at once, without a backoff timer.
    act(() => first.drop(1012))
    await waitFor(() => expect(FakeSocket.instances).toHaveLength(2))
    const second = FakeSocket.instances[1]
    act(() => second.open())
    await waitFor(() => expect(second.sent).toContainEqual({ type: 'sub', topics: ['orders:1'], after: { 'orders:1': '7' } }))
  })

  it('sends the console token in a connect frame and reports a refused connection', async () => {
    const user = userEvent.setup()
    useGrpcUiStore.setState({ globalAuthToken: 'tok-1' })
    const socket = await openTail(user)
    act(() => socket.open())
    await waitFor(() => expect(socket.sent[0]).toEqual({ type: 'connect', token: 'tok-1' }))
    act(() => socket.drop(1008))
    expect(await screen.findByText(/policy:/)).toBeInTheDocument()
    expect(screen.getByText('closed')).toBeInTheDocument()
    useGrpcUiStore.setState({ globalAuthToken: '' })
  })

  it('shows the instance overview and the namespace table on load', async () => {
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    expect(await screen.findByText('gw-1')).toBeInTheDocument()
    expect(screen.getByText('orders')).toBeInTheDocument()
    expect(screen.getByText('AT_LEAST_ONCE')).toBeInTheDocument()
    // The MEMORY engine is per instance; the view says so.
    expect(screen.getByText(/this instance only/)).toBeInTheDocument()
    // One stat per transport, the gRPC stream among them.
    expect(screen.getByText('grpc')).toBeInTheDocument()
  })

  it('lists connections and sends DELETE for the one the operator disconnects', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'connections' }))
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(screen.getByText('gw-b')).toBeInTheDocument()
    expect(screen.getByText('phone-1')).toBeInTheDocument()
    expect(screen.getByText(/cluster-wide registry: 1 connection on 1 instance/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'disconnect' }))
    await waitFor(() => {
      const deleted = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'DELETE')
      expect(deleted).toBeDefined()
      expect(String(deleted![0])).toBe('/actuator/gateway-push/connections/abcdef12-3456')
    })
  })

  it('sends a JSON payload to one connection and shows how each delivery fared', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'connections' }))
    await screen.findByText('alice')
    await user.click(screen.getByRole('button', { name: 'send…' }))
    await user.type(screen.getByLabelText('Send payload'), '{{"hello":"you"}')
    await user.click(screen.getByLabelText('Wait for acknowledgement'))
    await user.click(screen.getByRole('button', { name: 'send' }))
    await waitFor(() => {
      const posted = fetchMock.mock.calls.find(
        ([url, init]) => String(url).endsWith('/connections/abcdef12-3456/send') && (init as RequestInit | undefined)?.method === 'POST',
      )
      expect(posted).toBeDefined()
      expect(JSON.parse(String((posted![1] as RequestInit).body))).toMatchObject({ data: '{"hello":"you"}', requireAck: true })
    })
    expect(await screen.findByText(/DELIVERED/)).toBeInTheDocument()
  })

  it('searches the archive from the messages view, shows the hit, and posts a dry-run republish from its detail', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'messages' }))
    await user.type(await screen.findByLabelText('topic or pattern (orders:*)'), 'orders:*')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('region=eu')).toBeInTheDocument()
    const search = fetchMock.mock.calls.find(([u]) => String(u).includes('/messages?'))
    expect(String(search![0])).toBe('/actuator/gateway-push/messages?topic=orders%3A*&limit=100')
    await user.click(screen.getByText('region=eu'))
    await user.click(screen.getByRole('button', { name: 'dry run' }))
    expect(await screen.findByText(/would republish orders:1 41 to orders:1/)).toBeInTheDocument()
    const posted = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'POST')
    expect(String(posted![0])).toBe('/actuator/gateway-push/messages/41/republish')
    expect(JSON.parse((posted![1] as RequestInit).body as string)).toEqual({ topic: 'orders:1', dryRun: true })
  })

  it('shows the deliveries of the selected event and exports the query as NDJSON from the push path', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'messages' }))
    await user.type(await screen.findByLabelText('topic or pattern (orders:*)'), 'orders:*')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByText('region=eu'))
    expect(await screen.findByText('dropped:queue_full')).toBeInTheDocument()
    expect(screen.getByText('c-1')).toBeInTheDocument()
    const deliveries = fetchMock.mock.calls.find(([u]) => String(u).includes('/deliveries?'))
    expect(String(deliveries![0])).toBe('/actuator/gateway-push/messages/41/deliveries?topic=orders%3A1&limit=200')
    await user.click(screen.getByRole('button', { name: 'export NDJSON' }))
    await waitFor(() => {
      const exported = fetchMock.mock.calls.find(([u]) => String(u).includes('/export'))
      expect(String(exported![0])).toBe('/push/export?topic=orders%3A*')
    })
    expect(await screen.findByText('exported 1 event(s)')).toBeInTheDocument()
  })

  it('lists the sources and the dead-letter queue, and retries an entry to a chosen topic', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'sources' }))
    expect(await screen.findAllByText('order-events')).toHaveLength(2)
    expect(screen.getByText('owner')).toBeInTheDocument()
    expect(screen.getByText(/has no value/)).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: 'retry' })
    expect(retry).toBeDisabled()
    await user.type(screen.getByLabelText('Retry target topic'), 'orders:9')
    await user.click(screen.getByRole('button', { name: 'retry' }))
    await waitFor(() => {
      const posted = fetchMock.mock.calls.find(([u, init]) => String(u).endsWith('/dlq/d1/retry') && (init as RequestInit | undefined)?.method === 'POST')
      expect(posted).toBeDefined()
      expect(JSON.parse((posted![1] as RequestInit).body as string)).toEqual({ topic: 'orders:9' })
    })
    expect(await screen.findByText(/retried d1 as 77 on orders:9/)).toBeInTheDocument()
  })

  it('lists the persistent sessions and the consumer groups, and drops a session', async () => {
    const user = userEvent.setup()
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'sessions' }))
    expect(await screen.findByText('inbox:alice')).toBeInTheDocument()
    expect(screen.getByText('workers')).toBeInTheDocument()
    expect(screen.getByText('jobs:q')).toBeInTheDocument()
    const drops = screen.getAllByRole('button', { name: 'drop' })
    expect(drops).toHaveLength(2)
    await user.click(drops[1])
    await waitFor(() => {
      const deleted = fetchMock.mock.calls.find(([u, init]) => String(u).endsWith('/sessions/alice') && (init as RequestInit | undefined)?.method === 'DELETE')
      expect(deleted).toBeDefined()
    })
    expect(await screen.findByText('dropped alice')).toBeInTheDocument()
  })

  it('says how to turn the archive on when the overview reports it off', async () => {
    const user = userEvent.setup()
    fetchMock.mockImplementation((input) => {
      if (String(input).endsWith('/namespaces')) return Promise.resolve(okJson(namespaces))
      return Promise.resolve(okJson({ ...overview, engine: { ...overview.engine, archive: { ...overview.engine.archive, enabled: false } } }))
    })
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    await user.click(screen.getByRole('button', { name: 'messages' }))
    expect(await screen.findByText(/shiguchi.cursus.archive.retention/)).toBeInTheDocument()
  })

  it('shows the error line when the endpoint refuses', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response('', { status: 401, statusText: 'Unauthorized' })))
    render(<PushPanel adminPath="/actuator/gateway-push" pushPath="/push" />)
    expect(await screen.findByText(/401/)).toBeInTheDocument()
  })
})
