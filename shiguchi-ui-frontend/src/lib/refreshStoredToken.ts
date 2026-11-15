import { fetchClientCredentialsToken, refreshAccessToken } from '../api/grpcUiApi'
import type { AuthProviderDto } from '../types/schema'

export interface TokenState {
  token: string
  expiry: number | null
  refreshToken: string | null
  providerId: string | null
  /** client_id remembered for silent client_credentials refresh */
  clientId?: string
  /** client_secret kept in memory for silent client_credentials refresh */
  clientSecret?: string
}

export interface RefreshedToken {
  token: string
  expiry: number
  refreshToken: string | null
}

/**
 * Tries to get a new access token without user interaction: first the refresh_token grant (needs a
 * refresh token, the provider's token URL and the client id), then a client_credentials re-fetch
 * (needs the client id and secret). Resolves null when neither is possible or both fail.
 */
export async function refreshStoredToken(
  current: TokenState,
  providers: AuthProviderDto[],
): Promise<RefreshedToken | null> {
  const provider = current.providerId ? providers.find((p) => p.id === current.providerId) ?? null : null
  const tokenUrl = provider?.authorizationCode?.tokenUrl ?? provider?.clientCredentials?.tokenUrl ?? null

  if (current.refreshToken && tokenUrl && current.clientId) {
    try {
      const result = await refreshAccessToken(current.refreshToken, tokenUrl, current.clientId)
      return {
        token: result.accessToken,
        expiry: Date.now() + result.expiresIn * 1000,
        refreshToken: result.refreshToken ?? current.refreshToken,
      }
    } catch {
      // fall through to client_credentials
    }
  }

  if (current.providerId && current.clientId && current.clientSecret && provider?.clientCredentials) {
    try {
      const scopes = Object.keys(provider.clientCredentials.scopes ?? {})
      const tok = await fetchClientCredentialsToken(current.providerId, current.clientId, current.clientSecret, scopes)
      return { token: tok.accessToken, expiry: Date.now() + tok.expiresIn * 1000, refreshToken: null }
    } catch {
      // nothing left to try
    }
  }
  return null
}
