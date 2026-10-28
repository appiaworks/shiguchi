import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/grpc-ui/',
  resolve: {
    alias: {
      // The push panel uses the in-repo client from source: it is not published to npm yet and
      // is bundled into the console either way. Mirrored in tsconfig.json `paths` and
      // vitest.config.ts; CI runs the frontend job when shiguchi-cursus-client-js/src changes.
      '@appiaworks/shiguchi-cursus-client': fileURLToPath(new URL('../shiguchi-cursus-client-js/src/index.ts', import.meta.url)),
    },
  },
  build: {
    outDir: '../shiguchi-ui/build/generated/grpc-ui-static/static/grpc-ui',
    emptyOutDir: true,
    // Monaco is several megabytes and is deliberately in its own lazily-loaded chunks (see
    // LazyEditor). The default 500 kB warning is about the entry chunk being too big to be
    // worth downloading up front; these chunks are not downloaded up front.
    chunkSizeWarningLimit: 4000,
  },
  server: {
    proxy: {
      '/grpc-ui/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})