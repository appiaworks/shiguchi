import { afterEach, describe, expect, it } from 'vitest'
import { toCurl, toGrpcurl, type ExportRequest } from './exportCommand'

const base: ExportRequest = {
  origin: 'https://gateway.example.com',
  serviceName: 'shop.Catalog',
  methodName: 'GetItem',
  payload: '{\n  "id": "42"\n}',
  metadataEntries: [{ key: 'x-tenant', value: 'acme' }],
  target: 'catalog:9090',
}

describe('toCurl', () => {
  afterEach(() => window.history.pushState({}, '', '/'))

  it('points the fallback at the real, resolved API URL under a context path', () => {
    window.history.pushState({}, '', '/ctx/tools/grpc/index.html')

    expect(toCurl(base)).toContain(`curl -X POST '${window.location.origin}/ctx/tools/grpc/api/invoke'`)
  })

  it('uses the method’s REST binding, so the command is the URL a real caller would use', () => {
    const cmd = toCurl({ ...base, httpMethod: 'GET', httpPath: '/v1/items/{id}' })

    expect(cmd).toContain(`curl -X GET 'https://gateway.example.com/v1/items/{id}'`)
  })

  it('omits a body for GET, since curl would turn it into a POST', () => {
    const cmd = toCurl({ ...base, httpMethod: 'GET', httpPath: '/v1/items/{id}' })

    expect(cmd).not.toContain('-d ')
    expect(cmd).not.toContain('Content-Type')
  })

  it('sends a compact body for verbs that take one', () => {
    const cmd = toCurl({ ...base, httpMethod: 'POST', httpPath: '/v1/items' })

    expect(cmd).toContain(`-d '{"id":"42"}'`)
    expect(cmd).toContain('Content-Type: application/json')
  })

  it('falls back to the console’s invoke endpoint for a method with no HTTP binding', () => {
    // Methods without a google.api.http option have no REST URL at all — the invoke endpoint is
    // the only way to reach them over HTTP.
    const cmd = toCurl(base)

    expect(cmd).toContain(`'${window.location.origin}/api/invoke'`)
    expect(cmd).toContain('"serviceName":"shop.Catalog"')
  })

  it('never includes the real token, because an exported command ends up in issue trackers', () => {
    const cmd = toCurl({ ...base, httpMethod: 'GET', httpPath: '/v1/items/{id}' })

    expect(cmd).toContain('Bearer $TOKEN')
  })

  it('carries metadata rows through as headers, skipping half-typed ones', () => {
    const cmd = toCurl({
      ...base,
      httpMethod: 'GET',
      httpPath: '/v1/items/{id}',
      metadataEntries: [
        { key: 'x-tenant', value: 'acme' },
        { key: '  ', value: 'not filled in yet' },
      ],
    })

    expect(cmd).toContain(`-H 'x-tenant: acme'`)
    expect(cmd).not.toContain('not filled in yet')
  })

  it('escapes a quote in a value rather than producing a broken command', () => {
    const cmd = toCurl({ ...base, metadataEntries: [{ key: 'x-note', value: "it's here" }] })

    expect(cmd).toContain(`'x-note: it'\\''s here'`)
  })

  it('exports an unparseable payload as-is, since that may be exactly what you are debugging', () => {
    const cmd = toCurl({ ...base, httpMethod: 'POST', httpPath: '/v1/items', payload: '{ not json' })

    expect(cmd).toContain('{ not json')
  })
})

describe('toGrpcurl', () => {
  it('addresses the backend directly, bypassing the gateway', () => {
    const cmd = toGrpcurl(base)

    expect(cmd).toContain(`'catalog:9090'`)
    expect(cmd).toContain(`'shop.Catalog/GetItem'`)
  })

  it('uses a placeholder when the backend address is not known', () => {
    const cmd = toGrpcurl({ ...base, target: null })

    expect(cmd).toContain('BACKEND_HOST:PORT')
  })

  it('keeps the token a placeholder here too', () => {
    expect(toGrpcurl(base)).toContain('Bearer $TOKEN')
  })
})
