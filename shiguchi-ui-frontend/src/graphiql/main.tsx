import './workers'
import { StrictMode, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { GraphiQL } from 'graphiql'
import { createTransport } from '@graphiql/toolkit'
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
  const transport = useMemo(() => {
    if (!url) return null
    // Subscriptions run over graphql-transport-ws on the same origin. A browser cannot set
    // headers on a WebSocket, so the credential travels in the connection_init payload; it is
    // read when the socket connects, so signing in or out applies to the next subscription.
    const subscriptionClient = wsPath
      ? createClient({
          url: new URL(wsPath, window.location.href.replace(/^http/, 'ws')).toString(),
          lazy: true,
          connectionParams: () => {
            const { globalAuthToken, globalProviderId, links: l } = useGrpcUiStore.getState()
            return buildAuthHeaders(globalAuthToken, globalProviderId, l?.authProviders ?? [])
          },
        })
      : undefined
    // The gateway serves GraphQL over POST only. Naming it skips GraphiQL 6's OPTIONS probe of the
    // endpoint (2 s timeout) and keeps queries off GET and QUERY.
    return createTransport({ url, fetch: authFetch, subscriptionClient, supportedMethods: ['POST'] })
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
      <div className="gql-main">{transport && <GraphiQL transport={transport} />}</div>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Page />
  </StrictMode>,
)
