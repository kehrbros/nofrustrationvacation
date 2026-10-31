import { renderToString } from 'react-dom/server';
import { AuthContext, type AuthContextProps } from 'react-oidc-context';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import App from './App';

const authenticated: Partial<AuthContextProps> = {
  isLoading: false,
  isAuthenticated: true,
  user: { profile: { sub: 'test', preferred_username: 'test' } } as AuthContextProps['user'],
};

function renderApp(auth: Partial<AuthContextProps>, path: string) {
  return renderToString(
    <AuthContext.Provider value={auth as AuthContextProps}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('App', () => {
  it('renders the trips page with the navigation', () => {
    const html = renderApp(authenticated, '/reisen');

    expect(html).toContain('VacantAgent');
    expect(html).toContain('Meine Reisen');
  });

  it('does not render pages for unauthenticated users', () => {
    const html = renderApp({ isLoading: false, isAuthenticated: false }, '/reisen');

    expect(html).not.toContain('Meine Reisen');
  });
});
