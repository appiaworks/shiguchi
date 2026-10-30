import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { apiUrl, appUrl, csrfHeaders, oauthCallbackUrl } from './baseUrl'

/** Moves the jsdom document, which is what `document.baseURI` follows (no <base> element). */
function servedAt(url: string) {
  window.history.pushState({}, '', url)
}

function clearCookie(name: string) {
  document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
}

describe('URL helpers', () => {
  beforeEach(() => servedAt('/ctx/tools/grpc/index.html?x=1#/svc'))
  afterEach(() => servedAt('/'))

  it('resolves API calls next to index.html, under a context path and a custom UI path', () => {
    expect(apiUrl('invoke')).toBe(`${window.location.origin}/ctx/tools/grpc/api/invoke`)
    expect(apiUrl('/auth/a%20b/token')).toBe(`${window.location.origin}/ctx/tools/grpc/api/auth/a%20b/token`)
  })

  it('builds the OAuth callback beside the page, not at a hardcoded /grpc-ui', () => {
    expect(oauthCallbackUrl()).toBe(`${window.location.origin}/ctx/tools/grpc/oauth2-callback.html`)
    expect(appUrl('x')).not.toContain('/grpc-ui/')
  })

  it('works for the default mount without a context path', () => {
    servedAt('/grpc-ui/index.html')

    expect(apiUrl('config')).toBe(`${window.location.origin}/grpc-ui/api/config`)
  })
})

describe('csrfHeaders', () => {
  afterEach(() => clearCookie('XSRF-TOKEN'))

  it('sends nothing when there is no XSRF-TOKEN cookie', () => {
    expect(csrfHeaders()).toEqual({})
  })

  it('echoes the decoded XSRF-TOKEN cookie in X-XSRF-TOKEN', () => {
    document.cookie = 'other=1; path=/'
    document.cookie = 'XSRF-TOKEN=a%2Bb%3D%3D; path=/'

    expect(csrfHeaders()).toEqual({ 'X-XSRF-TOKEN': 'a+b==' })
  })
})
