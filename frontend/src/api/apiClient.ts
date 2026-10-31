import type { Session } from '../auth/session';
import { config } from '../config';
import { client } from '../generated/api/client.gen';

/**
 * Configures the generated API client (npm run generate:api): backend URL and the access token of the session. A
 * rejected token ends the session, the app then shows the login.
 */
export function configureApiClient(session: Session, apiClient = client) {
  apiClient.setConfig({ baseUrl: config.backendUrl });
  apiClient.interceptors.request.use(async (request) => {
    const accessToken = await session.accessToken();
    if (accessToken) {
      request.headers.set('Authorization', `Bearer ${accessToken}`);
    }
    return request;
  });
  apiClient.interceptors.response.use(async (response) => {
    if (response.status === 401) {
      await session.end();
    }
    return response;
  });
}
