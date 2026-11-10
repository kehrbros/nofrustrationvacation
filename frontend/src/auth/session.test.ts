import { describe, expect, it } from 'vitest';

import { returnPath, tokenRoles } from './session';

const token = (claims: object) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')}.s`;

describe('tokenRoles', () => {
  it('reads the roles of the own IdP and of Keycloak', () => {
    expect(tokenRoles(token({ roles: ['tester', 'user'] }))).toEqual(['tester', 'user']);
    expect(tokenRoles(token({ realm_access: { roles: ['tester'] } }))).toEqual(['tester']);
  });

  it('has no roles without a token or with a broken one', () => {
    expect(tokenRoles(undefined)).toEqual([]);
    expect(tokenRoles('not-a-jwt')).toEqual([]);
    expect(tokenRoles(token({ sub: 'x' }))).toEqual([]);
  });
});

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
