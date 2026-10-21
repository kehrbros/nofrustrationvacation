import type { UserManager } from 'oidc-client-ts';

import { client } from '../generated/api/client.gen';

/**
 * Configures the generated API client: same origin (the dev server proxies /api to the backend) and the
 * access token of the logged in user. A rejected token logs the user out, which restarts the login.
 */
export function configureApiClient(userManager: UserManager) {
  client.setConfig({ baseUrl: import.meta.env.VITE_API_BASE_URL ?? '' });
  client.interceptors.request.use(async (request) => {
    const user = await userManager.getUser();
    if (user) {
      request.headers.set('Authorization', `Bearer ${user.access_token}`);
    }
    return request;
  });
  client.interceptors.response.use(async (response) => {
    if (response.status === 401) {
      await userManager.removeUser();
    }
    return response;
  });
}
