import './workers'
import { StrictMode, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { GraphiQL } from 'graphiql'
import { createGraphiQLFetcher } from '@graphiql/toolkit'
import 'graphiql/style.css'
import '../index.css'
import './graphiql.css'
import { fetchConfig } from '../api/grpcUiApi'
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

  const url = links?.graphqlPath
  const fetcher = useMemo(
    () => (url ? createGraphiQLFetcher({ url, fetch: authFetch }) : null),
    [url],
  )

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
