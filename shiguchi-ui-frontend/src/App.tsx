import { useEffect, useRef, useState } from 'react'
import { Panel, Group, Separator } from 'react-resizable-panels'
import { fromHash, toHash } from './api/deepLink'
import { fetchConfig, fetchSchema } from './api/grpcUiApi'
import { GlobalAuthPanel } from './components/GlobalAuthPanel'
import { InvokePanel } from './components/InvokePanel'
import { PushPanel } from './components/PushPanel'
import { ServiceTree } from './components/ServiceTree'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import { useTokenAutoRefresh } from './hooks/useTokenAutoRefresh'
import { getSessionSecret } from './store/sessionSecrets'
import { useGrpcUiStore } from './store/useGrpcUiStore'

export default function App() {
  const { setServices, setLinks, searchText, setSearchText, setGlobalAuthToken, setGlobalAuthDialogOpen } = useGrpcUiStore()
  const links = useGrpcUiStore((s) => s.links)
  const globalAuthToken = useGrpcUiStore((s) => s.globalAuthToken)
  const globalTokenExpiry = useGrpcUiStore((s) => s.globalTokenExpiry)
  const globalRefreshToken = useGrpcUiStore((s) => s.globalRefreshToken)
  const globalProviderId = useGrpcUiStore((s) => s.globalProviderId)
  const providerDefaults = useGrpcUiStore((s) => s.providerDefaults)
  const globalAuthDialogOpen = useGrpcUiStore((s) => s.globalAuthDialogOpen)

  const services = useGrpcUiStore((s) => s.services)
  const selectedService = useGrpcUiStore((s) => s.selectedService)
  const selectedMethod = useGrpcUiStore((s) => s.selectedMethod)

  const [showAuth, setShowAuth] = useState(false)
  const [view, setView] = useState<'console' | 'push'>('console')
  const searchInputRef = useRef<HTMLInputElement>(null)

  // Sync store-driven open request (e.g. from auto-refresh hook in AuthBar)
  useEffect(() => {
    if (globalAuthDialogOpen) {
      setShowAuth(true)
      setGlobalAuthDialogOpen(false)
    }
  }, [globalAuthDialogOpen, setGlobalAuthDialogOpen])

  const authProviders = links?.authProviders ?? []

  useTokenAutoRefresh(
    {
      token: globalAuthToken,
      expiry: globalTokenExpiry,
      refreshToken: globalRefreshToken,
      providerId: globalProviderId,
      clientId: globalProviderId ? (providerDefaults[globalProviderId]?.clientId) : undefined,
      clientSecret: globalProviderId ? getSessionSecret(globalProviderId) : undefined,
    },
    {
      setToken: (token, expiry, rt) => setGlobalAuthToken(token, expiry, rt),
      openAuthDialog: () => setGlobalAuthDialogOpen(true),
    },
    authProviders,
  )

  useEffect(() => {
    fetchSchema()
      .then(setServices)
      .catch((e: unknown) => console.error('Failed to load schema:', e))
    fetchConfig()
      .then(setLinks)
      .catch((e: unknown) => console.error('Failed to load config:', e))
  }, [setServices, setLinks])

  // Open whatever the URL points at, once the schema it refers to has arrived. Also follows the
  // back button, so a shared link and normal browser navigation behave the same way.
  useEffect(() => {
    function openFromHash() {
      const ref = fromHash(window.location.hash)
      if (!ref) return
      // Read `services` from the closure rather than the store, so the dependency below is a
      // real one: the effect has to run again when the schema arrives, because a link opened on
      // a cold load refers to a method that does not exist yet.
      const service = services.find((s) => s.name === ref.serviceName)
      const method = service?.methods.find((m) => m.name === ref.methodName)
      // A link can outlive the method it names: ignore it rather than clearing the selection.
      if (service && method) useGrpcUiStore.getState().selectMethod(service, method)
    }

    openFromHash()
    window.addEventListener('hashchange', openFromHash)
    return () => window.removeEventListener('hashchange', openFromHash)
  }, [services])

  // Keep the URL in step with the selection, so the address bar is always shareable. replaceState
  // rather than pushState: clicking through a service tree should not build a back-button history
  // of every method you glanced at.
  useEffect(() => {
    if (!selectedService || !selectedMethod) return
    const hash = toHash({ serviceName: selectedService.name, methodName: selectedMethod.name })
    if (window.location.hash !== hash) {
      window.history.replaceState(null, '', hash)
    }
  }, [selectedService, selectedMethod])

  useKeyboardShortcuts({
    onFocusSearch: () => searchInputRef.current?.focus(),
    onEscape: () => setShowAuth(false),
  })

  const now = Date.now()
  const globalSecondsLeft = globalTokenExpiry
    ? Math.max(0, Math.round((globalTokenExpiry - now) / 1000))
    : null
  const globalExpired = globalTokenExpiry != null && globalTokenExpiry <= now

  const globalProvider = globalProviderId
    ? (links?.authProviders ?? []).find((p) => p.id === globalProviderId)
    : null

  const authLabel = globalProvider
    ? (globalProvider.description ?? globalProviderId)
    : globalAuthToken
      ? 'manual'
      : null

  return (
    <div className="h-screen flex flex-col bg-[#0f1117]">
      {/* Top bar */}
      <header className="relative flex items-center gap-3 px-4 py-2 border-b border-slate-700 shrink-0 bg-slate-900">
        <span className="text-blue-400 font-bold text-sm">gRPC UI</span>
        <span className="text-slate-600 text-xs">Shiguchi</span>
        {links?.grpcProxyEnabled && (
          <span className="text-[9px] text-yellow-600 border border-yellow-800 rounded px-1.5 py-0.5">
            proxy :{links.grpcProxyPort}
          </span>
        )}
        <div className="flex-1" />

        {/* Global auth button */}
        <button
          onClick={() => setShowAuth((v) => !v)}
          className={`flex items-center gap-1.5 text-xs px-2 py-0.5 rounded border transition-colors ${
            showAuth
              ? 'border-blue-600 text-blue-300 bg-blue-900/20'
              : globalAuthToken
                ? 'border-slate-600 text-slate-300 hover:border-slate-500'
                : 'border-slate-700 text-slate-500 hover:text-slate-400 hover:border-slate-600'
          }`}
        >
          <span>Auth</span>
          {authLabel && (
            <span className="text-[10px] text-slate-400 max-w-[8rem] truncate">{authLabel}</span>
          )}
          {globalAuthToken && (
            <span
              className={`text-[10px] ${
                globalExpired ? 'text-red-400' : globalSecondsLeft !== null && globalSecondsLeft < 60 ? 'text-yellow-400' : 'text-green-500'
              }`}
            >
              {globalExpired ? '⚠' : '✓'}
            </span>
          )}
        </button>

        {links?.pushEnabled && (
          <button
            onClick={() => setView((v) => (v === 'push' ? 'console' : 'push'))}
            className={`text-xs px-2 py-0.5 rounded border transition-colors ${
              view === 'push'
                ? 'border-purple-600 text-purple-300 bg-purple-900/20'
                : 'border-slate-700 text-slate-400 hover:text-purple-300 hover:border-slate-600'
            }`}
          >
            Push
          </button>
        )}

        {links?.swaggerEnabled && (
          <a
            href={links.swaggerPath}
            target="_blank"
            rel="noreferrer"
            className="text-xs text-slate-400 hover:text-green-400 transition-colors"
          >
            Swagger UI ↗
          </a>
        )}
        {links?.graphqlEnabled && (
          <a
            href={links.graphqlPath}
            target="_blank"
            rel="noreferrer"
            className="text-xs text-slate-400 hover:text-pink-400 transition-colors"
          >
            GraphQL ↗
          </a>
        )}

        {showAuth && <GlobalAuthPanel onClose={() => setShowAuth(false)} />}
      </header>

      {/* Main split, or the push admin views */}
      <div className="flex-1 min-h-0">
        {view === 'push' && links?.pushEnabled ? (
          <PushPanel adminPath={links.pushAdminPath ?? '/actuator/gateway-push'} pushPath={links.pushPath ?? '/push'} />
        ) : (
        <Group orientation="horizontal" className="h-full">
          <Panel defaultSize={22} minSize={15} maxSize={40}>
            <div className="h-full flex flex-col border-r border-slate-700">
              {/* Left panel header: title + search */}
              <div className="px-2 py-1.5 border-b border-slate-700 shrink-0 space-y-1.5">
                <div className="flex items-center">
                  <span className="text-[10px] text-slate-600 uppercase tracking-wider">Services</span>
                </div>
                <div className="relative">
                  <input
                    ref={searchInputRef}
                    type="search"
                    value={searchText}
                    onChange={(e) => setSearchText(e.target.value)}
                    placeholder="Filter services / methods…"
                    aria-label="Filter services and methods"
                    aria-keyshortcuts="Control+K Meta+K"
                    className="w-full bg-slate-800 text-slate-300 text-xs px-2 py-1 rounded outline-none placeholder-slate-700 border border-slate-700 focus:border-slate-500"
                  />
                  {searchText && (
                    <button
                      onClick={() => setSearchText('')}
                      aria-label="Clear the filter"
                      className="absolute right-1.5 top-1/2 -translate-y-1/2 text-slate-600 hover:text-slate-300 text-xs"
                    >
                      ×
                    </button>
                  )}
                </div>
              </div>
              <div className="flex-1 min-h-0">
                <ServiceTree />
              </div>
            </div>
          </Panel>

          <Separator className="w-px bg-slate-700 hover:bg-blue-600 transition-colors" />

          <Panel defaultSize={78}>
            <InvokePanel />
          </Panel>
        </Group>
        )}
      </div>
    </div>
  )
}
