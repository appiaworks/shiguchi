import { useState } from 'react'
import type { FieldSchema, MessageSchema } from '../types/schema'

interface Props {
  schema: MessageSchema
  values: Record<string, unknown>
  onChange: (v: Record<string, unknown>) => void
}

export function FormEditor({ schema, values, onChange }: Props) {
  return (
    <div className="h-full overflow-y-auto p-2">
      <FieldList fields={schema.fields} values={values} onChange={onChange} depth={0} />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldList — renders a flat list of fields, grouping oneof siblings
// ─────────────────────────────────────────────────────────────────────────────

function FieldList({
  fields,
  values,
  onChange,
  depth,
}: {
  fields: FieldSchema[]
  values: Record<string, unknown>
  onChange: (v: Record<string, unknown>) => void
  depth: number
}) {
  type Item =
    | { kind: 'field'; field: FieldSchema }
    | { kind: 'oneof'; name: string; fields: FieldSchema[] }

  const seen = new Set<string>()
  const items: Item[] = []
  for (const field of fields) {
    if (field.oneofName) {
      if (!seen.has(field.oneofName)) {
        seen.add(field.oneofName)
        items.push({ kind: 'oneof', name: field.oneofName, fields: fields.filter((f) => f.oneofName === field.oneofName) })
      }
    } else {
      items.push({ kind: 'field', field })
    }
  }

  if (items.length === 0) {
    return <div className="text-slate-700 text-[10px] italic">empty message</div>
  }

  return (
    <div className="space-y-1.5">
      {items.map((item) =>
        item.kind === 'field' ? (
          <FieldRow
            key={item.field.name}
            field={item.field}
            value={values[item.field.jsonName]}
            onChange={(v) => {
              const next = { ...values }
              if (v === undefined) delete next[item.field.jsonName]
              else next[item.field.jsonName] = v
              onChange(next)
            }}
            depth={depth}
          />
        ) : (
          <OneofGroup
            key={item.name}
            oneofName={item.name}
            fields={item.fields}
            values={values}
            onChange={onChange}
            depth={depth}
          />
        )
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// OneofGroup — radio-style selector for protobuf oneof
// ─────────────────────────────────────────────────────────────────────────────

function OneofGroup({
  oneofName,
  fields,
  values,
  onChange,
  depth,
}: {
  oneofName: string
  fields: FieldSchema[]
  values: Record<string, unknown>
  onChange: (v: Record<string, unknown>) => void
  depth: number
}) {
  const activeFromValues = fields.find((f) => values[f.jsonName] != null) ?? fields[0]
  const [selected, setSelected] = useState(activeFromValues.name)
  const activeField = fields.find((f) => f.name === selected) ?? fields[0]

  function pick(name: string) {
    setSelected(name)
    const next = { ...values }
    for (const f of fields) delete next[f.jsonName]
    onChange(next)
  }

  return (
    <div className="border border-slate-700 rounded p-1.5">
      <div className="text-[9px] text-slate-600 mb-1 uppercase tracking-wider">oneof {oneofName}</div>
      <div className="flex flex-wrap gap-1 mb-1.5">
        {fields.map((f) => (
          <button
            key={f.name}
            onClick={() => pick(f.name)}
            className={`px-2 py-0.5 rounded text-[10px] border transition-colors ${
              activeField.name === f.name
                ? 'bg-blue-800 border-blue-600 text-blue-100'
                : 'border-slate-700 text-slate-500 hover:text-slate-300'
            }`}
          >
            {f.name}
          </button>
        ))}
      </div>
      <FieldRow
        field={activeField}
        value={values[activeField.jsonName]}
        onChange={(v) => {
          const next = { ...values }
          for (const f of fields) delete next[f.jsonName]
          if (v !== undefined) next[activeField.jsonName] = v
          onChange(next)
        }}
        depth={depth + 1}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FieldRow — dispatch to Repeated / Map / Scalar
// ─────────────────────────────────────────────────────────────────────────────

function FieldRow({
  field,
  value,
  onChange,
  depth,
}: {
  field: FieldSchema
  value: unknown
  onChange: (v: unknown) => void
  depth: number
}) {
  if (field.wellKnownType === 'EMPTY') return null

  if (field.label === 'repeated') {
    return (
      <RepeatedField
        field={field}
        values={value as unknown[] | undefined}
        onChange={onChange}
        depth={depth}
      />
    )
  }
  if (field.label === 'map') {
    return (
      <MapField
        field={field}
        values={value as Record<string, unknown> | undefined}
        onChange={onChange}
        depth={depth}
      />
    )
  }
  return <ScalarField field={field} value={value} onChange={onChange} depth={depth} />
}

// ─────────────────────────────────────────────────────────────────────────────
// ScalarField — single-value field rendering
// ─────────────────────────────────────────────────────────────────────────────

function ScalarField({
  field,
  value,
  onChange,
  depth,
}: {
  field: FieldSchema
  value: unknown
  onChange: (v: unknown) => void
  depth: number
}) {
  const [expanded, setExpanded] = useState(true)
  const indent = depth * 10

  // Nested message (non-well-known, has fields)
  if (field.type === 'message' && !field.wellKnownType && field.fields.length > 0) {
    const msgValues = (value as Record<string, unknown>) ?? {}
    return (
      <div style={{ marginLeft: indent }}>
        <button
          onClick={() => setExpanded((e) => !e)}
          className="flex items-center gap-1 text-slate-400 text-xs hover:text-slate-200 w-full text-left py-0.5"
        >
          <span className="text-slate-600 text-[10px]">{expanded ? '▾' : '▸'}</span>
          <span className="font-mono text-slate-400">{field.name}</span>
          <span className="text-slate-700 text-[10px] ml-1">message</span>
        </button>
        {expanded && (
          <div className="ml-3 border-l border-slate-800 pl-2 mt-0.5">
            <FieldList
              fields={field.fields}
              values={msgValues}
              onChange={(next) =>
                onChange(Object.keys(next).length === 0 ? undefined : next)
              }
              depth={depth + 1}
            />
          </div>
        )}
      </div>
    )
  }

  const label = (
    <span className="text-slate-500 text-[10px] font-mono shrink-0" style={{ marginLeft: indent }}>
      {field.name}
    </span>
  )

  // Enum
  if (field.type === 'enum') {
    return (
      <div className="flex items-center gap-2">
        {label}
        <select
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value || undefined)}
          className="bg-slate-800 text-slate-300 text-xs px-1 py-0.5 rounded border border-slate-700 outline-none"
        >
          <option value="" />
          {field.enumValues.map((ev) => (
            <option key={ev.name} value={ev.name}>
              {ev.name}
            </option>
          ))}
        </select>
      </div>
    )
  }

  // Bool
  if (field.type === 'bool') {
    return (
      <div className="flex items-center gap-2">
        {label}
        <input
          type="checkbox"
          checked={(value as boolean) ?? false}
          onChange={(e) => onChange(e.target.checked)}
          className="accent-blue-500"
        />
      </div>
    )
  }

  // Timestamp → datetime-local
  if (field.wellKnownType === 'TIMESTAMP') {
    const isoSliced = value ? new Date(value as string).toISOString().slice(0, 16) : ''
    return (
      <div className="flex items-center gap-2">
        {label}
        <input
          type="datetime-local"
          value={isoSliced}
          onChange={(e) =>
            onChange(e.target.value ? new Date(e.target.value).toISOString() : undefined)
          }
          className="bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none"
        />
      </div>
    )
  }

  // google.type.Date / TimeOfDay, sent as ISO text (shiguchi.json.scalar-types).
  // A partial date (2026-09, --09-22) has no picker form, so it keeps the text input below.
  const isPartialDate = typeof value === 'string' && !/^\d{4}-\d{2}-\d{2}$/.test(value)
  if (field.wellKnownType === 'DATE' && !isPartialDate) {
    return (
      <div className="flex items-center gap-2">
        {label}
        <input
          type="date"
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value || undefined)}
          className="bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none"
        />
      </div>
    )
  }
  if (field.wellKnownType === 'TIME_OF_DAY') {
    return (
      <div className="flex items-center gap-2">
        {label}
        <input
          type="time"
          step={1}
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value || undefined)}
          className="bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none"
        />
      </div>
    )
  }

  // Numeric types
  const isNumeric = [
    'int32','int64','uint32','uint64','sint32','sint64',
    'fixed32','fixed64','sfixed32','sfixed64','float','double',
  ].includes(field.type)
  if (isNumeric) {
    return (
      <div className="flex items-center gap-2">
        {label}
        <input
          type="number"
          value={value === undefined || value === null ? '' : String(value)}
          onChange={(e) =>
            onChange(e.target.value === '' ? undefined : Number(e.target.value))
          }
          placeholder={field.type}
          className="w-32 bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none placeholder-slate-700 font-mono"
        />
      </div>
    )
  }

  // String / bytes / Duration / other well-known / message with no fields
  return (
    <div className="flex items-center gap-2">
      {label}
      <input
        type="text"
        value={(value as string) ?? ''}
        onChange={(e) => onChange(e.target.value || undefined)}
        placeholder={field.wellKnownType ?? field.typeName ?? field.type}
        className="flex-1 min-w-0 bg-slate-800 text-slate-300 text-xs px-2 py-0.5 rounded border border-slate-700 outline-none placeholder-slate-700 font-mono"
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RepeatedField — list with add / remove rows
// ─────────────────────────────────────────────────────────────────────────────

function RepeatedField({
  field,
  values,
  onChange,
  depth,
}: {
  field: FieldSchema
  values: unknown[] | undefined
  onChange: (v: unknown) => void
  depth: number
}) {
  const arr = values ?? []
  const indent = depth * 10

  function defaultItem(): unknown {
    if (field.type === 'message') return {}
    if (field.type === 'bool') return false
    if (field.type === 'enum') return field.enumValues[0]?.name ?? ''
    if (['int32','int64','uint32','uint64','float','double'].includes(field.type)) return 0
    return ''
  }

  const add = () => onChange([...arr, defaultItem()])
  const remove = (i: number) => onChange(arr.filter((_, idx) => idx !== i))
  const update = (i: number, v: unknown) => {
    const next = [...arr]
    next[i] = v
    onChange(next)
  }

  return (
    <div style={{ marginLeft: indent }}>
      <div className="flex items-center gap-1 py-0.5">
        <span className="text-slate-500 text-[10px] font-mono">{field.name}</span>
        <span className="text-slate-700 text-[10px]">[ ]</span>
        <button onClick={add} className="ml-1 text-[10px] text-blue-500 hover:text-blue-400">
          + add
        </button>
      </div>
      <div className="ml-2 space-y-1">
        {arr.map((item, i) => (
          <div key={i} className="flex items-start gap-1">
            <span className="text-slate-700 text-[10px] pt-1 shrink-0">[{i}]</span>
            <div className="flex-1 min-w-0">
              {field.type === 'message' && field.fields.length > 0 ? (
                <FieldList
                  fields={field.fields}
                  values={(item as Record<string, unknown>) ?? {}}
                  onChange={(v) => update(i, v)}
                  depth={0}
                />
              ) : (
                <ScalarField
                  field={{ ...field, label: 'optional' }}
                  value={item}
                  onChange={(v) => update(i, v)}
                  depth={0}
                />
              )}
            </div>
            <button
              onClick={() => remove(i)}
              className="text-[10px] text-slate-600 hover:text-red-400 pt-1 shrink-0"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// MapField — key-value pairs
// ─────────────────────────────────────────────────────────────────────────────

function MapField({
  field,
  values,
  onChange,
  depth,
}: {
  field: FieldSchema
  values: Record<string, unknown> | undefined
  onChange: (v: unknown) => void
  depth: number
}) {
  const map = values ?? {}
  const entries = Object.entries(map)
  const indent = depth * 10
  const valueField = field.fields.find((f) => f.name === 'value')

  const add = () => {
    const key = `key${entries.length + 1}`
    onChange({ ...map, [key]: '' })
  }
  const remove = (k: string) => {
    const next = { ...map }
    delete next[k]
    onChange(next)
  }
  const updateKey = (oldKey: string, newKey: string) => {
    if (oldKey === newKey) return
    const next: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(map)) next[k === oldKey ? newKey : k] = v
    onChange(next)
  }
  const updateVal = (k: string, v: unknown) => onChange({ ...map, [k]: v })

  return (
    <div style={{ marginLeft: indent }}>
      <div className="flex items-center gap-1 py-0.5">
        <span className="text-slate-500 text-[10px] font-mono">{field.name}</span>
        <span className="text-slate-700 text-[10px]">{'{}'}</span>
        <button onClick={add} className="ml-1 text-[10px] text-blue-500 hover:text-blue-400">
          + add
        </button>
      </div>
      <div className="ml-2 space-y-1">
        {entries.map(([k, v]) => (
          <div key={k} className="flex items-center gap-1">
            <input
              type="text"
              defaultValue={k}
              onBlur={(e) => updateKey(k, e.target.value)}
              placeholder="key"
              className="w-24 bg-slate-800 text-slate-300 text-[10px] px-1.5 py-0.5 rounded border border-slate-700 outline-none font-mono"
            />
            <span className="text-slate-700 text-[10px]">:</span>
            {valueField ? (
              <div className="flex-1 min-w-0">
                <ScalarField
                  field={{ ...valueField, label: 'optional' }}
                  value={v}
                  onChange={(newV) => updateVal(k, newV)}
                  depth={0}
                />
              </div>
            ) : (
              <input
                type="text"
                value={String(v ?? '')}
                onChange={(e) => updateVal(k, e.target.value)}
                placeholder="value"
                className="flex-1 bg-slate-800 text-slate-300 text-[10px] px-1.5 py-0.5 rounded border border-slate-700 outline-none font-mono"
              />
            )}
            <button
              onClick={() => remove(k)}
              className="text-[10px] text-slate-600 hover:text-red-400 shrink-0"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}