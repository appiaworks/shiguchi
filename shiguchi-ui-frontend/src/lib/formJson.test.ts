import { describe, expect, it } from 'vitest'
import type { FieldSchema, MessageSchema } from '../types/schema'
import { isInt64, jsonToFormValues } from './formJson'

function field(over: Partial<FieldSchema> & { name: string }): FieldSchema {
  return {
    jsonName: over.name,
    type: 'string',
    label: 'optional',
    fields: [],
    enumValues: [],
    mapEntry: false,
    ...over,
  }
}

const address: FieldSchema = field({
  name: 'home_address',
  jsonName: 'homeAddress',
  type: 'message',
  fields: [field({ name: 'zip_code', jsonName: 'zipCode' })],
})
const schema: MessageSchema = {
  fullName: 't.Req',
  fields: [
    field({ name: 'user_id', jsonName: 'userId' }),
    address,
    field({ name: 'past_addresses', jsonName: 'pastAddresses', type: 'message', label: 'repeated', fields: address.fields }),
    field({
      name: 'by_name',
      jsonName: 'byName',
      type: 'message',
      label: 'map',
      mapEntry: true,
      fields: [field({ name: 'key' }), field({ name: 'value', type: 'message', fields: address.fields })],
    }),
  ],
}

describe('jsonToFormValues', () => {
  it('GIVEN proto field names WHEN converting THEN renames them to JSON names at every level', () => {
    const r = jsonToFormValues(
      '{"user_id":"7","home_address":{"zip_code":"1"},"past_addresses":[{"zip_code":"2"}],"by_name":{"a":{"zip_code":"3"}}}',
      schema,
    )
    expect(r).toEqual({
      ok: true,
      values: {
        userId: '7',
        homeAddress: { zipCode: '1' },
        pastAddresses: [{ zipCode: '2' }],
        byName: { a: { zipCode: '3' } },
      },
    })
  })

  it('GIVEN an unknown key WHEN converting THEN keeps it', () => {
    const r = jsonToFormValues('{"userId":"1","mystery":{"x":1}}', schema)
    expect(r).toEqual({ ok: true, values: { userId: '1', mystery: { x: 1 } } })
  })

  it('GIVEN both spellings of a field WHEN converting THEN keeps the JSON name value and the other key', () => {
    const r = jsonToFormValues('{"userId":"1","user_id":"2"}', schema)
    expect(r).toEqual({ ok: true, values: { userId: '1', user_id: '2' } })
  })

  it('GIVEN invalid JSON WHEN converting THEN reports an error instead of an empty form', () => {
    const r = jsonToFormValues('{"userId":', schema)
    expect(r.ok).toBe(false)
  })

  it('GIVEN a JSON array WHEN converting THEN reports that an object is required', () => {
    expect(jsonToFormValues('[1]', schema)).toEqual({ ok: false, error: 'The request must be a JSON object' })
  })

  it('GIVEN blank text WHEN converting THEN yields an empty form', () => {
    expect(jsonToFormValues('  ', schema)).toEqual({ ok: true, values: {} })
  })
})

describe('isInt64', () => {
  it('GIVEN 64-bit and 32-bit types WHEN checking THEN flags only the 64-bit ones', () => {
    expect(isInt64('int64')).toBe(true)
    expect(isInt64('fixed64')).toBe(true)
    expect(isInt64('int32')).toBe(false)
  })
})
