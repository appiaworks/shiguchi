import type { FieldSchema, MessageSchema, ServiceSchema } from '../types/schema'

function simple(name: string): string {
  return name.split('.').pop() ?? name
}

function search(fields: FieldSchema[], name: string): FieldSchema[] | null {
  for (const f of fields) {
    if (f.fields.length > 0 && f.typeName && (f.typeName === name || simple(f.typeName) === name)) return f.fields
    const inner = search(f.fields, name)
    if (inner) return inner
  }
  return null
}

function searchMessage(m: MessageSchema | undefined, name: string): FieldSchema[] | null {
  if (!m) return null
  if (m.fullName === name || simple(m.fullName) === name) return m.fields
  return search(m.fields, name)
}

/**
 * The fields of the message a `resolves_type` name points to, found in any method's input or output schema
 * of the discovered services (by full name, else by simple name). Null when no schema exposes that type.
 */
export function findTypeFields(services: ServiceSchema[], name: string): FieldSchema[] | null {
  for (const svc of services) {
    for (const m of svc.methods) {
      const hit = searchMessage(m.outputSchema, name) ?? searchMessage(m.inputSchema, name)
      if (hit) return hit
    }
  }
  return null
}
