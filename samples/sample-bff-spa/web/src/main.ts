import { createPushClient } from '@appiaworks/shiguchi-cursus-client'
import type { PushClient } from '@appiaworks/shiguchi-cursus-client'

// Everything here is same-origin and cookie-authenticated: no token is ever in this page.

interface Session {
  authenticated: boolean
  subject?: string
  name?: string
  provider?: string
  expiresAt?: string
}

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T

let push: PushClient | null = null
let pushSubject: string | null = null

function show(el: HTMLElement, value: unknown): void {
  el.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

function logPush(line: string): void {
  const el = $('push-log')
  el.textContent = `${new Date().toLocaleTimeString()}  ${line}\n${el.textContent ?? ''}`
}

// ---- session and sign-in --------------------------------------------------------------------

async function loadProviders(): Promise<void> {
  const res = await fetch('/bff/providers')
  const providers: { id: string; displayName: string }[] = res.ok ? await res.json() : []
  const box = $('providers')
  box.replaceChildren()
  for (const p of providers) {
    const button = document.createElement('button')
    button.textContent = `Sign in with ${p.displayName}`
    button.addEventListener('click', () => {
      // A full-page navigation: the gateway answers with a redirect to the issuer, and brings the
      // browser back to return_to once the session cookie is set.
      const returnTo = encodeURIComponent(`${location.origin}/`)
      location.href = `/bff/login/${encodeURIComponent(p.id)}?return_to=${returnTo}`
    })
    box.append(button)
  }
}

async function refreshSession(): Promise<Session> {
  const res = await fetch('/bff/session')
  const session: Session = await res.json()
  show($('session'), session)
  const signedIn = session.authenticated
  for (const id of ['logout', 'rest', 'grpcweb', 'ping']) ($(id) as HTMLButtonElement).disabled = !signedIn
  if (signedIn && session.subject) startPush(session.subject)
  else stopPush()
  return session
}

// The gateway sets shiguchi.bff.csrf-header.name=X-CSRF: every state-changing call carries it
// (a custom header forces a CORS preflight, so another site cannot send it with the cookie).
const CSRF = { 'X-CSRF': '1' }

async function logout(): Promise<void> {
  // A cookie write: the browser adds Origin, which the gateway must find among the application's origins.
  const res = await fetch('/bff/logout', { method: 'POST', headers: CSRF })
  logPush(`logout: HTTP ${res.status}`)
  // The socket is closed by the gateway with 4440; this page stops the client on its own too.
  await refreshSession()
}

// ---- who am I: REST and gRPC-Web --------------------------------------------------------------

function describeClaims(claimsJson: string | undefined): unknown {
  if (!claimsJson) return 'no token reached the backend'
  const c = JSON.parse(claimsJson) as { sub?: string; iat?: number; exp?: number }
  const at = (s?: number) => (s ? new Date(s * 1000).toLocaleTimeString() : '?')
  return { sub: c.sub, issuedAt: at(c.iat), expires: at(c.exp) }
}

async function callRest(): Promise<void> {
  const res = await fetch('/v1/whoami')
  const body = await res.json()
  show($('who'), {
    via: 'REST',
    status: res.status,
    principal: body.principal,
    token: describeClaims(body.claimsJson),
  })
}

// A unary gRPC-Web call by hand: a request body is one frame (flag byte 0, 4-byte big-endian length,
// message), a response is data frames and then a trailers frame (flag 0x80). WhoamiRequest has no
// fields, so the message is empty. Connect-ES with generated code does the same for real services:
// see docs/grpc-web.md. Here it would need protoc-gen-es for a one-field message.
async function callGrpcWeb(): Promise<void> {
  const res = await fetch('/sample.bff.WhoAmI/Whoami', {
    method: 'POST',
    headers: { ...CSRF, 'Content-Type': 'application/grpc-web+proto', 'X-Grpc-Web': '1' },
    body: new Uint8Array([0, 0, 0, 0, 0]),
  })
  const bytes = new Uint8Array(await res.arrayBuffer())
  let message: Uint8Array | null = null
  let trailers = ''
  for (let i = 0; i + 5 <= bytes.length; ) {
    const flag = bytes[i]
    const length = new DataView(bytes.buffer, bytes.byteOffset + i + 1, 4).getUint32(0)
    const frame = bytes.subarray(i + 5, i + 5 + length)
    if (flag & 0x80) trailers = new TextDecoder().decode(frame)
    else message = frame
    i += 5 + length
  }
  const status = /grpc-status:\s*(\d+)/.exec(trailers)?.[1] ?? res.headers.get('grpc-status') ?? '?'
  if (!message) {
    show($('who'), { via: 'gRPC-Web', httpStatus: res.status, grpcStatus: status, trailers })
    return
  }
  const fields = decodeStrings(message)
  show($('who'), {
    via: 'gRPC-Web',
    grpcStatus: status,
    principal: fields.get(1),
    token: describeClaims(fields.get(2)),
  })
}

/** Reads the string fields (wire type 2) of a protobuf message: enough for WhoamiReply. */
function decodeStrings(message: Uint8Array): Map<number, string> {
  const out = new Map<number, string>()
  let i = 0
  const varint = (): number => {
    let result = 0
    for (let shift = 0; ; shift += 7) {
      const b = message[i++]
      result |= (b & 0x7f) << shift
      if (!(b & 0x80)) return result
    }
  }
  while (i < message.length) {
    const tag = varint()
    if ((tag & 7) !== 2) break // this reply only has length-delimited fields
    const length = varint()
    out.set(tag >> 3, new TextDecoder().decode(message.subarray(i, i + length)))
    i += length
  }
  return out
}

// ---- push over the cookie ---------------------------------------------------------------------

function startPush(subject: string): void {
  if (push && pushSubject === subject) return
  stopPush()
  pushSubject = subject
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  push = createPushClient({
    url: `${scheme}://${location.host}/push/ws`,
    // No token, no connect frame: the browser sends the session cookie with the handshake.
    auth: { mode: 'cookie' },
  })
  push.on('state', (state, info) => {
    $('push-state').textContent = info?.reason ? `${state} (${info.reason})` : state
  })
  push.on('error', (e) => {
    logPush(`error ${e.code}: ${e.message}`)
    // 4440: the session ended (logout elsewhere, expiry). The client has stopped; show the truth.
    if (e.code === 'session-ended' || e.code === 'signed-out') void refreshSession()
  })
  push.subscribe(`user:${subject}`, {
    onEvent: (e) => logPush(`event ${e.topic} #${e.id}: ${JSON.stringify(e.data)}`),
    onError: (e) => logPush(`subscription error ${e.code}`),
  })
}

function stopPush(): void {
  push?.close()
  push = null
  pushSubject = null
  $('push-state').textContent = 'idle'
}

async function publishToMyself(): Promise<void> {
  if (!pushSubject) return
  const res = await fetch('/push/publish', {
    method: 'POST',
    headers: { ...CSRF, 'Content-Type': 'application/json' },
    body: JSON.stringify({ topic: `user:${pushSubject}`, data: { hello: 'from the page', at: new Date().toISOString() } }),
  })
  logPush(`publish: HTTP ${res.status}`)
}

// ---- wiring -------------------------------------------------------------------------------------

$('logout').addEventListener('click', () => void logout())
$('rest').addEventListener('click', () => void callRest())
$('grpcweb').addEventListener('click', () => void callGrpcWeb())
$('ping').addEventListener('click', () => void publishToMyself())

void loadProviders()
void refreshSession()
