import { create } from 'zustand'
import type { GatewayUiLinks, GroupBy, MethodSchema, RequestMode, ServiceSchema } from '../types/schema'

export type InvokeStatus = 'idle' | 'loading' | 'success' | 'error' | 'streaming'
export type ResponseTab = 'pretty' | 'raw'

export interface MetadataEntry {
  key: string
  value: string
}

export interface HistoryEntry {
  id: string
  serviceName: string
  methodName: string
  configName: string
  payload: string
  metadataEntries: MetadataEntry[]
  timestamp: number
  grpcStatus: string | null
}

export interface InvokeResult {
  status: InvokeStatus
  response: string | null
  grpcStatus: string | null
  errorMessage: string | null
  /** google.rpc.Status details of a failed call (BadRequest field violations, RetryInfo, ...) */
  errorDetails: unknown[] | null
  durationMs: number | null
  streamChunks: string[]
}

export interface ProviderDefaults {
  clientId?: string
}

interface GrpcUiState {
  services: ServiceSchema[]
  selectedService: ServiceSchema | null
  selectedMethod: MethodSchema | null
  links: GatewayUiLinks | null

  // Global auth — applies to all calls unless overridden per-call
  globalAuthToken: string
  globalTokenExpiry: number | null
  globalProviderId: string | null
  globalRefreshToken: string | null
  providerDefaults: Record<string, ProviderDefaults>

  // Per-call auth override; null = inherit global
  authToken: string
  tokenExpiry: number | null
  activeProviderId: string | null
  refreshToken: string | null

  payload: string
  /**
   * The extra request messages of a client-streaming call. `payload` is the first message; these
   * are the ones after it, so a unary method never has to think about this.
   */
  extraPayloads: string[]
  formValues: Record<string, unknown>
  requestMode: RequestMode
  metadataEntries: MetadataEntry[]
  result: InvokeResult
  responseTab: ResponseTab
  expandedServices: Set<string>
  searchText: string
  groupBy: GroupBy
  history: HistoryEntry[]

  setServices: (services: ServiceSchema[]) => void
  setLinks: (links: GatewayUiLinks) => void
  selectMethod: (service: ServiceSchema, method: MethodSchema) => void

  setGlobalAuthToken: (token: string, expiry?: number | null, refreshToken?: string | null) => void
  setGlobalProviderId: (id: string | null) => void
  setProviderDefault: (providerId: string, defaults: ProviderDefaults) => void

  setAuthToken: (token: string, expiry?: number | null, refreshToken?: string | null) => void
  setActiveProviderId: (id: string | null) => void

  setPayload: (payload: string) => void
  setExtraPayloads: (payloads: string[]) => void
  addExtraPayload: () => void
  removeExtraPayload: (index: number) => void
  setFormValues: (values: Record<string, unknown>) => void
  setRequestMode: (mode: RequestMode) => void
  setMetadataEntries: (entries: MetadataEntry[]) => void
  setResult: (result: Partial<InvokeResult>) => void
  appendChunk: (chunk: string) => void
  setResponseTab: (tab: ResponseTab) => void
  toggleService: (serviceName: string) => void
  setSearchText: (text: string) => void
  setGroupBy: (groupBy: GroupBy) => void
  addHistoryEntry: (entry: HistoryEntry) => void
  restoreHistory: (entry: HistoryEntry) => void

  // Auth dialog open state — used by useTokenAutoRefresh to trigger global auth panel
  globalAuthDialogOpen: boolean
  setGlobalAuthDialogOpen: (open: boolean) => void
}

const IDLE_RESULT: InvokeResult = {
  status: 'idle',
  response: null,
  grpcStatus: null,
  errorMessage: null,
  errorDetails: null,
  durationMs: null,
  streamChunks: [],
}

function loadHistory(): HistoryEntry[] {
  try {
    return JSON.parse(localStorage.getItem('grpcui-history') ?? '[]') as HistoryEntry[]
  } catch {
    return []
  }
}

