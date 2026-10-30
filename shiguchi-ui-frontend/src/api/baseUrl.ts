/**
 * URLs are resolved against the page itself, never against a hardcoded mount point: the backend
 * serves the SPA at `{contextPath}{shiguchi.ui.path}/index.html`, and both are configurable.
 */

/** Resolves `relative` against the document's base URI (the directory holding index.html). */
export function appUrl(relative: string): string {
  return new URL(relative, document.baseURI).href
}

/** Absolute URL of a gateway console API endpoint, e.g. `apiUrl('invoke')`. */
export function apiUrl(path: string): string {
  return appUrl(`api/${path.replace(/^\/+/, '')}`)
}

/** Redirect URI registered for the OAuth2 authorization-code popup flow. */
export function oauthCallbackUrl(): string {
  return appUrl('oauth2-callback.html')
}

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * The CSRF header for a state-changing call to the gateway, or nothing when the XSRF-TOKEN
 * cookie is absent (Spring Security's CookieCsrfTokenRepository convention).
 */
export function csrfHeaders(): Record<string, string> {
  for (const part of document.cookie.split(';')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    if (part.slice(0, eq).trim() !== CSRF_COOKIE) continue
    const raw = part.slice(eq + 1).trim()
    let value = raw
    try {
      value = decodeURIComponent(raw)
    } catch {
      // A malformed escape: send the raw value rather than dropping the header.
    }
    return value ? { [CSRF_HEADER]: value } : {}
  }
  return {}
}
