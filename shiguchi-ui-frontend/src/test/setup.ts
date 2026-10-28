import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// The store reads localStorage at module load and writes to it as history accumulates. Clearing
// between tests keeps one test's history out of the next one's assertions.
beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  cleanup()
})
