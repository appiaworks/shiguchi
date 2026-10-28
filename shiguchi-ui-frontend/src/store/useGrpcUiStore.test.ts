import { beforeEach, describe, expect, it } from 'vitest'
import { useGrpcUiStore, type HistoryEntry } from './useGrpcUiStore'
import type { MethodSchema, ServiceSchema } from '../types/schema'

const method = (name: string): MethodSchema => ({
  name,
  fullServiceName: 'test.Catalog',
  methodType: 'UNARY',
  inputSchema: { fullName: 'test.Req', fields: [] },
  outputTypeName: 'Res',
})

const service = (name: string, methods: MethodSchema[]): ServiceSchema => ({
  name,
  target: 'localhost:9090',
  methods,
  namespace: 'test',
  configName: 'backend',
})

const CATALOG = service('Catalog', [method('GetItem'), method('ListItems')])

const entry = (over: Partial<HistoryEntry> = {}): HistoryEntry => ({
  id: 'h1',
  serviceName: 'Catalog',
  methodName: 'GetItem',
  configName: 'backend',
  payload: '{"id":"1"}',
  metadataEntries: [{ key: 'x-tenant', value: 'acme' }],
  timestamp: 1,
  grpcStatus: 'OK',
  ...over,
})

/** The store is a singleton, so each test starts from a known state. */
function reset() {
  useGrpcUiStore.setState({
    services: [],
    selectedService: null,
    selectedMethod: null,
    payload: '',
    extraPayloads: [],
    metadataEntries: [],
    history: [],
    expandedServices: new Set<string>(),
  })
}

describe('useGrpcUiStore', () => {
  beforeEach(reset)

  describe('selecting a method', () => {
    it('clears the previous response, so a new method never shows the last one’s output', () => {
      useGrpcUiStore.getState().setResult({ status: 'success', response: '{"old":true}' })

      useGrpcUiStore.getState().selectMethod(CATALOG, CATALOG.methods[0])

      const s = useGrpcUiStore.getState()
      expect(s.selectedMethod?.name).toBe('GetItem')
      expect(s.result.status).toBe('idle')
      expect(s.result.response).toBeNull()
    })

    it('resets the payload to an empty object rather than keeping the previous method’s body', () => {
      useGrpcUiStore.getState().setPayload('{"from":"the previous method"}')

      useGrpcUiStore.getState().selectMethod(CATALOG, CATALOG.methods[1])

      expect(useGrpcUiStore.getState().payload).not.toContain('previous method')
    })
  })

  describe('client-streaming payloads', () => {
    it('adds and removes extra messages by index, leaving the first message alone', () => {
      const store = useGrpcUiStore.getState()
      store.setPayload('{"first":true}')
      store.addExtraPayload()
      store.addExtraPayload()
      useGrpcUiStore.getState().setExtraPayloads(['{"second":true}', '{"third":true}'])

      useGrpcUiStore.getState().removeExtraPayload(0)

      const s = useGrpcUiStore.getState()
      expect(s.payload).toBe('{"first":true}')
      expect(s.extraPayloads).toEqual(['{"third":true}'])
    })
  })

  describe('history', () => {
    it('keeps the newest entry first', () => {
      useGrpcUiStore.getState().addHistoryEntry(entry({ id: 'older' }))
      useGrpcUiStore.getState().addHistoryEntry(entry({ id: 'newer' }))

      expect(useGrpcUiStore.getState().history.map((h) => h.id)).toEqual(['newer', 'older'])
    })

    it('caps at 20 entries, so a long session cannot grow localStorage without bound', () => {
      for (let i = 0; i < 25; i++) {
        useGrpcUiStore.getState().addHistoryEntry(entry({ id: `h${i}` }))
      }

      const history = useGrpcUiStore.getState().history
      expect(history).toHaveLength(20)
      expect(history[0].id).toBe('h24')
      expect(history[history.length - 1].id).toBe('h5')
    })

    it('persists to localStorage, which is what makes it survive a reload', () => {
      useGrpcUiStore.getState().addHistoryEntry(entry({ id: 'persisted' }))

      const stored = JSON.parse(localStorage.getItem('grpcui-history') ?? '[]') as HistoryEntry[]
      expect(stored.map((h) => h.id)).toEqual(['persisted'])
    })

    it('restores the request that produced an entry', () => {
      useGrpcUiStore.setState({ services: [CATALOG] })

      useGrpcUiStore.getState().restoreHistory(entry())

      const s = useGrpcUiStore.getState()
      expect(s.selectedMethod?.name).toBe('GetItem')
      expect(s.payload).toBe('{"id":"1"}')
      expect(s.metadataEntries).toEqual([{ key: 'x-tenant', value: 'acme' }])
      expect(s.requestMode).toBe('json')
    })

    it('ignores an entry whose method no longer exists, rather than selecting nothing', () => {
      // The backend's schema can change between sessions while history persists, so a stale
      // entry must not clear a valid selection.
      useGrpcUiStore.setState({ services: [CATALOG] })
      useGrpcUiStore.getState().selectMethod(CATALOG, CATALOG.methods[1])

      useGrpcUiStore.getState().restoreHistory(entry({ methodName: 'MethodThatWasRemoved' }))

      expect(useGrpcUiStore.getState().selectedMethod?.name).toBe('ListItems')
    })
  })

  describe('service tree', () => {
    it('toggles a service open and closed again', () => {
      useGrpcUiStore.getState().toggleService('Catalog')
      expect(useGrpcUiStore.getState().expandedServices.has('Catalog')).toBe(true)

      useGrpcUiStore.getState().toggleService('Catalog')
      expect(useGrpcUiStore.getState().expandedServices.has('Catalog')).toBe(false)
    })

    it('replaces the set rather than mutating it, so subscribers actually re-render', () => {
      const before = useGrpcUiStore.getState().expandedServices

      useGrpcUiStore.getState().toggleService('Catalog')

      expect(useGrpcUiStore.getState().expandedServices).not.toBe(before)
    })
  })

  describe('streaming results', () => {
    it('appends chunks in arrival order', () => {
      useGrpcUiStore.getState().setResult({ status: 'streaming', streamChunks: [] })

      useGrpcUiStore.getState().appendChunk('{"n":1}')
      useGrpcUiStore.getState().appendChunk('{"n":2}')

      expect(useGrpcUiStore.getState().result.streamChunks).toEqual(['{"n":1}', '{"n":2}'])
    })
  })
})
