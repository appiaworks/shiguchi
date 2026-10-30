import { useEffect, useRef, useState } from 'react'
import { fetchClientCredentialsToken } from '../api/grpcUiApi'
import { useTokenAutoRefresh } from '../hooks/useTokenAutoRefresh'
import { getSessionSecret, setSessionSecret } from '../store/sessionSecrets'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { oauthCallbackUrl } from '../api/baseUrl'

// Sentinel: per-call auth explicitly set to manual bearer with no provider
const MANUAL = '__manual__'

async function generatePkce(): Promise<{ verifier: string; challenge: string }> {
  const array = new Uint8Array(32)
  crypto.getRandomValues(array)
  const verifier = btoa(String.fromCharCode(...array))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
  const data = new TextEncoder().encode(verifier)
  const digest = await crypto.subtle.digest('SHA-256', data)
  const challenge = btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
  return { verifier, challenge }
}

export function AuthBar() {
  const {
    authToken, tokenExpiry, activeProviderId, links,
    globalAuthToken, globalTokenExpiry, globalProviderId,
    refreshToken, providerDefaults,
    setAuthToken, setActiveProviderId, setGlobalAuthDialogOpen,
  } = useGrpcUiStore()

  const authProviders = links?.authProviders ?? []

  // null = inherit global; MANUAL = manual bearer override; else provider id
  const selectValue = activeProviderId === null ? '' : activeProviderId

  const isInherit = activeProviderId === null
  const isManualOverride = activeProviderId === MANUAL
  const isProviderOverride = !isInherit && !isManualOverride

  const activeProvider = isProviderOverride
    ? (authProviders.find((p) => p.id === activeProviderId) ?? null)
    : null

  const globalProvider = globalProviderId
    ? (authProviders.find((p) => p.id === globalProviderId) ?? null)
    : null

  const savedDefaults = activeProviderId && isProviderOverride
    ? (providerDefaults[activeProviderId] ?? {})
    : {}

  const [clientId, setClientId] = useState(savedDefaults.clientId ?? '')
  const [clientSecret, setClientSecret] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reauthNeeded, setReauthNeeded] = useState(false)
  const pkceVerifierRef = useRef<string | null>(null)

  // Auto-refresh local (per-call) token
  useTokenAutoRefresh(
    {
      token: isInherit ? '' : authToken,
      expiry: isInherit ? null : tokenExpiry,
      refreshToken: isInherit ? null : refreshToken,
      providerId: isProviderOverride ? activeProviderId : null,
      clientId: (isProviderOverride && activeProviderId) ? (providerDefaults[activeProviderId]?.clientId ?? clientId) : undefined,
      clientSecret: (isProviderOverride && activeProviderId) ? getSessionSecret(activeProviderId) : undefined,
    },
    {
      setToken: (token, expiry, rt) => setAuthToken(token, expiry, rt),
      openAuthDialog: () => {
        if (isInherit) {
          setGlobalAuthDialogOpen(true)
        } else {
          setReauthNeeded(true)
        }
      },
    },
    authProviders,
  )

  useEffect(() => {
    if (isProviderOverride && activeProviderId) {
      setClientId(providerDefaults[activeProviderId]?.clientId ?? '')
    } else {
      setClientId('')
    }
    setClientSecret('')
    setError(null)
    setReauthNeeded(false)
  }, [activeProviderId, isProviderOverride, providerDefaults])

  const now = Date.now()
  const effectiveToken = isInherit ? globalAuthToken : authToken
  const effectiveExpiry = isInherit ? globalTokenExpiry : tokenExpiry
  const secondsLeft = effectiveExpiry ? Math.max(0, Math.round((effectiveExpiry - now) / 1000)) : null
  const isExpired = effectiveExpiry != null && effectiveExpiry <= now

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (event.origin !== window.location.origin) return
      if ((event.data as { type?: string })?.type !== 'oauth2-callback') return
      const { code, error: cbError } = event.data as { code?: string; error?: string }
      if (cbError) { setError(`OAuth2 error: ${cbError}`); setLoading(false); return }
      const verifier = pkceVerifierRef.current
      if (!code || !verifier || !activeProvider?.authorizationCode?.tokenUrl) return
      pkceVerifierRef.current = null
      const callbackUri = oauthCallbackUrl()
      fetch(activeProvider.authorizationCode.tokenUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'authorization_code', code,
          redirect_uri: callbackUri, code_verifier: verifier, client_id: clientId,
        }).toString(),
      })
        .then((r) => { if (!r.ok) throw new Error(`Token endpoint: ${r.status}`); return r.json() })
        .then((data: Record<string, unknown>) => {
          const token = data['access_token'] as string
          const expiresIn = (data['expires_in'] as number | undefined) ?? 3600
          const rt = (data['refresh_token'] as string | undefined) ?? null
          setAuthToken(token, Date.now() + expiresIn * 1000, rt)
          setError(null)
        })
        .catch((e: unknown) => setError(String(e)))
        .finally(() => setLoading(false))
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [activeProvider, clientId, setAuthToken])

  async function handlePkceLogin() {
    if (!activeProvider?.authorizationCode?.authorizationUrl) return
    setLoading(true); setError(null)
    const { verifier, challenge } = await generatePkce()
    pkceVerifierRef.current = verifier
    const callbackUri = oauthCallbackUrl()
    const scopes = Object.keys(activeProvider.authorizationCode.scopes ?? {})
    const params = new URLSearchParams({
      response_type: 'code', client_id: clientId,
      redirect_uri: callbackUri, code_challenge: challenge,
      code_challenge_method: 'S256', state: Math.random().toString(36).slice(2),
      ...(scopes.length ? { scope: scopes.join(' ') } : {}),
    })
    const popup = window.open(
      `${activeProvider.authorizationCode.authorizationUrl}?${params.toString()}`,
      'oauth2_login', 'width=600,height=700',
    )
    if (!popup) { setError('Popup blocked — allow popups for this page'); setLoading(false) }
  }

  async function handleClientCredentials() {
    if (!activeProvider) return
    setLoading(true); setError(null)
    const scopes = Object.keys(activeProvider.clientCredentials?.scopes ?? {})
    try {
      const tok = await fetchClientCredentialsToken(activeProvider.id, clientId, clientSecret, scopes)
      setAuthToken(tok.accessToken, Date.now() + tok.expiresIn * 1000, null)
      if (clientSecret) setSessionSecret(activeProvider.id, clientSecret)
      setError(null)
    } catch (e: unknown) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  const isOAuth2Code = activeProvider?.type === 'oauth2' && !!activeProvider.authorizationCode
  const isOAuth2Client = activeProvider?.type === 'oauth2' && !isOAuth2Code && !!activeProvider.clientCredentials
  const isApiKey = activeProvider?.type === 'apiKey'

  function handleProviderChange(value: string) {
    setError(null)
    if (value === '') {
      setActiveProviderId(null)
    } else {
      setActiveProviderId(value)
    }
  }

  return (
    <div className="border-b border-slate-700 shrink-0">
      {/* Provider selector row */}
      <div className="flex items-center gap-2 px-3 py-1.5">
        <span className="text-slate-500 text-xs shrink-0">Auth</span>
        <select
          value={selectValue}
          onChange={(e) => handleProviderChange(e.target.value)}
          className="bg-slate-800 text-slate-300 text-xs px-1 py-0.5 rounded border border-slate-700 outline-none"
        >
          <option value="">Inherit (global)</option>
          <option value={MANUAL}>Bearer (manual)</option>
          {authProviders.map((p) => (
            <option key={p.id} value={p.id}>
              {p.description ?? p.id} ({p.type})
            </option>
          ))}
        </select>

        {/* Token status for non-inherit modes, or inherited provider label */}
        {isInherit ? (
          <span className="text-[10px] text-slate-600">
            {globalProvider
              ? `↳ ${globalProvider.description ?? globalProviderId}`
              : globalAuthToken
                ? '↳ manual bearer'
                : '↳ no auth'}
          </span>
        ) : null}

        {effectiveToken && effectiveExpiry != null && (
          <span
            className={`ml-auto text-[10px] shrink-0 ${
              isExpired ? 'text-red-400' : secondsLeft! < 60 ? 'text-yellow-400' : 'text-green-500'
            }`}
          >
            {isExpired ? (reauthNeeded ? '⚠ re-auth needed' : 'expired') : secondsLeft! < 60 ? `${secondsLeft}s` : `${Math.round(secondsLeft! / 60)}m`}
          </span>
        )}
      </div>

      {/* Inherit — show effective token preview if set */}
      {isInherit && effectiveToken && (
        <div className="flex items-center gap-2 px-3 pb-1.5">
          <span className="text-slate-700 text-[10px] shrink-0 font-mono truncate max-w-[20rem]" title={effectiveToken}>
            {effectiveToken.slice(0, 32)}…
          </span>
        </div>
      )}

      {/* Manual bearer override */}
      {isManualOverride && (
        <div className="flex items-center gap-2 px-3 pb-1.5">
          <span className="text-slate-600 text-[10px] shrink-0">Bearer</span>
          <input
            type="text"
            value={authToken}
            onChange={(e) => setAuthToken(e.target.value)}
            placeholder="Token (optional)"
            className="flex-1 bg-transparent text-slate-300 text-xs outline-none placeholder-slate-700 font-mono"
          />
        </div>
      )}

      {/* API Key override */}
      {isApiKey && (
        <div className="flex items-center gap-2 px-3 pb-1.5">
          <span className="text-slate-600 text-[10px] shrink-0">
            {activeProvider!.apiKeyIn}/{activeProvider!.apiKeyName}
          </span>
          <input
            type="text"
            value={authToken}
            onChange={(e) => setAuthToken(e.target.value)}
            placeholder="API key"
            className="flex-1 bg-transparent text-slate-300 text-xs outline-none placeholder-slate-700 font-mono"
          />
        </div>
      )}

      {/* OAuth2 flows override */}
      {(isOAuth2Code || isOAuth2Client) && (
        <div className="flex flex-wrap items-center gap-2 px-3 pb-1.5">
          <input
            type="text"
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
            placeholder={savedDefaults.clientId ? `client_id (${savedDefaults.clientId})` : 'client_id'}
            className="w-36 bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
          />
          {isOAuth2Client && (
            <input
              type="password"
              value={clientSecret}
              onChange={(e) => setClientSecret(e.target.value)}
              placeholder="client_secret"
              className="w-36 bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
            />
          )}
          {authToken ? (
            <>
              <span
                className="text-green-500 text-[10px] font-mono truncate max-w-[8rem]"
                title={authToken}
              >
                ✓ {authToken.slice(0, 20)}…
              </span>
              <button
                onClick={() => setAuthToken('', null)}
                className="text-[10px] text-slate-500 hover:text-red-400"
              >
                clear
              </button>
            </>
          ) : null}
          <button
            onClick={() => { setReauthNeeded(false); void (isOAuth2Code ? handlePkceLogin() : handleClientCredentials()) }}
            disabled={loading || !clientId || (isOAuth2Client && !clientSecret)}
            className={`px-2 py-0.5 rounded text-[10px] text-blue-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors ${
              reauthNeeded ? 'bg-red-700 hover:bg-red-600 animate-pulse' : 'bg-blue-800 hover:bg-blue-700'
            }`}
          >
            {loading ? '…' : reauthNeeded ? 'Re-authenticate' : isOAuth2Code ? 'Login (PKCE)' : 'Get Token'}
          </button>
        </div>
      )}

      {error && (
        <div className="px-3 pb-1.5 text-[10px] text-red-400">{error}</div>
      )}
    </div>
  )
}
