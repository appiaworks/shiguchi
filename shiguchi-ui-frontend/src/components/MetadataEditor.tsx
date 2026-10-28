import { useState } from 'react'
import { useGrpcUiStore } from '../store/useGrpcUiStore'

export function MetadataEditor() {
  const { metadataEntries, setMetadataEntries } = useGrpcUiStore()
  const [open, setOpen] = useState(false)

  const add = () => {
    setOpen(true)
    setMetadataEntries([...metadataEntries, { key: '', value: '' }])
  }
  const remove = (i: number) =>
    setMetadataEntries(metadataEntries.filter((_, idx) => idx !== i))
  const update = (i: number, field: 'key' | 'value', val: string) => {
    const next = [...metadataEntries]
    next[i] = { ...next[i], [field]: val }
    setMetadataEntries(next)
  }

  const activeCount = metadataEntries.filter((e) => e.key.trim()).length

  return (
    <div className="border-b border-slate-700">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-2 px-3 py-1 hover:bg-slate-800 text-left"
      >
        <span className="text-[9px] text-slate-600 uppercase tracking-wider">Metadata</span>
        {activeCount > 0 && (
          <span className="text-[9px] bg-slate-700 text-slate-300 px-1 rounded">
            {activeCount}
          </span>
        )}
        <span className="ml-auto text-slate-700 text-[9px]">{open ? '▾' : '▸'}</span>
        <button
          onClick={(e) => { e.stopPropagation(); add() }}
          className="text-[10px] text-blue-500 hover:text-blue-400 ml-1"
        >
          + add
        </button>
      </button>

      {open && (
        <div className="px-3 pb-2">
          {metadataEntries.length === 0 ? (
            <span className="text-slate-700 text-[10px] italic">No entries</span>
          ) : (
            <div className="space-y-1">
              {metadataEntries.map((entry, i) => (
                <div key={i} className="flex items-center gap-1">
                  <input
                    type="text"
                    value={entry.key}
                    onChange={(e) => update(i, 'key', e.target.value)}
                    placeholder="key"
                    className="w-28 bg-slate-800 text-slate-300 text-[10px] px-1.5 py-0.5 rounded border border-slate-700 outline-none font-mono placeholder-slate-700"
                  />
                  <span className="text-slate-700 text-[10px]">:</span>
                  <input
                    type="text"
                    value={entry.value}
                    onChange={(e) => update(i, 'value', e.target.value)}
                    placeholder="value"
                    className="flex-1 bg-slate-800 text-slate-300 text-[10px] px-1.5 py-0.5 rounded border border-slate-700 outline-none font-mono placeholder-slate-700"
                  />
                  <button
                    onClick={() => remove(i)}
                    className="text-[10px] text-slate-600 hover:text-red-400 shrink-0"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}