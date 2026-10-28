import { useState } from 'react'
import type { FieldSchema, MessageSchema } from '../types/schema'

function FieldRow({ field, depth }: { field: FieldSchema; depth: number }) {
  const [open, setOpen] = useState(depth < 2)
  const hasChildren = field.fields.length > 0 || field.enumValues.length > 0

  const typeLabel = field.wellKnownType
    ? field.wellKnownType
    : field.typeName
    ? field.typeName.split('.').pop()!
    : field.type

  const labelColor =
    field.label === 'repeated'
      ? 'text-yellow-500'
      : field.label === 'map'
      ? 'text-orange-500'
      : 'text-slate-600'

  return (
    <div style={{ paddingLeft: depth * 12 }}>
      <div className="flex items-center gap-1 py-0.5">
        {hasChildren ? (
          <button
            onClick={() => setOpen(!open)}
            className="text-slate-500 text-[10px] w-3 shrink-0 hover:text-slate-300"
          >
            {open ? '▾' : '▸'}
          </button>
        ) : (
          <span className="w-3 shrink-0" />
        )}
        <span className="text-slate-300 text-xs">{field.jsonName}</span>
        <span className="text-slate-600 text-[10px]">:</span>
        <span className="text-emerald-400 text-[10px]">{typeLabel}</span>
        {field.label !== 'optional' && (
          <span className={`text-[10px] ${labelColor}`}>[{field.label}]</span>
        )}
        {field.oneofName && (
          <span className="text-slate-600 text-[10px]">oneof:{field.oneofName}</span>
        )}
      </div>

      {open && field.fields.length > 0 && (
        <div>
          {field.fields.map((f) => (
            <FieldRow key={f.name} field={f} depth={depth + 1} />
          ))}
        </div>
      )}

      {open && field.enumValues.length > 0 && (
        <div style={{ paddingLeft: (depth + 1) * 12 }}>
          {field.enumValues.map((v) => (
            <div key={v.name} className="flex items-center gap-1 py-0.5">
              <span className="w-3 shrink-0" />
              <span className="text-slate-400 text-[10px]">{v.name}</span>
              <span className="text-slate-600 text-[10px]">= {v.number}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function SchemaDoc({ schema }: { schema: MessageSchema }) {
  return (
    <div className="h-full overflow-y-auto px-2 py-1">
      <div className="text-[10px] text-slate-500 mb-1">{schema.fullName}</div>
      {schema.fields.map((f) => (
        <FieldRow key={f.name} field={f} depth={0} />
      ))}
      {schema.fields.length === 0 && (
        <div className="text-slate-600 text-xs">(empty message)</div>
      )}
    </div>
  )
}