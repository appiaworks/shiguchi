# UI smoke test

Loads `/grpc-ui/`, `/graphiql` and `/swagger-ui/index.html` of a running gateway in headless
Chromium and fails on a page that does not render, a CSP violation or a console error. It guards
what the module tests cannot: the bundled assets running in a real browser under the real
security headers.

```
./gradlew -p samples sample-discovery-gateway:bootJar -PgatewayVersion=<version>
java -jar samples/sample-discovery-gateway/build/libs/<jar> &
cd scripts/ui-smoke && npm install && npx playwright install chromium && npm run smoke
```

`BASE_URL` points it at another gateway (default `http://localhost:8080`). CI runs it nightly.
