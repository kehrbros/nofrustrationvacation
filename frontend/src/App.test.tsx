import { renderToString } from 'react-dom/server';
import { AuthContext, type AuthContextProps } from 'react-oidc-context';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import App from './App';

function renderApp(auth: Partial<AuthContextProps>, path = '/') {
  return renderToString(
    <AuthContext.Provider value={auth as AuthContextProps}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('App', () => {
  it('renders the dashboard for authenticated users', () => {
    const html = renderApp({
      isLoading: false,
      isAuthenticated: true,
      user: { profile: { sub: 'test', preferred_username: 'test' } } as AuthContextProps['user'],
    });

    expect(html).toContain('VacantAgent');
    expect(html).toContain('Übersicht');
    expect(html).toContain('test');
  });

  it('does not render the dashboard for unauthenticated users', () => {
    const html = renderApp({ isLoading: false, isAuthenticated: false });

    expect(html).not.toContain('Übersicht');
  });
});
