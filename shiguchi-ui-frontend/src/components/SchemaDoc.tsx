import { useEffect, useState } from 'react'
import type { FieldSchema, MessageSchema } from '../types/schema'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { findTypeFields } from '../lib/findType'

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

/** Keeps required fields and the messages that contain one, for the "Required only" view. */
function keepRequired(f: FieldSchema): boolean {
  return f.required === true || f.fields.some(keepRequired)
}

const SOURCE_TIPS: Record<string, string> = {
  annotation: 'Declared by (shiguchi.field).resolves_type / resolves_many in the proto',
  extension: 'Declared by an x-shiguchi extension in the OpenAPI document',
  yaml: 'Declared in the gateway YAML (relationships:)',
  bean: 'Declared by a RelationshipSource bean in the application',
}

/** What one badge points at: a relationship, or a bare resolves_type from an older backend. */
interface RefBadge {
  target: string
  many: boolean
  fieldName?: string | null
  groupBy?: string
  source?: string
}

function refBadges(f: FieldSchema): RefBadge[] {
  if (f.relationships && f.relationships.length > 0) {
    return f.relationships.map((r) => ({
      target: r.target,
      many: r.many,
      fieldName: r.fieldName,
      groupBy: r.groupBy,
      source: r.source,
    }))
  }
  return (f.resolvesTypes ?? []).map((t) => ({ target: t, many: false }))
}

function refTitle(r: RefBadge, isOpen: boolean): string {
  const what = r.many
    ? `This ID has many ${r.target}${r.groupBy ? `, grouped by ${r.groupBy}` : ''}.`
    : `This ID points to ${r.target}.`
  const via = r.fieldName ? ` Resolved by the ${r.fieldName} field.` : ''
  return `${what}${via} Click to ${isOpen ? 'hide' : 'show'} its fields.`
}

function anyExpandable(fields: FieldSchema[]): boolean {
  return fields.some((f) => f.fields.length > 0 || f.enumValues.length > 0)
}

function FieldRow({
  field,
  depth,
  parentPath,
  bulk,
  requiredOnly,
}: {
  field: FieldSchema
  depth: number
  parentPath: string
  bulk: BulkToggle | null
  requiredOnly: boolean
}) {
  const services = useGrpcUiStore((s) => s.services)
  const [openRef, setOpenRef] = useState<string | null>(null)
  const refFields = openRef ? findTypeFields(services, openRef) : null
  const [open, setOpen] = useState(depth < 2)
  // enum values are noise until asked for: always start collapsed, whatever the depth
  const [enumOpen, setEnumOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const hasEnum = field.enumValues.length > 0
  const refs = refBadges(field)
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

  const baseTip = field.wellKnownType
    ? wellKnownTip(field.wellKnownType)
    : field.typeName
    ? `${field.type}: ${field.typeName}`
    : field.type
  const typeTip = explicitOptional
    ? `${baseTip} (proto3 optional: leaving it out differs from sending the default value)`
    : baseTip

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
      <div
        className={`group flex items-center gap-1 py-0.5 ${
          field.required ? 'border-l-2 border-red-500 bg-red-500/5 pl-1' : ''
        }`}
      >
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
        <span className={field.required ? 'text-red-200 text-xs font-semibold' : 'text-slate-300 text-xs'}>
          {field.jsonName}
        </span>
        <span className="text-slate-600 text-[14px]">:</span>
        <span className="text-emerald-400 text-[14px]" title={typeTip}>
          {typeLabel}
        </span>
        {field.required && (
          <span
            className="rounded bg-red-500/20 px-1 text-red-400 text-[11px] font-semibold uppercase tracking-wide"
            title={REQUIRED_TIP}
          >
            required
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
        {refs.map((r) => (
          <span key={`${r.fieldName ?? ''}:${r.target}`} className="inline-flex items-center gap-0.5">
            <button
              onClick={() => setOpenRef(openRef === r.target ? null : r.target)}
              aria-expanded={openRef === r.target}
              title={refTitle(r, openRef === r.target)}
              className="rounded bg-sky-500/15 px-1 text-sky-300 text-[13px] hover:bg-sky-500/25"
            >
              {`→ ${r.target}${r.many ? '[]' : ''}`}
            </button>
            {r.source && (
              <span
                className="rounded bg-slate-500/20 px-1 text-slate-400 text-[11px] uppercase tracking-wide"
                title={SOURCE_TIPS[r.source] ?? `Declared by ${r.source}`}
              >
                {r.source}
              </span>
            )}
          </span>
        ))}
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
          {(requiredOnly ? field.fields.filter(keepRequired) : field.fields).map((f) => (
            <FieldRow
              key={f.name}
              field={f}
              depth={depth + 1}
              parentPath={childPrefix}
              bulk={bulk}
              requiredOnly={requiredOnly}
            />
          ))}
        </div>
      )}

      {openRef && (
        <div style={{ paddingLeft: 12 }} className="border-l border-sky-500/40 ml-1">
          <div className="text-sky-300 text-[13px] py-0.5">{openRef}</div>
          {refFields ? (
            refFields.map((f) => (
              <FieldRow key={f.name} field={f} depth={depth + 1} parentPath="" bulk={null} requiredOnly={false} />
            ))
          ) : (
            <div className="text-slate-500 text-[13px]">
              {openRef} is not part of any schema the gateway exposes here.
            </div>
          )}
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
  const [requiredOnly, setRequiredOnly] = useState(false)
  const hasRequired = schema.fields.some(keepRequired)
  const shown = requiredOnly ? schema.fields.filter(keepRequired) : schema.fields

  return (
    <div className="h-full overflow-y-auto px-2 py-1">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 mb-1">
        <span className="min-w-0 break-all text-[14px] text-slate-500">{schema.fullName}</span>
        {hasRequired && (
          <label className="flex items-center gap-1 text-[13px] text-slate-400 cursor-pointer">
            <input
              type="checkbox"
              checked={requiredOnly}
              onChange={(e) => setRequiredOnly(e.target.checked)}
            />
            Required only
          </label>
        )}
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
      {shown.map((f) => (
        <FieldRow key={f.name} field={f} depth={0} parentPath="" bulk={bulk} requiredOnly={requiredOnly} />
      ))}
      {schema.fields.length === 0 && (
        <div className="text-slate-600 text-xs">(empty message)</div>
      )}
    </div>
  )
}
