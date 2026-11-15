import { useEffect, useRef } from 'react'
import { refreshStoredToken, type TokenState } from '../lib/refreshStoredToken'
import type { AuthProviderDto } from '../types/schema'

export type { TokenState }

export interface TokenUpdater {
  setToken: (token: string, expiry: number | null, refreshToken?: string | null) => void
  /** Called when auto-refresh fails and user action is required */
  openAuthDialog: () => void
}

const REFRESH_BEFORE_EXPIRY_MS = 60_000

/**
 * Watches a token's expiry and attempts auto-refresh 60s before it expires.
 *
 * Refresh strategy (see refreshStoredToken): refresh_token grant, then client_credentials re-fetch,
 * then openAuthDialog() so the user can re-authenticate
 *
 * API-key and manual-bearer tokens have no expiry set, so this hook is a no-op for them.
 */
export function useTokenAutoRefresh(
  state: TokenState,
  updater: TokenUpdater,
  providers: AuthProviderDto[],
) {
  const { token, expiry, providerId } = state
  const stateRef = useRef(state)
  const updaterRef = useRef(updater)

  // Keep the refs current for the timer callback. Declared before the scheduling effect so it runs first.
  useEffect(() => {
    stateRef.current = state
    updaterRef.current = updater
  })

  useEffect(() => {
    if (!token || expiry == null) return

    const msUntilRefresh = expiry - Date.now() - REFRESH_BEFORE_EXPIRY_MS
    const delay = Math.max(0, msUntilRefresh)

    const timer = setTimeout(async () => {
      const current = stateRef.current

      // Token was cleared or renewed externally
      if (!current.token || current.expiry == null) return

      // Still valid with plenty of margin — race condition, skip
      if (current.expiry - Date.now() > REFRESH_BEFORE_EXPIRY_MS) return

      const refreshed = await refreshStoredToken(current, providers)
      if (refreshed) {
        updaterRef.current.setToken(refreshed.token, refreshed.expiry, refreshed.refreshToken)
        return
      }

      // Nothing worked silently: ask the user to sign in again
      updaterRef.current.openAuthDialog()
    }, delay)

    return () => clearTimeout(timer)
    // Re-schedule whenever expiry or token changes (new token sets new expiry)
  }, [token, expiry, providerId, providers])
}
