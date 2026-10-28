import { describe, expect, it } from 'vitest'
import { fromHash, toHash } from './deepLink'

describe('deep links', () => {
  it('round-trips a selection', () => {
    const ref = { serviceName: 'shop.Catalog', methodName: 'GetItem' }

    expect(fromHash(toHash(ref))).toEqual(ref)
  })

  it('encodes a service name so a dotted package does not break the path', () => {
    expect(toHash({ serviceName: 'shop.v1.Catalog', methodName: 'GetItem' })).toBe(
      '#/shop.v1.Catalog/GetItem',
    )
  })

  it('reads a hash with or without the leading slash', () => {
    expect(fromHash('#/Catalog/GetItem')).toEqual({ serviceName: 'Catalog', methodName: 'GetItem' })
    expect(fromHash('#Catalog/GetItem')).toEqual({ serviceName: 'Catalog', methodName: 'GetItem' })
  })

  it('returns null for an empty or partial hash rather than a half-built reference', () => {
    expect(fromHash('')).toBeNull()
    expect(fromHash('#')).toBeNull()
    expect(fromHash('#/')).toBeNull()
    expect(fromHash('#/OnlyAService')).toBeNull()
    expect(fromHash('#/too/many/parts')).toBeNull()
  })

  it('survives a hand-edited URL with a stray percent sign', () => {
    // decodeURIComponent throws on '%zz'; a malformed link should open the console, not break it.
    expect(fromHash('#/Catalog%zz/GetItem')).toBeNull()
  })
})
