import { describe, expect, it, vi } from 'vitest';

import html from '../public/oauth2-callback.html?raw';
import script from '../public/oauth2-callback.js?raw';

function run(search: string, opener: { postMessage: ReturnType<typeof vi.fn> } | null) {
  const fakeWindow = {
    location: { search, origin: 'https://gw.example.org' },
    opener,
    close: vi.fn(),
  };
  new Function('window', 'URLSearchParams', script)(fakeWindow, URLSearchParams);
  return fakeWindow;
}

describe('oauth2-callback page', () => {
  it('GIVEN the callback page WHEN inspected SHOULD load its script from a file and carry no inline script', () => {
    const withoutComments = html.replace(/<!--[\s\S]*?-->/g, '');
    expect(withoutComments).toContain('<script src="oauth2-callback.js"></script>');
    expect(withoutComments).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/);
  });

  it('GIVEN an opener WHEN the IdP redirects back SHOULD post code and state to the own origin, then close', () => {
    const opener = { postMessage: vi.fn() };
    const w = run('?code=abc&state=xyz', opener);
    expect(opener.postMessage).toHaveBeenCalledWith(
      { type: 'oauth2-callback', code: 'abc', error: null, state: 'xyz' },
      'https://gw.example.org'
    );
    expect(w.close).toHaveBeenCalledTimes(1);
  });

  it('GIVEN an error response WHEN the IdP redirects back SHOULD forward the error', () => {
    const opener = { postMessage: vi.fn() };
    run('?error=access_denied', opener);
    expect(opener.postMessage.mock.calls[0][0]).toMatchObject({ code: null, error: 'access_denied' });
  });

  it('GIVEN no opener WHEN the page loads SHOULD just close without throwing', () => {
    const w = run('?code=abc', null);
    expect(w.close).toHaveBeenCalledTimes(1);
  });
});