function loadProviderDefaults(): Record<string, ProviderDefaults> {
  try {
    return JSON.parse(localStorage.getItem('grpcui-provider-defaults') ?? '{}') as Record<string, ProviderDefaults>
  } catch {
    return {}
  }
}

export const useGrpcUiStore = create<GrpcUiState>((set) => ({
  services: [],
  selectedService: null,
  selectedMethod: null,
  links: null,

  globalAuthToken: '',
  globalTokenExpiry: null,
  globalProviderId: null,
  globalRefreshToken: null,
  providerDefaults: loadProviderDefaults(),

  authToken: '',
  tokenExpiry: null,
  activeProviderId: null,
  refreshToken: null,

  payload: '{\n  \n}',
  extraPayloads: [],
  formValues: {},
  requestMode: 'json',
  metadataEntries: [],
  result: IDLE_RESULT,
  responseTab: 'pretty',
  expandedServices: new Set(),
  searchText: '',
  groupBy: 'configName',
  history: loadHistory(),

  setServices: (services) =>
    set({ services, expandedServices: new Set(services.map((s) => s.name)) }),

  setLinks: (links) => set({ links }),

  selectMethod: (service, method) =>
    set({
      selectedService: service,
      selectedMethod: method,
      payload: '{\n  \n}',
      formValues: {},
      result: IDLE_RESULT,
    }),

  setGlobalAuthToken: (globalAuthToken, expiry = null, refreshToken = null) =>
    set({ globalAuthToken, globalTokenExpiry: expiry, globalRefreshToken: refreshToken }),
  setGlobalProviderId: (id) => set({ globalProviderId: id }),
  setProviderDefault: (providerId, defaults) =>
    set((s) => {
      const providerDefaults = { ...s.providerDefaults, [providerId]: defaults }
      try { localStorage.setItem('grpcui-provider-defaults', JSON.stringify(providerDefaults)) } catch { /* storage full */ }
      return { providerDefaults }
    }),

  setAuthToken: (authToken, expiry = null, refreshToken = null) => set({ authToken, tokenExpiry: expiry, refreshToken }),
  setActiveProviderId: (id) => set({ activeProviderId: id }),
  setPayload: (payload) => set({ payload }),
  setExtraPayloads: (extraPayloads) => set({ extraPayloads }),
  addExtraPayload: () => set((s) => ({ extraPayloads: [...s.extraPayloads, '{\n  \n}'] })),
  removeExtraPayload: (index) =>
    set((s) => ({ extraPayloads: s.extraPayloads.filter((_, i) => i !== index) })),
  setFormValues: (formValues) => set({ formValues }),
  setRequestMode: (requestMode) => set({ requestMode }),
  setMetadataEntries: (metadataEntries) => set({ metadataEntries }),

  setResult: (partial) =>
    set((s) => ({ result: { ...s.result, ...partial } })),

  appendChunk: (chunk) =>
    set((s) => ({
      result: { ...s.result, streamChunks: [...s.result.streamChunks, chunk] },
    })),

  setResponseTab: (responseTab) => set({ responseTab }),

  toggleService: (name) =>
    set((s) => {
      const next = new Set(s.expandedServices)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return { expandedServices: next }
    }),

  setSearchText: (searchText) => set({ searchText }),
  setGroupBy: (groupBy) => set({ groupBy }),

  globalAuthDialogOpen: false,
  setGlobalAuthDialogOpen: (open) => set({ globalAuthDialogOpen: open }),

  addHistoryEntry: (entry) =>
    set((s) => {
      const history = [entry, ...s.history].slice(0, 20)
      try { localStorage.setItem('grpcui-history', JSON.stringify(history)) } catch { /* storage full */ }
      return { history }
    }),

  restoreHistory: (entry) =>
    set((s) => {
      const svc = s.services.find((sv) => sv.name === entry.serviceName)
      const method = svc?.methods.find((m) => m.name === entry.methodName)
      if (!svc || !method) return {}
      return {
        selectedService: svc,
        selectedMethod: method,
        payload: entry.payload,
        metadataEntries: entry.metadataEntries,
        requestMode: 'json' as RequestMode,
        formValues: {},
        result: IDLE_RESULT,
      }
    }),
}))