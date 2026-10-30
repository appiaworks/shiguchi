import { useEffect, useRef, useState } from 'react'
import { fetchClientCredentialsToken } from '../api/grpcUiApi'
import { setSessionSecret } from '../store/sessionSecrets'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { oauthCallbackUrl } from '../api/baseUrl'

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

export function GlobalAuthPanel({ onClose }: { onClose: () => void }) {
  const {
    globalProviderId, globalAuthToken, globalTokenExpiry,
    providerDefaults, links,
    setGlobalProviderId, setGlobalAuthToken, setProviderDefault,
  } = useGrpcUiStore()

  const authProviders = links?.authProviders ?? []
  const activeProvider = globalProviderId
    ? (authProviders.find((p) => p.id === globalProviderId) ?? null)
    : null

  const savedDefaults = globalProviderId ? (providerDefaults[globalProviderId] ?? {}) : {}
  const [clientId, setClientId] = useState(savedDefaults.clientId ?? '')
  const [clientSecret, setClientSecret] = useState('')
  const [rememberClientId, setRememberClientId] = useState(!!savedDefaults.clientId)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pkceVerifierRef = useRef<string | null>(null)

  useEffect(() => {
    if (globalProviderId) {
      const d = providerDefaults[globalProviderId] ?? {}
      setClientId(d.clientId ?? '')
      setRememberClientId(!!d.clientId)
    } else {
      setClientId('')
      setRememberClientId(false)
    }
    setClientSecret('')
    setError(null)
  }, [globalProviderId, providerDefaults])

  const now = Date.now()
  const secondsLeft = globalTokenExpiry ? Math.max(0, Math.round((globalTokenExpiry - now) / 1000)) : null
  const isExpired = globalTokenExpiry != null && globalTokenExpiry <= now

  const isOAuth2Code = activeProvider?.type === 'oauth2' && !!activeProvider.authorizationCode
  const isOAuth2Client = activeProvider?.type === 'oauth2' && !isOAuth2Code && !!activeProvider.clientCredentials
  const isApiKey = activeProvider?.type === 'apiKey'
  const isManual = !activeProvider || activeProvider.type === 'http'

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (event.origin !== window.location.origin) return
      if ((event.data as { type?: string })?.type !== 'oauth2-callback') return
      const { code, error: cbErr } = event.data as { code?: string; error?: string }
      if (cbErr) { setError(`OAuth2 error: ${cbErr}`); setLoading(false); return }
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
          setGlobalAuthToken(token, Date.now() + expiresIn * 1000, rt)
          if (rememberClientId && globalProviderId) setProviderDefault(globalProviderId, { clientId })
          setError(null)
          onClose()
        })
        .catch((e: unknown) => setError(String(e)))
        .finally(() => setLoading(false))
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [activeProvider, clientId, rememberClientId, globalProviderId, setGlobalAuthToken, setProviderDefault])

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
      setGlobalAuthToken(tok.accessToken, Date.now() + tok.expiresIn * 1000, null)
      if (clientSecret) setSessionSecret(activeProvider.id, clientSecret)
      if (rememberClientId && globalProviderId) setProviderDefault(globalProviderId, { clientId })
      setError(null)
      onClose()
    } catch (e: unknown) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="absolute top-full left-0 right-0 z-50 bg-slate-900 border-b border-slate-700 shadow-2xl">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-2 border-b border-slate-800">
        <span className="text-xs font-semibold text-slate-200">Global Auth</span>
        <span className="text-[10px] text-slate-500">Applied to all requests unless overridden per-call</span>
        <div className="flex-1" />
        {globalAuthToken && (
          <div className="flex items-center gap-2">
            <span
              className={`text-[10px] font-mono ${
                isExpired ? 'text-red-400' : secondsLeft !== null && secondsLeft < 60 ? 'text-yellow-400' : 'text-green-500'
              }`}
            >
              {isExpired
                ? '⚠ expired'
                : secondsLeft !== null
                  ? secondsLeft < 60 ? `${secondsLeft}s` : `${Math.round(secondsLeft / 60)}m`
                  : '✓ active'}
            </span>
            <button
              onClick={() => setGlobalAuthToken('', null)}
              className="text-[10px] text-slate-600 hover:text-red-400 transition-colors"
            >
              clear
            </button>
          </div>
        )}
        <button
          onClick={onClose}
          className="text-slate-500 hover:text-slate-200 text-sm ml-1 transition-colors"
        >
          ✕
        </button>
      </div>

      <div className="px-4 py-3 space-y-3 max-w-2xl">
        {/* Provider selector */}
        <div className="flex items-center gap-3">
          <span className="text-[10px] text-slate-500 w-20 shrink-0">Provider</span>
          <select
            value={globalProviderId ?? ''}
            onChange={(e) => { setGlobalProviderId(e.target.value || null); setError(null) }}
            className="bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded border border-slate-700 outline-none"
          >
            <option value="">Bearer (manual)</option>
            {authProviders.map((p) => (
              <option key={p.id} value={p.id}>
                {p.description ?? p.id} ({p.type})
              </option>
            ))}
          </select>
        </div>

        {/* Manual bearer */}
        {isManual && (
          <div className="flex items-center gap-3">
            <span className="text-[10px] text-slate-500 w-20 shrink-0">Token</span>
            <input
              type="text"
              value={globalAuthToken}
              onChange={(e) => setGlobalAuthToken(e.target.value)}
              placeholder="Bearer token (optional)"
              className="flex-1 bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
            />
          </div>
        )}

        {/* API Key */}
        {isApiKey && (
          <div className="flex items-center gap-3">
            <span className="text-[10px] text-slate-500 w-20 shrink-0">
              {activeProvider!.apiKeyIn}/{activeProvider!.apiKeyName}
            </span>
            <input
              type="text"
              value={globalAuthToken}
              onChange={(e) => setGlobalAuthToken(e.target.value)}
              placeholder="API key"
              className="flex-1 bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
            />
          </div>
        )}

        {/* OAuth2 flows */}
        {(isOAuth2Code || isOAuth2Client) && (
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-slate-500 w-20 shrink-0">client_id</span>
              <input
                type="text"
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
                placeholder="client_id"
                className="w-52 bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
              />
              <label className="flex items-center gap-1 text-[10px] text-slate-500 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={rememberClientId}
                  onChange={(e) => setRememberClientId(e.target.checked)}
                  className="w-3 h-3 accent-blue-500"
                />
                remember
              </label>
            </div>
            {isOAuth2Client && (
              <div className="flex items-center gap-2">
                <span className="text-[10px] text-slate-500 w-20 shrink-0">client_secret</span>
                <input
                  type="password"
                  value={clientSecret}
                  onChange={(e) => setClientSecret(e.target.value)}
                  placeholder="client_secret (not saved)"
                  className="w-52 bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded border border-slate-700 outline-none placeholder-slate-600 font-mono"
                />
              </div>
            )}
            <div className="flex items-center gap-3">
              {globalAuthToken && (
                <span className="text-green-500 text-[10px] font-mono" title={globalAuthToken}>
                  ✓ {globalAuthToken.slice(0, 24)}…
                </span>
              )}
              <button
                onClick={isOAuth2Code ? handlePkceLogin : handleClientCredentials}
                disabled={loading || !clientId || (isOAuth2Client && !clientSecret)}
                className={`px-3 py-1 rounded text-xs text-blue-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors ${
                  isExpired ? 'bg-red-700 hover:bg-red-600 animate-pulse' : 'bg-blue-800 hover:bg-blue-700'
                }`}
              >
                {loading ? '…' : isExpired ? 'Re-authenticate' : isOAuth2Code ? 'Login (PKCE)' : 'Get Token'}
              </button>
            </div>
          </div>
        )}

        {error && <div className="text-[10px] text-red-400">{error}</div>}
      </div>
    </div>
  )
}
