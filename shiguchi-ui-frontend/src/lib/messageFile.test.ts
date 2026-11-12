import { describe, expect, it } from 'vitest'
import { parseMessageFile } from './messageFile'

describe('parseMessageFile', () => {
  it('reads NDJSON, skipping blank lines', () => {
    const r = parseMessageFile('{"a":1}\n\n{"a":2}\r\n')
    expect(r).toEqual({ ok: true, messages: ['{\n  "a": 1\n}', '{\n  "a": 2\n}'] })
  })

  it('reads a JSON array', () => {
    const r = parseMessageFile('[{"a":1},{"a":2}]')
    expect(r.ok && r.messages).toHaveLength(2)
  })

  it('names the bad line in NDJSON', () => {
    const r = parseMessageFile('{"a":1}\nnope')
    expect(!r.ok && r.error).toMatch(/Line 2/)
  })

  it('rejects an empty file and an empty array', () => {
    expect(parseMessageFile('  \n').ok).toBe(false)
    expect(parseMessageFile('[]').ok).toBe(false)
  })
})
