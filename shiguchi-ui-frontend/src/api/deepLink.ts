/**
 * The selected method in the URL, so a console view can be shared or bookmarked.
 *
 * <p>Only the service and method go in the fragment. The payload is deliberately left out: it is
 * often large, frequently contains ids or personal data, and a URL is the single most
 * copy-pasteable thing on the page — a link that carries a customer record into a chat message is
 * a worse default than one that opens an empty request editor.
 *
 * <p>A fragment rather than a query string, so the selection never reaches the server: fragments
 * are not sent in the HTTP request, which keeps internal service names out of access logs and
 * proxies.
 */

export interface MethodRef {
  serviceName: string
  methodName: string
}

/** `#/Catalog/GetItem` for the given selection. */
export function toHash(ref: MethodRef): string {
  return `#/${encodeURIComponent(ref.serviceName)}/${encodeURIComponent(ref.methodName)}`
}

/** The selection a fragment refers to, or null when it refers to nothing usable. */
export function fromHash(hash: string): MethodRef | null {
  const trimmed = hash.replace(/^#\/?/, '')
  if (!trimmed) return null

  const parts = trimmed.split('/')
  if (parts.length !== 2) return null

  const [serviceName, methodName] = parts.map((p) => {
    try {
      return decodeURIComponent(p)
    } catch {
      // A hand-edited URL can contain a stray % that is not an escape sequence.
      return ''
    }
  })
  if (!serviceName || !methodName) return null

  return { serviceName, methodName }
}
