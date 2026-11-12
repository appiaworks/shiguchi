/**
 * Parses a file of request messages for a client-streaming call: either a JSON array of objects,
 * or NDJSON / JSON Lines (one object per non-empty line). Returns each message pretty-printed.
 */
export type ParsedMessages = { ok: true; messages: string[] } | { ok: false; error: string }

const pretty = (v: unknown) => JSON.stringify(v, null, 2)

export function parseMessageFile(text: string): ParsedMessages {
  const trimmed = text.replace(/^\uFEFF/, '').trim()
  if (!trimmed) return { ok: false, error: 'The file is empty.' }
  if (trimmed.startsWith('[')) {
    try {
      const arr: unknown = JSON.parse(trimmed)
      if (!Array.isArray(arr) || arr.length === 0) return { ok: false, error: 'The JSON array has no messages.' }
      return { ok: true, messages: arr.map(pretty) }
    } catch (e) {
      return { ok: false, error: `Invalid JSON array: ${(e as Error).message}` }
    }
  }
  const messages: string[] = []
  const lines = trimmed.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim()
    if (!line) continue
    try {
      messages.push(pretty(JSON.parse(line)))
    } catch (e) {
      return { ok: false, error: `Line ${i + 1} is not valid JSON: ${(e as Error).message}` }
    }
  }
  return messages.length ? { ok: true, messages } : { ok: false, error: 'The file has no messages.' }
}
