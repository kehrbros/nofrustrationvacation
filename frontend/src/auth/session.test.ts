import { describe, expect, it } from 'vitest';

import { returnPath } from './session';

describe('returnPath', () => {
  it('returns to the page the login started on', () => {
    expect(returnPath({ returnTo: '/reisen/3/stops/neu?neu=1' })).toBe('/reisen/3/stops/neu?neu=1');
  });

  it('falls back to the start page for missing or foreign targets', () => {
    expect(returnPath(undefined)).toBe('/');
    expect(returnPath({ returnTo: 'https://example.org/' })).toBe('/');
    expect(returnPath({ returnTo: '//example.org/' })).toBe('/');
    expect(returnPath({ returnTo: 42 })).toBe('/');
  });
});
