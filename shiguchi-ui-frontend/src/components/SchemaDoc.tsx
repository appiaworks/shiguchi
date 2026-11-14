import { useEffect, useState } from 'react'
import type { FieldSchema, MessageSchema } from '../types/schema'

/** Bump `n` to make every row apply `open`, whatever the user toggled before. */
interface BulkToggle {
  open: boolean
  n: number
}

const REQUIRED_TIP =
  'Marked required by annotation (google.api.field_behavior / buf.validate). Proto3 itself has no required fields.'

const WELL_KNOWN_TIPS: Record<string, string> = {
  TIMESTAMP: 'google.protobuf.Timestamp: an RFC 3339 string, e.g. "2026-11-14T10:00:00Z"',
  DURATION: 'google.protobuf.Duration: seconds with an "s" suffix, e.g. "3.5s"',
  FIELD_MASK: 'google.protobuf.FieldMask: a comma-separated string of field paths',
  STRUCT: 'google.protobuf.Struct: any JSON object',
  VALUE: 'google.protobuf.Value: any JSON value',
  LIST_VALUE: 'google.protobuf.ListValue: a JSON array',
  ANY: 'google.protobuf.Any: an object carrying an "@type" key plus that type\'s fields',
  EMPTY: 'google.protobuf.Empty: an empty object {}',
}

function wellKnownTip(wk: string): string {
  if (WELL_KNOWN_TIPS[wk]) return WELL_KNOWN_TIPS[wk]
  if (wk.endsWith('_VALUE')) return `Wrapper type (${wk}): a plain JSON value, or null for unset`
  return `Well-known type ${wk}: written as a single JSON value, not an object`
}

function anyExpandable(fields: FieldSchema[]): boolean {
  return fields.some((f) => f.fields.length > 0 || f.enumValues.length > 0)
}

function FieldRow({
  field,
  depth,
  parentPath,
  bulk,
}: {
  field: FieldSchema
  depth: number
  parentPath: string
  bulk: BulkToggle | null
}) {
  const [open, setOpen] = useState(depth < 2)
  // enum values are noise until asked for: always start collapsed, whatever the depth
  const [enumOpen, setEnumOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const hasEnum = field.enumValues.length > 0
  const hasChildren = field.fields.length > 0

  useEffect(() => {
    if (!bulk) return
    setOpen(bulk.open)
    setEnumOpen(bulk.open)
  }, [bulk])

  // proto3 `optional` keyword: the field tracks presence, so unset differs from the default value
  const explicitOptional =
    field.hasPresence === true &&
    field.label === 'optional' &&
    field.fields.length === 0 &&
    !field.wellKnownType

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

  const labelTip =
    field.label === 'repeated'
      ? 'repeated: a JSON array of this type'
      : field.label === 'map'
      ? 'map: a JSON object with string keys; each value has the type shown below'
      : ''

  const typeTip = field.wellKnownType
    ? wellKnownTip(field.wellKnownType)
    : field.typeName
    ? `${field.type}: ${field.typeName}`
    : field.type

  const path = parentPath ? `${parentPath}.${field.jsonName}` : field.jsonName
  const childPrefix = field.label === 'repeated' ? `${path}[]` : path

  const copyPath = async () => {
    try {
      await navigator.clipboard.writeText(path)
      setCopied(true)
      setTimeout(() => setCopied(false), 1200)
    } catch {
      /* clipboard unavailable (insecure context or denied): nothing to do */
    }
  }

  return (
    <div style={{ paddingLeft: depth * 12 }}>
      <div className="group flex items-center gap-1 py-0.5">
        {hasChildren ? (
          <button
            onClick={() => setOpen(!open)}
            className="text-slate-500 text-[14px] w-3 shrink-0 hover:text-slate-300"
          >
            {open ? '▾' : '▸'}
          </button>
        ) : (
          <span className="w-3 shrink-0" />
        )}
        <span className="text-slate-300 text-xs">{field.jsonName}</span>
        <span className="text-slate-600 text-[14px]">:</span>
        <span className="text-emerald-400 text-[14px]" title={typeTip}>
          {typeLabel}
        </span>
        {field.required && (
          <span className="text-red-500 text-[14px] font-bold" title={REQUIRED_TIP}>
            !
          </span>
        )}
        {hasEnum && (
          <button
            onClick={() => setEnumOpen(!enumOpen)}
            title={enumOpen ? 'Hide enum values' : 'Show enum values'}
            className="text-slate-500 text-[13px] hover:text-slate-300"
          >
            {enumOpen ? '▾' : '▸'} enum ({field.enumValues.length})
          </button>
        )}
        {explicitOptional && (
          <span
            className="text-sky-500 text-[13px]"
            title="proto3 optional: leaving it out differs from sending the default value"
          >
            optional
          </span>
        )}
        {field.label !== 'optional' && (
          <span className={`text-[14px] ${labelColor}`} title={labelTip}>
            [{field.label}]
          </span>
        )}
        {field.oneofName && (
          <span
            className="text-slate-600 text-[14px]"
            title={`oneof ${field.oneofName}: at most one field of this group may be set`}
          >
            oneof:{field.oneofName}
          </span>
        )}
        <button
          onClick={copyPath}
          aria-label={`Copy path ${path}`}
          title={copied ? 'Copied' : `Copy path ${path}`}
          className="ml-1 text-slate-400 text-[12px] opacity-0 group-hover:opacity-100 focus:opacity-100 hover:text-slate-300"
        >
          {copied ? '✓' : 'copy'}
        </button>
      </div>

      {open && field.fields.length > 0 && (
        <div>
          {field.fields.map((f) => (
            <FieldRow key={f.name} field={f} depth={depth + 1} parentPath={childPrefix} bulk={bulk} />
          ))}
        </div>
      )}

      {enumOpen && hasEnum && (
        <div style={{ paddingLeft: (depth + 1) * 12 }}>
          {field.enumValues.map((v) => (
            <div key={v.name} className="flex items-center gap-1 py-0.5">
              <span className="w-3 shrink-0" />
              <span className="text-slate-400 text-[14px]">{v.name}</span>
              <span className="text-slate-600 text-[14px]">= {v.number}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function SchemaDoc({ schema }: { schema: MessageSchema }) {
  const [bulk, setBulk] = useState<BulkToggle | null>(null)
  const toggleAll = (open: boolean) => setBulk((b) => ({ open, n: (b?.n ?? 0) + 1 }))

  return (
    <div className="h-full overflow-y-auto px-2 py-1">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 mb-1">
        <span className="min-w-0 break-all text-[14px] text-slate-500">{schema.fullName}</span>
        {anyExpandable(schema.fields) && (
          <span className="ml-auto flex shrink-0 gap-2 whitespace-nowrap text-[13px]">
            <button onClick={() => toggleAll(true)} className="text-slate-500 hover:text-slate-300">
              Expand all
            </button>
            <button onClick={() => toggleAll(false)} className="text-slate-500 hover:text-slate-300">
              Collapse all
            </button>
          </span>
        )}
      </div>
      {schema.fields.map((f) => (
        <FieldRow key={f.name} field={f} depth={0} parentPath="" bulk={bulk} />
      ))}
      {schema.fields.length === 0 && (
        <div className="text-slate-600 text-xs">(empty message)</div>
      )}
    </div>
  )
}
