import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import type { GatewayUiLinks } from '../types/schema'
import { authFetch } from './authFetch'

const links = (authProviders: GatewayUiLinks['authProviders']): GatewayUiLinks => ({
  swaggerEnabled: true, swaggerPath: '/swagger-ui.html', graphqlEnabled: true, graphqlPath: '/graphql', graphqlEndpoint: '/graphql', graphqlWebSocketPath: '/graphql/ws',
  grpcProxyEnabled: false, grpcProxyPort: 0, authProviders,
})

describe('authFetch', () => {
  const spy = vi.fn(() => Promise.resolve(new Response('{}')))
  beforeEach(() => {
    vi.stubGlobal('fetch', spy)
    spy.mockClear()
    useGrpcUiStore.setState({ globalAuthToken: '', globalProviderId: null, links: null })
  })
  afterEach(() => vi.unstubAllGlobals())

  const sentHeaders = () => new Headers((spy.mock.calls[0] as unknown as [string, RequestInit])[1].headers)

  it('GIVEN no token WHEN fetching SHOULD add no credential header', async () => {
    await authFetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json' } })
    expect(sentHeaders().get('authorization')).toBeNull()
    expect(sentHeaders().get('content-type')).toBe('application/json')
  })

  it('GIVEN a bearer token WHEN fetching SHOULD send Authorization', async () => {
    useGrpcUiStore.setState({ globalAuthToken: 'abc' })
    await authFetch('/graphql', { method: 'POST' })
    expect(sentHeaders().get('authorization')).toBe('Bearer abc')
  })

  it('GIVEN an API-key scheme WHEN fetching SHOULD send the key header, not Authorization', async () => {
    useGrpcUiStore.setState({
      globalAuthToken: 'k1',
      globalProviderId: 'key',
      links: links([{ id: 'key', type: 'apiKey', apiKeyIn: 'header', apiKeyName: 'X-Api-Key' }]),
    })
    await authFetch('/graphql', { method: 'POST' })
    expect(sentHeaders().get('x-api-key')).toBe('k1')
    expect(sentHeaders().get('authorization')).toBeNull()
  })
})
