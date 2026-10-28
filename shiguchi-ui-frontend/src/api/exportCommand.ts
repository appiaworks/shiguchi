import type { MetadataEntry } from '../store/useGrpcUiStore'

/**
 * The current request as a command you can paste into a terminal.
 *
 * <p>Two shapes, because they answer different questions. `curl` reproduces the call *through the
 * gateway*, which is what you want in a bug report — it is the request the gateway actually
 * received. `grpcurl` reproduces the call *to the backend*, which is what you want when you
 * suspect the gateway itself is the problem and need to know whether the backend behaves the same
 * way without it.
 *
 * <p>Neither includes the bearer token. A console session's token ends up in issue trackers and
 * chat logs when it is included, and the placeholder is a better prompt than a leaked credential.
 */

/** Single-quotes a value for a POSIX shell, escaping any quotes inside it. */
function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

/** Metadata rows the user actually filled in. */
function usableMetadata(entries: MetadataEntry[]): MetadataEntry[] {
  return entries.filter((e) => e.key.trim() !== '')
}

/** Compact JSON, so the command stays on a readable number of lines. */
function compactJson(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload))
  } catch {
    // Not valid JSON yet — export what is there rather than refusing, since the user may be
    // exporting precisely to debug it.
    return payload.trim()
  }
}

export interface ExportRequest {
  /** Absolute base URL of the gateway, e.g. `https://gateway.example.com`. */
  origin: string
  serviceName: string
  methodName: string
  payload: string
  metadataEntries: MetadataEntry[]
  /** The backend address the gateway routes this service to, when known. */
  target?: string | null
  /** The HTTP binding, when the method has one. */
  httpMethod?: string | null
  httpPath?: string | null
}

/**
 * A `curl` command reproducing the call through the gateway.
 *
 * <p>Uses the method's own REST binding when it has one, so the command is the URL a real caller
 * would use. Falls back to the console's invoke endpoint for methods with no binding — which is
 * the only way to reach them over HTTP at all.
 */
export function toCurl(req: ExportRequest): string {
  const headers = usableMetadata(req.metadataEntries).map(
    (e) => `  -H ${shellQuote(`${e.key}: ${e.value}`)}`,
  )
  headers.unshift(`  -H ${shellQuote('Authorization: Bearer $TOKEN')}`)

  const body = compactJson(req.payload)

  if (req.httpPath) {
    const verb = (req.httpMethod ?? 'POST').toUpperCase()
    // Path variables cannot be filled in from here: the value lives in the payload, and
    // substituting it would silently send a different request than the console did.
    const url = `${req.origin}${req.httpPath}`
    const lines = [`curl -X ${verb} ${shellQuote(url)}`, ...headers]
    if (verb !== 'GET' && verb !== 'DELETE') {
      lines.splice(1, 0, `  -H ${shellQuote('Content-Type: application/json')}`)
      lines.push(`  -d ${shellQuote(body)}`)
    }
    return lines.join(' \\\n')
  }

  return [
    `curl -X POST ${shellQuote(`${req.origin}/grpc-ui/api/invoke`)}`,
    `  -H ${shellQuote('Content-Type: application/json')}`,
    ...headers,
    `  -d ${shellQuote(
      JSON.stringify({
        serviceName: req.serviceName,
        methodName: req.methodName,
        payload: body,
        metadata: usableMetadata(req.metadataEntries),
      }),
    )}`,
  ].join(' \\\n')
}

/**
 * A `grpcurl` command calling the backend directly, bypassing the gateway.
 *
 * <p>`-plaintext` because that is how most backends are reached inside a cluster and it is the
 * common case when debugging; drop it for a TLS backend.
 */
export function toGrpcurl(req: ExportRequest): string {
  const metadata = usableMetadata(req.metadataEntries).map(
    (e) => `  -H ${shellQuote(`${e.key}: ${e.value}`)}`,
  )
  metadata.unshift(`  -H ${shellQuote('authorization: Bearer $TOKEN')}`)

  return [
    `grpcurl -plaintext`,
    `  -d ${shellQuote(compactJson(req.payload))}`,
    ...metadata,
    `  ${shellQuote(req.target ?? 'BACKEND_HOST:PORT')}`,
    `  ${shellQuote(`${req.serviceName}/${req.methodName}`)}`,
  ].join(' \\\n')
}
