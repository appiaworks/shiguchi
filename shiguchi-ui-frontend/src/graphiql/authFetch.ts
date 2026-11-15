import { buildAuthHeaders } from '../api/grpcUiApi'
import { refreshStoredToken, type RefreshedToken } from '../lib/refreshStoredToken'
import { getSessionSecret } from '../store/sessionSecrets'
import { useGrpcUiStore } from '../store/useGrpcUiStore'

let inFlight: Promise<RefreshedToken | null> | null = null

/** One refresh at a time: concurrent 401s share the same attempt. Stores the new token on success. */
function refreshOnce(): Promise<RefreshedToken | null> {
  inFlight ??= (async () => {
    const s = useGrpcUiStore.getState()
    if (!s.globalAuthToken || s.globalTokenExpiry == null) return null // API key or manual bearer: nothing to renew
    const id = s.globalProviderId
    const refreshed = await refreshStoredToken(
      {
        token: s.globalAuthToken,
        expiry: s.globalTokenExpiry,
        refreshToken: s.globalRefreshToken,
        providerId: id,
        clientId: id ? s.providerDefaults[id]?.clientId : undefined,
        clientSecret: id ? getSessionSecret(id) : undefined,
      },
      s.links?.authProviders ?? [],
    )
    if (refreshed) s.setGlobalAuthToken(refreshed.token, refreshed.expiry, refreshed.refreshToken)
    return refreshed
  })().finally(() => {
    inFlight = null
  })
  return inFlight
}

function send(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const { globalAuthToken, globalProviderId, links } = useGrpcUiStore.getState()
  const auth = buildAuthHeaders(globalAuthToken, globalProviderId, links?.authProviders ?? [])
  const headers = new Headers(init?.headers)
  for (const [name, value] of Object.entries(auth)) headers.set(name, value)
  return fetch(input, { ...init, headers, credentials: init?.credentials ?? 'same-origin' })
}

/**
 * `fetch` for the GraphiQL page: adds the credential chosen in the Authorize panel (an
 * Authorization bearer header, or the API-key header of the selected scheme). The token is read
 * from the in-memory store on every call, so signing in or out takes effect on the next request;
 * nothing is persisted, and a reload signs out. With no token, no header is added.
 *
 * A 401 on a request that carried a renewable token (OAuth2, with an expiry) triggers one silent
 * refresh, shared by concurrent requests, and one retry with the new token. If the refresh is not
 * possible the 401 is returned as is.
 */
export const authFetch: typeof fetch = async (input, init) => {
  const response = await send(input, init)
  if (response.status !== 401 || !useGrpcUiStore.getState().globalAuthToken) return response
  if (typeof init?.body !== 'string' && init?.body != null) return response // body may not be replayable
  const refreshed = await refreshOnce()
  return refreshed ? send(input, init) : response
}
