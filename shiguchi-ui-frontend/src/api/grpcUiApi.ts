import type { AuthProviderDto, GatewayUiLinks, InvokeResponse, ServiceSchema } from '../types/schema'
import type { MetadataEntry } from '../store/useGrpcUiStore'

import { apiUrl, csrfHeaders } from './baseUrl'

export async function fetchConfig(): Promise<GatewayUiLinks> {
  const r = await fetch(apiUrl('config'))
  if (!r.ok) throw new Error(`Config fetch failed: ${r.status}`)
  return r.json() as Promise<GatewayUiLinks>
}

export async function fetchSchema(): Promise<ServiceSchema[]> {
  const r = await fetch(apiUrl('schema'))
  if (!r.ok) throw new Error(`Schema fetch failed: ${r.status}`)
  return r.json() as Promise<ServiceSchema[]>
}

export async function fetchClientCredentialsToken(
  schemeId: string,
  clientId: string,
  clientSecret: string,
  scopes: string[],
): Promise<{ accessToken: string; expiresIn: number; tokenType: string }> {
  const r = await fetch(apiUrl(`auth/${encodeURIComponent(schemeId)}/token`), {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
    body: JSON.stringify({ clientId, clientSecret, scopes }),
  })
  if (!r.ok) {
    const body = await r.text().catch(() => r.statusText)
    throw new Error(`Token exchange failed (${r.status}): ${body}`)
  }
  return r.json()
}

export async function refreshAccessToken(
  refreshToken: string,
  tokenUrl: string,
  clientId: string,
): Promise<{ accessToken: string; expiresIn: number; refreshToken: string | null }> {
  const r = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
      client_id: clientId,
    }).toString(),
  })
  if (!r.ok) throw new Error(`Token refresh failed (${r.status})`)
  const data = await r.json() as Record<string, unknown>
  return {
    accessToken: data['access_token'] as string,
    expiresIn: (data['expires_in'] as number | undefined) ?? 3600,
    refreshToken: (data['refresh_token'] as string | undefined) ?? null,
  }
}

export function buildAuthHeaders(
  authToken: string,
  activeProviderId: string | null,
  authProviders: AuthProviderDto[],
): Record<string, string> {
  if (!authToken.trim()) return {}
  const provider = activeProviderId
    ? authProviders.find((p) => p.id === activeProviderId)
    : null
  if (provider?.type === 'apiKey' && provider.apiKeyIn === 'header' && provider.apiKeyName) {
    return { [provider.apiKeyName]: authToken.trim() }
  }
  return { Authorization: `Bearer ${authToken.trim()}` }
}

export async function invokeUnary(
  serviceName: string,
  methodName: string,
  payload: string,
  authToken: string,
  activeProviderId: string | null = null,
  authProviders: AuthProviderDto[] = [],
  metadataEntries: MetadataEntry[] = [],
): Promise<InvokeResponse> {
  const authHeaders = buildAuthHeaders(authToken, activeProviderId, authProviders)
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...csrfHeaders(), ...authHeaders }

  const r = await fetch(apiUrl('invoke'), {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify({
      serviceName,
      methodName,
      payload: payload || '{}',
      metadata: metadataEntries.filter((e) => e.key.trim()),
    }),
  })
  return r.json() as Promise<InvokeResponse>
}

/**
 * Client streaming: every collected message goes out on one call, and the backend answers once.
 * The messages travel as a `payloads` array; `payload` stays set to the first of them so an
 * older gateway still sees a well-formed single-message request.
 */
export async function invokeClientStream(
  serviceName: string,
  methodName: string,
  payloads: string[],
  authToken: string,
  activeProviderId: string | null = null,
  authProviders: AuthProviderDto[] = [],
  metadataEntries: MetadataEntry[] = [],
): Promise<InvokeResponse> {
  const authHeaders = buildAuthHeaders(authToken, activeProviderId, authProviders)
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...csrfHeaders(), ...authHeaders }

  const messages = payloads.map((p) => p || '{}')
  const r = await fetch(apiUrl('invoke'), {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify({
      serviceName,
      methodName,
      payload: messages[0] ?? '{}',
      payloads: messages,
      metadata: metadataEntries.filter((e) => e.key.trim()),
    }),
  })
  return r.json() as Promise<InvokeResponse>
}

function httpStatusToGrpcCode(status: number): string {
  switch (status) {
    case 401: return 'UNAUTHENTICATED'
    case 403: return 'PERMISSION_DENIED'
    case 404: return 'NOT_FOUND'
    case 429: return 'RESOURCE_EXHAUSTED'
    case 503: return 'UNAVAILABLE'
    default: return 'UNKNOWN'
  }
}

export async function* invokeStream(
  serviceName: string,
  methodName: string,
  payload: string,
  authToken: string,
  signal: AbortSignal,
  activeProviderId: string | null = null,
  authProviders: AuthProviderDto[] = [],
  metadataEntries: MetadataEntry[] = [],
): AsyncGenerator<{ event: 'data' | 'done' | 'error'; data: string }> {
  const authHeaders = buildAuthHeaders(authToken, activeProviderId, authProviders)
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...csrfHeaders(), ...authHeaders }

  const r = await fetch(apiUrl('invoke/stream'), {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify({
      serviceName,
      methodName,
      payload: payload || '{}',
      metadata: metadataEntries.filter((e) => e.key.trim()),
    }),
    signal,
  })

  if (!r.ok || !r.body) {
    // An auth filter in front of the console answers with a plain HTTP status, not an SSE body.
    // Without this the empty body parses to zero events and the panel never leaves "Streaming".
    const code = httpStatusToGrpcCode(r.status)
    const text = (await r.text().catch(() => '')).trim().slice(0, 300)
    yield {
      event: 'error',
      data: JSON.stringify({ code, message: text ? `HTTP ${r.status}: ${text}` : `HTTP ${r.status}` }),
    }
    return
  }

  const reader = r.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let eventType = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buf += dec.decode(value, { stream: true })
    const lines = buf.split('\n')
    buf = lines.pop() ?? ''

    for (const rawLine of lines) {
      // SSE allows CRLF and an optional space after the colon; Spring's writer emits `event:error`.
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        const data = line.slice(line.startsWith('data: ') ? 6 : 5)
        if (eventType === 'data' || eventType === 'done' || eventType === 'error') {
          yield { event: eventType, data }
          if (eventType === 'done') return
        }
        eventType = ''
      }
    }
  }
}