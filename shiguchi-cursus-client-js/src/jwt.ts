/** The `exp` of a JWT in epoch milliseconds, read without validating anything; undefined if there is none. */
export function jwtExpiryMs(token: string): number | undefined {
  const parts = token.split('.')
  if (parts.length < 2) return undefined
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
    const claims = JSON.parse(new TextDecoder().decode(bytes)) as { exp?: unknown }
    return typeof claims.exp === 'number' ? claims.exp * 1000 : undefined
  } catch {
    return undefined
  }
}
