import { TokenResponse } from 'expo-auth-session';

import { Session } from '../auth/session';
import { createClient } from '../generated/api/client';
import { getProfile } from '../generated/api';
import { configureApiClient } from './apiClient';

function signedInSession() {
  let stored: TokenResponse | null = new TokenResponse({
    accessToken: 'access-1',
    expiresIn: 900,
    issuedAt: Math.floor(Date.now() / 1000),
  });
  return new Session(
    {
      load: async () => stored,
      save: async (t) => {
        stored = t;
      },
      clear: async () => {
        stored = null;
      },
    },
    jest.fn(),
  );
}

describe('configureApiClient', () => {
  it('sends the access token of the session to the backend', async () => {
    const session = signedInSession();
    await session.restore();
    const fetch = jest.fn(async (_input: RequestInfo | URL) => Response.json({ id: 1, username: 'test' }));
    const client = createClient({ fetch });
    configureApiClient(session, client);

    const { data } = await getProfile({ client });

    expect(data?.username).toBe('test');
    const request = fetch.mock.calls[0][0] as Request;
    expect(request.url).toBe('http://localhost:8080/api/v1/users/me');
    expect(request.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('ends the session when the backend rejects the token', async () => {
    const session = signedInSession();
    await session.restore();
    const client = createClient({ fetch: async () => new Response(null, { status: 401 }) });
    configureApiClient(session, client);

    await getProfile({ client });

    expect(session.current).toBeNull();
  });
});
