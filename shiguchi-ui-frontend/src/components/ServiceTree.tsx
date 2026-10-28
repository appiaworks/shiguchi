import { useMemo } from 'react'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { MethodBadge } from './MethodBadge'
import type { GroupBy, ServiceSchema } from '../types/schema'

const GROUP_LABEL: Record<GroupBy, string> = {
  flat: '—',
  configName: 'Svc',
  namespace: 'NS',
  target: 'Host',
}

export function ServiceTree() {
  const {
    services,
    selectedMethod,
    selectedService,
    expandedServices,
    searchText,
    groupBy,
    selectMethod,
    toggleService,
    setGroupBy,
  } = useGrpcUiStore()

  // Filter by search text (must be before any early return to obey hooks rules)
  const filtered = useMemo(() => {
    if (!searchText.trim()) return services
    const q = searchText.toLowerCase()
    return services
      .map((svc) => {
        const svcMatch = svc.name.toLowerCase().includes(q)
        return {
          ...svc,
          methods: svcMatch
            ? svc.methods
            : svc.methods.filter((m) => m.name.toLowerCase().includes(q)),
        }
      })
      .filter((svc) => svc.methods.length > 0 || svc.name.toLowerCase().includes(q))
  }, [services, searchText])

  // Group
  type Group = { label: string | null; services: ServiceSchema[] }
  const groups = useMemo((): Group[] => {
    if (groupBy === 'flat') return [{ label: null, services: filtered }]
    const map = new Map<string, ServiceSchema[]>()
    for (const svc of filtered) {
      const key =
        groupBy === 'namespace'  ? (svc.namespace  || '(unknown)')
        : groupBy === 'target'   ? svc.target
        :                          (svc.configName  || '(unknown)')
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(svc)
    }
    return [...map.entries()].map(([label, svcs]) => ({ label, services: svcs }))
  }, [filtered, groupBy])

  // Auto-expand all matches when searching
  const effectiveExpanded = useMemo((): Set<string> => {
    if (!searchText.trim()) return expandedServices
    return new Set(filtered.map((s) => s.name))
  }, [filtered, searchText, expandedServices])

  if (services.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-slate-500 text-xs">
        Loading schema…
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col">
      {/* Group-by toggle */}
      <div className="flex items-center gap-1 px-2 py-1 border-b border-slate-700 shrink-0">
        <span className="text-[9px] text-slate-700 mr-1">group</span>
        {(['flat', 'configName', 'namespace', 'target'] as const).map((g) => (
          <button
            key={g}
            onClick={() => setGroupBy(g)}
            className={`text-[9px] px-1.5 py-0.5 rounded transition-colors ${
              groupBy === g
                ? 'bg-slate-700 text-slate-200'
                : 'text-slate-600 hover:text-slate-400'
            }`}
          >
            {GROUP_LABEL[g]}
          </button>
        ))}
      </div>

      {/* A real tree, so a screen reader announces "expanded/collapsed", the level, and which
          method is selected, instead of reading a flat list of unlabelled buttons. */}
      <div className="flex-1 min-h-0 overflow-y-auto" role="tree" aria-label="gRPC services and methods">
        {filtered.length === 0 && searchText.trim() ? (
          <div className="flex items-center justify-center h-16 text-slate-600 text-xs">
            No matches
          </div>
        ) : (
          groups.map((group, gi) => (
            <div key={group.label ?? gi}>
              {group.label && (
                <div className="sticky top-0 z-10 px-2 py-0.5 text-[9px] text-slate-600 bg-slate-900 border-b border-slate-800 uppercase tracking-widest">
                  {group.label}
                </div>
              )}
              {group.services.map((svc) => {
                const expanded = effectiveExpanded.has(svc.name)
                const shortName = svc.name.split('.').pop() ?? svc.name

                return (
                  <div key={svc.name} role="none">
                    <button
                      onClick={() => toggleService(svc.name)}
                      role="treeitem"
                      aria-expanded={expanded}
                      aria-level={1}
                      className="w-full flex items-center gap-1 px-2 py-1.5 hover:bg-slate-800 text-left"
                    >
                      <span className="text-slate-400 text-[10px] w-3 shrink-0">
                        {expanded ? '▾' : '▸'}
                      </span>
                      <span
                        className="text-slate-300 font-semibold text-xs truncate"
                        title={svc.name}
                      >
                        {shortName}
                      </span>
                      {(groupBy === 'flat' || groupBy === 'configName' || groupBy === 'namespace') && (
                        <span className="ml-auto text-slate-700 text-[9px] shrink-0 truncate max-w-[6rem]">
                          {svc.target}
                        </span>
                      )}
                    </button>

                    {expanded && (
                      <div className="pl-4" role="group">
                        {svc.methods.map((method) => {
                          const active =
                            selectedService?.name === svc.name &&
                            selectedMethod?.name === method.name
                          return (
                            <button
                              key={method.name}
                              onClick={() => selectMethod(svc, method)}
                              role="treeitem"
                              aria-level={2}
                              aria-selected={active}
                              className={`w-full flex items-center gap-2 px-2 py-1 hover:bg-slate-800 text-left rounded-sm ${
                                active ? 'bg-slate-700 text-white' : 'text-slate-400'
                              }`}
                            >
                              <MethodBadge type={method.methodType} />
                              <span className="truncate text-xs" title={method.name}>
                                {method.name}
                              </span>
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          ))
        )}
      </div>
    </div>
  )
}