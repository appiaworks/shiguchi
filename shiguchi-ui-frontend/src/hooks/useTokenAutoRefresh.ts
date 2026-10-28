import { useEffect, useRef } from 'react'
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

export interface TokenUpdater {
  setToken: (token: string, expiry: number | null, refreshToken?: string | null) => void
  /** Called when auto-refresh fails and user action is required */
  openAuthDialog: () => void
}

const REFRESH_BEFORE_EXPIRY_MS = 60_000

/**
 * Watches a token's expiry and attempts auto-refresh 60s before it expires.
 *
 * Refresh strategy (in priority order):
 * 1. refresh_token grant — if a refresh token is stored and provider has a tokenUrl
 * 2. client_credentials re-fetch — if clientId + clientSecret are in memory
 * 3. Falls back to calling openAuthDialog() so the user can re-authenticate
 *
 * API-key and manual-bearer tokens have no expiry set, so this hook is a no-op for them.
 */
export function useTokenAutoRefresh(
  state: TokenState,
  updater: TokenUpdater,
  providers: AuthProviderDto[],
) {
  const stateRef = useRef(state)
  stateRef.current = state

  const updaterRef = useRef(updater)
  updaterRef.current = updater

  useEffect(() => {
    const { token, expiry, providerId } = stateRef.current
    if (!token || expiry == null) return

    const provider = providerId ? providers.find((p) => p.id === providerId) ?? null : null
    const tokenUrl =
      provider?.authorizationCode?.tokenUrl ??
      provider?.clientCredentials?.tokenUrl ??
      null

    const msUntilRefresh = expiry - Date.now() - REFRESH_BEFORE_EXPIRY_MS
    const delay = Math.max(0, msUntilRefresh)

    const timer = setTimeout(async () => {
      const current = stateRef.current

      // Token was cleared or renewed externally
      if (!current.token || current.expiry == null) return

      // Still valid with plenty of margin — race condition, skip
      if (current.expiry - Date.now() > REFRESH_BEFORE_EXPIRY_MS) return

      // Strategy 1: refresh_token grant
      if (current.refreshToken && tokenUrl && current.clientId) {
        try {
          const result = await refreshAccessToken(current.refreshToken, tokenUrl, current.clientId)
          updaterRef.current.setToken(
            result.accessToken,
            Date.now() + result.expiresIn * 1000,
            result.refreshToken ?? current.refreshToken,
          )
          return
        } catch {
          // fall through to next strategy
        }
      }

      // Strategy 2: silent client_credentials re-fetch
      if (current.providerId && current.clientId && current.clientSecret && provider?.clientCredentials) {
        try {
          const scopes = Object.keys(provider.clientCredentials.scopes ?? {})
          const tok = await fetchClientCredentialsToken(current.providerId, current.clientId, current.clientSecret, scopes)
          updaterRef.current.setToken(
            tok.accessToken,
            Date.now() + tok.expiresIn * 1000,
            null,
          )
          return
        } catch {
          // fall through to dialog
        }
      }

      // Strategy 3: open auth dialog
      updaterRef.current.openAuthDialog()
    }, delay)

    return () => clearTimeout(timer)
    // Re-schedule whenever expiry or token changes (new token sets new expiry)
  }, [state.token, state.expiry, state.refreshToken, providers])
}
