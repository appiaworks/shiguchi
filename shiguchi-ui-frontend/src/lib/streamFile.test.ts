import { describe, expect, it } from 'vitest'
import { toNdjsonLine } from './streamFile'

describe('toNdjsonLine', () => {
  it('compacts pretty JSON onto one line', () => {
    expect(toNdjsonLine('{\n  "a": 1,\n  "b": [1, 2]\n}')).toBe('{"a":1,"b":[1,2]}')
  })

  it('flattens newlines in non-JSON text', () => {
    expect(toNdjsonLine('plain\ntext')).toBe('plain text')
  })
})
