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

const clientIds: Record<string, string> = {}

/**
 * The client id used for the current sign-in, kept in memory so a silent token refresh works even
 * when the user did not tick "remember" (which persists the id to localStorage).
 */
export function setSessionClientId(providerId: string, clientId: string) {
  if (clientId) clientIds[providerId] = clientId
}

export function getSessionClientId(providerId: string): string | undefined {
  return clientIds[providerId]
}

export function clearSessionSecret(providerId: string) {
  delete secrets[providerId]
}
