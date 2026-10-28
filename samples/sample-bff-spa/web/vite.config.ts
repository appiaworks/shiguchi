import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  resolve: {
    alias: {
      // The push client is not on npm yet: use the in-repo source, as the gRPC console does
      // (shiguchi-ui-frontend). Once published, replace this with a normal dependency.
      '@appiaworks/shiguchi-cursus-client': fileURLToPath(
        new URL('../../../shiguchi-cursus-client-js/src/index.ts', import.meta.url),
      ),
    },
  },
  build: {
    // Straight into the gateway's static resources: the page is served from the gateway's own
    // origin, which is what lets the session cookie be same-site. Gitignored build output.
    outDir: '../gateway/src/main/resources/static',
    emptyOutDir: true,
  },
})
