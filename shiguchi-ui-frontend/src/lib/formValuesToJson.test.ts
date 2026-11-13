import { describe, expect, it } from 'vitest'
import { formValuesToJson } from './formJson'

describe('formValuesToJson', () => {
  it('GIVEN a repeated int64 holding 34 WHEN serialised THEN the body is {"ids":["34"]}', () => {
    expect(formValuesToJson({ ids: ['34'] })).toBe('{"ids":["34"]}')
  })

  it('GIVEN a repeated row nobody filled in WHEN serialised THEN it is dropped rather than sent as null', () => {
    expect(formValuesToJson({ ids: [undefined, '34', ''] })).toBe('{"ids":["34"]}')
  })

  it('GIVEN nothing filled in WHEN serialised THEN the body is an empty object', () => {
    expect(formValuesToJson({})).toBe('{}')
  })
})
