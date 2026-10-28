/**
 * In-memory session secrets — never written to localStorage or any persistent store.
 * Cleared on page reload. Keyed by provider id.
 */
const secrets: Record<string, string> = {}

export function setSessionSecret(providerId: string, clientSecret: string) {
  secrets[providerId] = clientSecret
}

export function getSessionSecret(providerId: string): string | undefined {
  return secrets[providerId]
}

export function clearSessionSecret(providerId: string) {
  delete secrets[providerId]
}
