import './workers'
import { StrictMode, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { GraphiQL } from 'graphiql'
import { createGraphiQLFetcher } from '@graphiql/toolkit'
import { createClient } from 'graphql-ws'
import 'graphiql/style.css'
import '../index.css'
import './graphiql.css'
import { buildAuthHeaders, fetchConfig } from '../api/grpcUiApi'
import { GlobalAuthPanel } from '../components/GlobalAuthPanel'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { authFetch } from './authFetch'

function Page() {
  const links = useGrpcUiStore((s) => s.links)
  const hasToken = useGrpcUiStore((s) => !!s.globalAuthToken)
  const setLinks = useGrpcUiStore((s) => s.setLinks)
  const [panelOpen, setPanelOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchConfig().then(setLinks).catch((e: unknown) => setError(String(e)))
  }, [setLinks])

  const url = links?.graphqlEndpoint
  const wsPath = links?.graphqlWebSocketPath
  const fetcher = useMemo(() => {
    if (!url) return null
    // Subscriptions run over graphql-transport-ws on the same origin. A browser cannot set
    // headers on a WebSocket, so the credential travels in the connection_init payload; it is
    // read when the socket connects, so signing in or out applies to the next subscription.
    const wsClient = wsPath
      ? createClient({
          url: new URL(wsPath, window.location.href.replace(/^http/, 'ws')).toString(),
          lazy: true,
          connectionParams: () => {
            const { globalAuthToken, globalProviderId, links: l } = useGrpcUiStore.getState()
            return buildAuthHeaders(globalAuthToken, globalProviderId, l?.authProviders ?? [])
          },
        })
      : undefined
    return createGraphiQLFetcher({ url, fetch: authFetch, wsClient })
  }, [url, wsPath])

  return (
    <div className="gql-page">
      <div className="gql-bar">
        <span>Shiguchi GraphiQL</span>
        <button type="button" onClick={() => setPanelOpen((o) => !o)}>
          {hasToken ? 'Authorized ✓' : 'Authorize'}
        </button>
      </div>
      {panelOpen && <GlobalAuthPanel onClose={() => setPanelOpen(false)} />}
      {error && <div role="alert">Could not load the gateway config: {error}</div>}
      <div className="gql-main">{fetcher && <GraphiQL fetcher={fetcher} />}</div>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Page />
  </StrictMode>,
)
