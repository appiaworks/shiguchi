import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { InvokePanel } from './InvokePanel'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import type { MethodSchema, ServiceSchema } from '../types/schema'

// The Monaco editor and the resizable-panel layout are irrelevant to the stream state logic.
vi.mock('./LazyEditor', () => ({ LazyEditor: () => <div data-testid="editor" /> }))
vi.mock('react-resizable-panels', () => ({
  Group: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Panel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Separator: () => <hr />,
}))

const method: MethodSchema = {
  name: 'Watch',
  fullServiceName: 'demo.Orders',
  methodType: 'SERVER_STREAMING',
  inputSchema: { name: 'Req', fields: [] } as unknown as MethodSchema['inputSchema'],
  outputTypeName: 'demo.Order',
}
const service: ServiceSchema = {
  name: 'demo.Orders',
  target: 'localhost:9090',
  methods: [method],
  namespace: 'demo',
  configName: 'default',
}

function sse(body: string, status = 200): Response {
  const bytes = new TextEncoder().encode(body)
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(bytes)
      c.close()
    },
  })
  return new Response(stream, { status, headers: { 'Content-Type': 'text/event-stream' } })
}

async function pressStream() {
  await userEvent.click(screen.getByRole('button', { name: 'Stream' }))
}

describe('InvokePanel server-streaming result state', () => {
  beforeEach(() => {
    useGrpcUiStore.setState({
      selectedService: service,
      selectedMethod: method,
      links: null,
      globalAuthToken: '',
      authToken: '',
      activeProviderId: null,
      globalProviderId: null,
      history: [],
      metadataEntries: [],
    })
    useGrpcUiStore.getState().setResult({
      status: 'idle', response: null, grpcStatus: null, errorMessage: null,
      errorDetails: null, durationMs: null, streamChunks: [],
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('GIVEN_a_valid_bearer_token_WHEN_the_stream_sends_N_data_events_then_done_SHOULD_show_N_messages_as_success', async () => {
    useGrpcUiStore.setState({ globalAuthToken: 'valid-token' })
    const fetchMock = vi.fn().mockResolvedValue(
      sse('event:data\ndata:{"id":"1"}\n\nevent:data\ndata:{"id":"2"}\n\nevent:data\ndata:{"id":"3"}\n\nevent:done\ndata:{"durationMs":12}\n\n'),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<InvokePanel />)

    await pressStream()

    await waitFor(() => expect(useGrpcUiStore.getState().result.status).toBe('success'))
    const r = useGrpcUiStore.getState().result
    expect(r.streamChunks).toEqual(['{"id":"1"}', '{"id":"2"}', '{"id":"3"}'])
    expect(r.grpcStatus).toBe('OK')
    expect(r.durationMs).toBe(12)
    expect(await screen.findByText('3 chunks')).toBeInTheDocument()
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer valid-token')
  })

  it('GIVEN_no_token_WHEN_the_stream_sends_an_UNAUTHENTICATED_error_then_done_SHOULD_show_the_error_and_not_an_empty_success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        sse('event:error\ndata:{"code":"UNAUTHENTICATED","message":"Authentication failed"}\n\nevent:done\ndata:{"durationMs":3}\n\n'),
      ),
    )
    render(<InvokePanel />)

    await pressStream()

    await waitFor(() => expect(useGrpcUiStore.getState().result.status).toBe('error'))
    const r = useGrpcUiStore.getState().result
    expect(r.grpcStatus).toBe('UNAUTHENTICATED')
    expect(r.errorMessage).toBe('Authentication failed')
    expect(r.streamChunks).toEqual([])
    expect(r.durationMs).toBe(3)
    expect(await screen.findByText('UNAUTHENTICATED')).toBeInTheDocument()
    expect(screen.getByText('Authentication failed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set or refresh credentials' })).toBeInTheDocument()
  })

  it('GIVEN_a_gateway_that_answers_the_stream_with_plain_HTTP_401_WHEN_streaming_SHOULD_show_UNAUTHENTICATED_instead_of_hanging_on_Streaming', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    render(<InvokePanel />)

    await pressStream()

    await waitFor(() => expect(useGrpcUiStore.getState().result.status).toBe('error'))
    expect(useGrpcUiStore.getState().result.grpcStatus).toBe('UNAUTHENTICATED')
  })

  it('GIVEN_a_stream_that_closes_without_done_or_error_WHEN_streaming_SHOULD_end_in_an_error_state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sse('event: data\ndata: {"id":"1"}\n\n')))
    render(<InvokePanel />)

    await pressStream()

    await waitFor(() => expect(useGrpcUiStore.getState().result.status).toBe('error'))
    expect(useGrpcUiStore.getState().result.streamChunks).toEqual(['{"id":"1"}'])
  })

  it('GIVEN_events_with_a_space_after_the_colon_and_CRLF_line_ends_WHEN_streaming_SHOULD_parse_them_like_the_compact_form', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sse('event: data\r\ndata: {"id":"1"}\r\n\r\nevent: done\r\ndata: {"durationMs":1}\r\n\r\n')))
    render(<InvokePanel />)

    await pressStream()

    await waitFor(() => expect(useGrpcUiStore.getState().result.status).toBe('success'))
    expect(useGrpcUiStore.getState().result.streamChunks).toEqual(['{"id":"1"}'])
  })
})
