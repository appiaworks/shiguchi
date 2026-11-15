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

  describe('401 handling', () => {
    const oauthLinks = links([
      {
        id: 'kc', type: 'oauth2',
        authorizationCode: { authorizationUrl: 'https://idp/auth', tokenUrl: 'https://idp/token', scopes: {} },
      } as never,
    ])
    const signedIn = () =>
      useGrpcUiStore.setState({
        globalAuthToken: 'old', globalTokenExpiry: Date.now() + 5_000, globalRefreshToken: 'r1',
        globalProviderId: 'kc', links: oauthLinks, providerDefaults: { kc: { clientId: 'swagger' } } as never,
      })

    it('GIVEN a 401 and a refresh token WHEN fetching SHOULD refresh once and retry with the new token', async () => {
      signedIn()
      const calls: string[] = []
      vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
        if (url === 'https://idp/token') {
          calls.push('token')
          return Promise.resolve(new Response(JSON.stringify({ access_token: 'new', expires_in: 300, refresh_token: 'r2' })))
        }
        const auth = new Headers(init?.headers).get('authorization')
        calls.push(auth ?? 'none')
        return Promise.resolve(new Response('{}', { status: auth === 'Bearer new' ? 200 : 401 }))
      }))
      const res = await authFetch('/graphql', { method: 'POST', body: '{}' })
      expect(res.status).toBe(200)
      expect(calls).toEqual(['Bearer old', 'token', 'Bearer new'])
      expect(useGrpcUiStore.getState().globalAuthToken).toBe('new')
    })

    it('GIVEN a 401 and a failing refresh WHEN fetching SHOULD return the 401 without retrying', async () => {
      signedIn()
      const f = vi.fn((url: string) =>
        Promise.resolve(new Response('{}', { status: url === 'https://idp/token' ? 400 : 401 })))
      vi.stubGlobal('fetch', f)
      const res = await authFetch('/graphql', { method: 'POST', body: '{}' })
      expect(res.status).toBe(401)
      expect(f).toHaveBeenCalledTimes(2) // request + token attempt, no retry
    })

    it('GIVEN a 401 on a manual bearer token WHEN fetching SHOULD not try to refresh', async () => {
      useGrpcUiStore.setState({ globalAuthToken: 'abc', globalTokenExpiry: null, globalProviderId: null, links: null })
      const f = vi.fn(() => Promise.resolve(new Response('{}', { status: 401 })))
      vi.stubGlobal('fetch', f)
      const res = await authFetch('/graphql', { method: 'POST', body: '{}' })
      expect(res.status).toBe(401)
      expect(f).toHaveBeenCalledTimes(1)
    })
  })
})
