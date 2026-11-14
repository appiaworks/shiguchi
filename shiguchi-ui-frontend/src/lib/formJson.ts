import type { FieldSchema, MessageSchema } from '../types/schema'

export type ParsedForm =
  | { ok: true; values: Record<string, unknown> }
  | { ok: false; error: string }

const INT64_TYPES = new Set(['int64', 'uint64', 'sint64', 'fixed64', 'sfixed64'])

/** True for 64-bit integers, which the form keeps as strings: a JS number loses precision above 2^53. */
export function isInt64(type: string): boolean {
  return INT64_TYPES.has(type)
}

/**
 * Turns the JSON editor's text into form values. Accepts proto field names (`user_id`) as well as
 * JSON names (`userId`), renaming to the JSON name the form edits; keys the schema doesn't know
 * are kept so switching modes never drops data. Invalid JSON, or JSON that isn't an object, is
 * reported instead of being replaced by an empty form.
 */
export function jsonToFormValues(text: string, schema: MessageSchema): ParsedForm {
  const trimmed = text.trim()
  if (trimmed === '') return { ok: true, values: {} }
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'Invalid JSON' }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: 'The request must be a JSON object' }
  }
  return { ok: true, values: normalize(parsed as Record<string, unknown>, schema.fields) }
}

function normalize(obj: Record<string, unknown>, fields: FieldSchema[]): Record<string, unknown> {
  const out: Record<string, unknown> = { ...obj }
  for (const f of fields) {
    if (f.name !== f.jsonName && f.name in out && !(f.jsonName in out)) {
      out[f.jsonName] = out[f.name]
      delete out[f.name]
    }
    if (f.jsonName in out) out[f.jsonName] = normalizeValue(out[f.jsonName], f)
  }
  return out
}

function normalizeValue(value: unknown, f: FieldSchema): unknown {
  if (value === null || typeof value !== 'object') return value
  if (f.mapEntry || f.label === 'map') {
    const valueField = f.fields[1]
    if (!valueField || Array.isArray(value)) return value
    const entries = Object.entries(value as Record<string, unknown>).map(
      ([k, v]) => [k, normalizeLeaf(v, valueField)] as const,
    )
    return Object.fromEntries(entries)
  }
  if (Array.isArray(value)) return value.map((v) => normalizeLeaf(v, f))
  return normalizeLeaf(value, f)
}

function normalizeLeaf(value: unknown, f: FieldSchema): unknown {
  if (f.type !== 'message' || f.wellKnownType || f.fields.length === 0) return value
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return value
  return normalize(value as Record<string, unknown>, f.fields)
}

/** An empty repeated row (still unset) is not a value: drop it instead of sending `null`. */
function withoutEmptyRows(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.filter((v) => v !== undefined && v !== null && v !== '').map(withoutEmptyRows)
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, withoutEmptyRows(v)]))
  }
  return value
}

/** The request body for the form: the form values as JSON, minus repeated rows nobody filled in. */
export function formValuesToJson(values: unknown, space?: number): string {
  return JSON.stringify(withoutEmptyRows(values), null, space)
}
