import { TokenResponse } from 'expo-auth-session';

import { Session } from './session';
import type { TokenStorage } from './tokenStorage';

function tokens(accessToken: string, { expiresIn = 900, refreshToken = 'refresh-1', idToken = 'id-1' } = {}) {
  return new TokenResponse({ accessToken, expiresIn, refreshToken, idToken, issuedAt: Math.floor(Date.now() / 1000) });
}

function memoryStorage(initial: TokenResponse | null = null): TokenStorage & { stored: TokenResponse | null } {
  return {
    stored: initial,
    async load() {
      return this.stored;
    },
    async save(t) {
      this.stored = t;
    },
    async clear() {
      this.stored = null;
    },
  };
}

describe('Session', () => {
  it('restores the tokens of the last start and hands out a fresh access token without refresh', async () => {
    const refresh = jest.fn();
    const session = new Session(memoryStorage(tokens('access-1')), refresh);

    await session.restore();

    expect(await session.accessToken()).toBe('access-1');
    expect(refresh).not.toHaveBeenCalled();
  });

  it('refreshes an expiring token once for concurrent requests and stores the rotated tokens', async () => {
    const storage = memoryStorage(tokens('access-1', { expiresIn: 10 }));
    // the refresh response of the IdP carries no ID token
    const refreshed = new TokenResponse({ accessToken: 'access-2', expiresIn: 900, refreshToken: 'refresh-2' });
    const refresh = jest.fn().mockResolvedValue(refreshed);
    const session = new Session(storage, refresh);
    await session.restore();

    const accessTokens = await Promise.all([session.accessToken(), session.accessToken()]);

    expect(accessTokens).toEqual(['access-2', 'access-2']);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledWith('refresh-1');
    expect(storage.stored?.refreshToken).toBe('refresh-2');
    expect(storage.stored?.idToken).toBe('id-1');
  });

  it('ends the session when the refresh fails', async () => {
    const storage = memoryStorage(tokens('access-1', { expiresIn: 10 }));
    const session = new Session(storage, jest.fn().mockRejectedValue(new Error('invalid_grant')));
    const listener = jest.fn();
    await session.restore();
    session.subscribe(listener);

    expect(await session.accessToken()).toBeNull();
    expect(storage.stored).toBeNull();
    expect(listener).toHaveBeenLastCalledWith(null);
  });

  it('has no access token without a login', async () => {
    const session = new Session(memoryStorage(), jest.fn());
    await session.restore();

    expect(await session.accessToken()).toBeNull();
  });
});
