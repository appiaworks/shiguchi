import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { buildAuthHeaders, fetchClientCredentialsToken, invokeClientStream, invokeStream, invokeUnary, refreshAccessToken } from './grpcUiApi'
import type { AuthProviderDto } from '../types/schema'

const apiKeyProvider: AuthProviderDto = {
  id: 'apikey',
  type: 'apiKey',
  apiKeyIn: 'header',
  apiKeyName: 'X-API-Key',
}

const oauthProvider: AuthProviderDto = { id: 'oauth', type: 'oauth2' }

describe('buildAuthHeaders', () => {
  it('sends nothing when there is no token, rather than an empty Authorization header', () => {
    // An `Authorization: Bearer ` header is worse than none: it turns "anonymous" into
    // "malformed credentials" at the gateway.
    expect(buildAuthHeaders('', null, [])).toEqual({})
    expect(buildAuthHeaders('   ', null, [])).toEqual({})
  })

  it('defaults to a bearer token when no provider is selected', () => {
    expect(buildAuthHeaders('abc123', null, [])).toEqual({ Authorization: 'Bearer abc123' })
  })

  it('uses the provider’s own header name for an API-key provider', () => {
    expect(buildAuthHeaders('secret', 'apikey', [apiKeyProvider])).toEqual({ 'X-API-Key': 'secret' })
  })

  it('falls back to a bearer token for a provider that is not an API key', () => {
    expect(buildAuthHeaders('abc123', 'oauth', [oauthProvider])).toEqual({
      Authorization: 'Bearer abc123',
    })
  })

  it('trims the token, since a value pasted from a terminal usually carries whitespace', () => {
    expect(buildAuthHeaders('  abc123\n', null, [])).toEqual({ Authorization: 'Bearer abc123' })
  })
})

describe('invoke requests', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    fetchMock.mockResolvedValue({ json: async () => ({ status: 'OK' }) })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** The JSON body of the single fetch call made. */
  function sentBody(): Record<string, unknown> {
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    return JSON.parse(init.body as string) as Record<string, unknown>
  }

  it('drops metadata rows with a blank key, which the editor creates as you type', () => {
    void invokeUnary('Catalog', 'GetItem', '{}', '', null, [], [
      { key: 'x-tenant', value: 'acme' },
      { key: '   ', value: 'a half-typed row' },
      { key: '', value: '' },
    ])

    expect(sentBody().metadata).toEqual([{ key: 'x-tenant', value: 'acme' }])
  })

  it('sends an empty object when the payload is blank, so the call is still valid JSON', () => {
    void invokeUnary('Catalog', 'GetItem', '', '', null, [], [])

    expect(sentBody().payload).toBe('{}')
  })

  it('sends client-streaming messages as payloads AND keeps payload set to the first one', () => {
    // The compatibility contract: an older gateway that does not know about `payloads` still
    // sees a well-formed single-message request instead of an empty one.
    void invokeClientStream('Catalog', 'Collect', ['{"n":1}', '{"n":2}'], '', null, [], [])

    const body = sentBody()
    expect(body.payloads).toEqual(['{"n":1}', '{"n":2}'])
    expect(body.payload).toBe('{"n":1}')
  })

  it('attaches the auth header the provider asked for', () => {
    void invokeUnary('Catalog', 'GetItem', '{}', 'secret', 'apikey', [apiKeyProvider], [])

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.headers).toMatchObject({ 'X-API-Key': 'secret' })
  })

  it('posts to the API next to the page, so a context path or custom UI path still works', () => {
    window.history.pushState({}, '', '/ctx/tools/grpc/index.html')
    try {
      void invokeUnary('Catalog', 'GetItem', '{}', '', null, [], [])
      void invokeClientStream('Catalog', 'Collect', ['{}'], '', null, [], [])

      const origin = window.location.origin
      expect(fetchMock.mock.calls[0][0]).toBe(`${origin}/ctx/tools/grpc/api/invoke`)
      expect(fetchMock.mock.calls[1][0]).toBe(`${origin}/ctx/tools/grpc/api/invoke`)
    } finally {
      window.history.pushState({}, '', '/')
    }
  })

  describe('CSRF', () => {
    afterEach(() => {
      document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    })

    function headersOf(call: number): Record<string, string> {
      return (fetchMock.mock.calls[call][1] as RequestInit).headers as Record<string, string>
    }

    it('adds X-XSRF-TOKEN to invoke when the cookie exists', () => {
      document.cookie = 'XSRF-TOKEN=tok%2F1; path=/'

      void invokeUnary('Catalog', 'GetItem', '{}', 'secret', null, [], [])

      expect(headersOf(0)).toMatchObject({ 'X-XSRF-TOKEN': 'tok/1', Authorization: 'Bearer secret' })
      expect((fetchMock.mock.calls[0][1] as RequestInit).credentials).toBe('same-origin')
    })

    it('omits the header without the cookie', () => {
      void invokeUnary('Catalog', 'GetItem', '{}', '', null, [], [])

      expect(headersOf(0)).not.toHaveProperty('X-XSRF-TOKEN')
    })

    it('adds it to invoke/stream and the token exchange, but not to the external refresh', async () => {
      document.cookie = 'XSRF-TOKEN=t; path=/'
      fetchMock.mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: 'a', accessToken: 'a', expiresIn: 1 }),
        body: { getReader: () => ({ read: async () => ({ done: true }) }) },
      })

      await invokeStream('S', 'M', '{}', '', new AbortController().signal).next()
      await fetchClientCredentialsToken('basic', 'id', 'secret', [])
      await refreshAccessToken('rt', 'https://idp.example.com/token', 'cid')

      expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/invoke\/stream$/)
      expect(headersOf(0)).toMatchObject({ 'X-XSRF-TOKEN': 't' })
      expect(String(fetchMock.mock.calls[1][0])).toMatch(/\/api\/auth\/basic\/token$/)
      expect(headersOf(1)).toMatchObject({ 'X-XSRF-TOKEN': 't' })
      expect(fetchMock.mock.calls[2][0]).toBe('https://idp.example.com/token')
      expect(JSON.stringify(fetchMock.mock.calls[2][1])).not.toContain('XSRF')
    })
  })
})
