import { renderToString } from 'react-dom/server';
import { AuthContext, type AuthContextProps } from 'react-oidc-context';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import App from './App';
import { RoleProvider, type Role } from './role/RoleContext';

const authenticated: Partial<AuthContextProps> = {
  isLoading: false,
  isAuthenticated: true,
  user: { profile: { sub: 'test', preferred_username: 'test' } } as AuthContextProps['user'],
};

function renderApp(auth: Partial<AuthContextProps>, path: string, role?: Role) {
  return renderToString(
    <AuthContext.Provider value={auth as AuthContextProps}>
      <RoleProvider initialRole={role}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </RoleProvider>
    </AuthContext.Provider>,
  );
}

describe('App', () => {
  it('renders the consumer dashboard with the role switcher', () => {
    const html = renderApp(authenticated, '/consumer', 'consumer');

    expect(html).toContain('VacantAgent');
    expect(html).toContain('Meine Aufträge');
    expect(html).toContain('Consumer');
    expect(html).toContain('Provider');
    expect(html).not.toContain('Betreiber');
  });

  it('renders the provider navigation for providers', () => {
    const html = renderApp(authenticated, '/provider', 'provider');

    expect(html).toContain('Meine Angebote');
    expect(html).toContain('Agents');
    expect(html).toContain('Einnahmen');
  });

  it('offers top-ups to consumers', () => {
    const html = renderApp(authenticated, '/consumer/guthaben', 'consumer');

    expect(html).toContain('Guthaben aufladen');
    expect(html).toContain('Weiter zu PayPal');
  });

  it('renders the agent form for providers', () => {
    const html = renderApp(authenticated, '/provider/agents', 'provider');

    expect(html).toContain('Agent hinzufügen');
    expect(html).toContain('verschlüsselt');
  });

  it('renders the onboarding with the role choice', () => {
    const html = renderApp(authenticated, '/willkommen');

    expect(html).toContain('Willkommen, test.');
    expect(html).toContain('Ich kaufe Tokens');
    expect(html).toContain('Ich biete Kapazität an');
  });

  it('offers the operator view only to operators', () => {
    const operator = {
      ...authenticated,
      user: { profile: { sub: 'admin', roles: ['user', 'admin'] } } as unknown as AuthContextProps['user'],
    };
    const html = renderApp(operator, '/betreiber', 'operator');

    expect(html).toContain('Betreiber');
    expect(html).toContain('Orderbuch');
  });

  it('does not render the dashboard for unauthenticated users', () => {
    const html = renderApp({ isLoading: false, isAuthenticated: false }, '/consumer', 'consumer');

    expect(html).not.toContain('Meine Aufträge');
  });
});
