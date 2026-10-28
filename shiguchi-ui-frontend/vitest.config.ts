import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Separate from vite.config.ts on purpose: that one writes its build output straight into
// shiguchi-ui's static resources, and a test run has no business touching those.
export default defineConfig({
  plugins: [react()],
  resolve: {
    // Same source alias as vite.config.ts (kept apart for the reason above).
    alias: {
      '@appiaworks/shiguchi-cursus-client': fileURLToPath(new URL('../shiguchi-cursus-client-js/src/index.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx', 'src/vite-env.d.ts'],
    },
  },
})
