import { buildAuthHeaders } from '../api/grpcUiApi'
import { useGrpcUiStore } from '../store/useGrpcUiStore'

/**
 * `fetch` for the GraphiQL page: adds the credential chosen in the Authorize panel (an
 * Authorization bearer header, or the API-key header of the selected scheme). The token is read
 * from the in-memory store on every call, so signing in or out takes effect on the next request;
 * nothing is persisted, and a reload signs out. With no token, no header is added.
 */
export const authFetch: typeof fetch = (input, init) => {
  const { globalAuthToken, globalProviderId, links } = useGrpcUiStore.getState()
  const auth = buildAuthHeaders(globalAuthToken, globalProviderId, links?.authProviders ?? [])
  const headers = new Headers(init?.headers)
  for (const [name, value] of Object.entries(auth)) headers.set(name, value)
  return fetch(input, { ...init, headers, credentials: init?.credentials ?? 'same-origin' })
}
