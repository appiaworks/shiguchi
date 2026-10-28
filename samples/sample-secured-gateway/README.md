# sample-secured-gateway

A gateway secured on **both** sides: callers authenticate to it with a JWT, and it authenticates
to its backend with a client certificate.

Those are two different problems, and they belong in one sample because getting only the first
right is the common mistake. A gateway that validates the caller's token carefully and then
forwards it to the backend in plaintext has moved the credential onto the wire — the gateway
warns about exactly that at startup.

| Half | Configuration | What it does |
| --- | --- | --- |
| Inbound | `shiguchi.security.mode: AUTHENTICATED` | every surface requires a valid bearer token |
| Outbound | `services[].tls.{trust-cert-collection,cert-chain,private-key}` | mutual TLS to the backend |

The backend is an in-process gRPC server configured with `ClientAuth.REQUIRE`, so a caller that
does not present a certificate signed by the sample CA is rejected **during the handshake**. The
gateway reaching it at all is the proof that the outbound half works.

## About the certificates

They are generated into a temp directory at startup and never committed. Committed private keys
in sample projects get copied into real deployments, and a key that is public in a git history is
not a key.

`DevCertificates` makes a throwaway CA, a server certificate for `localhost`, and a client
certificate for the gateway. A real deployment mounts PEM files from its CA, cert-manager, or a
secret, and writes the paths in `application.yml` directly — `CertificateEnvironment` exists only
because these files do not exist until the JVM starts.

Similarly, the JWT decoder here uses a **fixed HMAC secret** printed at startup, so the sample
needs no identity provider. Swapping it for a real one is one property:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/main
```

Nothing else in the sample changes — the interesting configuration is `shiguchi.security`.

## Run it

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-secured-gateway:bootRun
```

The log prints a ready-made token:

```
JWT: HS256 with a fixed development secret. A demo token is printed below.
demo token: eyJhbGciOiJIUzI1NiJ9...
```

Without it, everything is refused:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/v1/products/A100
# 401
```

With it, the call goes through — and on the way, the gateway completed a mutual-TLS handshake
with the backend:

```bash
TOKEN='<paste the demo token>'
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/v1/products/A100 | jq
```

```json
{ "sku": "A100", "name": "Anvil, 50 kg" }
```

The actuator surface is covered by the same policy, which is the right default for an endpoint
that lists your internal routes:

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/gateway-routes | jq '.loaded'
# 1
```

## Seeing each half fail

Worth doing once — a security control you have never seen refuse anything is a control you are
trusting on faith.

**Drop the client certificate.** Comment out `cert-chain` and `private-key` in
`application.yml` and restart. The backend still demands one, so the handshake fails and the
schema never loads:

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/health | jq '.components.shiguchi'
# "status": "DOWN", ... "lastError": "UNAVAILABLE: ..."
```

**Drop the whole `tls` block.** The gateway now talks plaintext to a TLS backend — and logs the
warning this sample exists to make concrete:

```
gRPC gateway security: bearer tokens are forwarded to 1 backend(s) over PLAINTEXT (catalog).
The gateway copies the caller's Authorization header onto every outbound call, so those tokens
are readable by anything on the network path. ...
```

## What to copy from this

- **Both halves, together.** Authenticating callers without encrypting the backend hop leaves the
  credential readable on that hop.
- **`insecure: true` is the default.** A service definition with no `tls` block is plaintext. That
  is legitimate to a mesh sidecar on localhost, and not much else.
- **Actuator is a surface too.** `gateway-routes` and `gateway-refresh` describe your internals;
  `AUTHENTICATED` covering them is a feature.
- **Certificates come from your platform**, not from application code. The generation here is a
  sample affordance, not a pattern.

→ [docs/security.md](../../docs/security.md) ·
[docs/configuration.md](../../docs/configuration.md) ·
[docs/troubleshooting.md](../../docs/troubleshooting.md)
