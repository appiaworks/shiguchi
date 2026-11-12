import { describe, expect, it } from 'vitest'
import { resolveClientId } from './GlobalAuthPanel'
import type { AuthProviderDto } from '../types/schema'

const provider = (clientId?: string): AuthProviderDto => ({
  id: 'sso',
  type: 'oauth2',
  authorizationCode: { authorizationUrl: 'https://idp/auth', clientId },
})

describe('resolveClientId', () => {
  it('GIVEN a saved default and a configured id WHEN resolved SHOULD prefer the saved default', () => {
    expect(resolveClientId('mine', provider('configured'))).toBe('mine')
  })
  it('GIVEN no saved default WHEN resolved SHOULD use the configured id', () => {
    expect(resolveClientId(undefined, provider('configured'))).toBe('configured')
  })
  it('GIVEN neither WHEN resolved SHOULD be empty', () => {
    expect(resolveClientId(undefined, provider())).toBe('')
    expect(resolveClientId(undefined, null)).toBe('')
  })
})
