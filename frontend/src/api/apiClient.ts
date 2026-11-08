import { accessToken, renewSession } from '../auth/session';
import { client } from '../generated/api/client.gen';
import { currentLanguage } from '../i18n';

/** Unsent copies of the requests, to repeat a request once with a renewed token. */
const retries = new WeakMap<Request, Request>();

/**
 * Configures the generated API client: same origin (the dev server proxies /api to the backend) and the access token
 * of the logged in user, renewed before it expires (see auth/session.ts). A rejected token is renewed and the request
 * repeated once. The user is never logged out here, otherwise the current page and its inputs would be lost.
 */
export function configureApiClient() {
  client.setConfig({ baseUrl: import.meta.env.VITE_API_BASE_URL ?? '' });
  client.interceptors.request.use(async (request) => {
    // language of the UI, for texts the backend writes (Docs/i18n.md#backend)
    request.headers.set('Accept-Language', currentLanguage());
    const token = await accessToken();
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`);
      retries.set(request, request.clone());
    }
    return request;
  });
  client.interceptors.response.use(async (response, request, options) => {
    const retry = retries.get(request);
    if (response.status !== 401 || !retry) {
      return response;
    }
    retries.delete(request);
    const sent = request.headers.get('Authorization');
    const user = await renewSession();
    if (!user || `Bearer ${user.access_token}` === sent) {
      return response;
    }
    retry.headers.set('Authorization', `Bearer ${user.access_token}`);
    // assigned first, a call as method of options would fail with "Illegal invocation"
    const send = options.fetch ?? fetch;
    return send(retry);
  });
}
