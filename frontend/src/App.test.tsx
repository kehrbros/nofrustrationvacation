import { renderToString } from 'react-dom/server';
import { AuthContext, type AuthContextProps } from 'react-oidc-context';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import App from './App';
import { i18n } from './i18n';

const authenticated: Partial<AuthContextProps> = {
  isLoading: false,
  isAuthenticated: true,
  user: { profile: { sub: 'test', preferred_username: 'test' } } as AuthContextProps['user'],
  events: { addAccessTokenExpired: () => () => undefined } as unknown as AuthContextProps['events'],
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
  it('renders the trip list with tabs and the button to add a trip', () => {
    const html = renderApp(authenticated, '/reisen');

    expect(html).toContain('Meine Reisen');
    expect(html).toContain('Reise hinzufügen');
    expect(html).toContain('Profil, Einstellungen und Abmelden');
  });

  it('renders the form for a new trip', () => {
    const html = renderApp(authenticated, '/reisen/neu');

    expect(html).toContain('Neue Reise');
    expect(html).toContain('Titelfoto');
    expect(html).toContain('Reise anlegen');
  });

  it('renders in English when chosen', async () => {
    await i18n.changeLanguage('en');
    try {
      const html = renderApp(authenticated, '/reisen/neu');

      expect(html).toContain('New trip');
      expect(html).toContain('Cover photo');
      expect(html).toContain('Create trip');
    } finally {
      await i18n.changeLanguage('de');
    }
  });

  it('does not render pages for unauthenticated users', () => {
    const html = renderApp({ isLoading: false, isAuthenticated: false }, '/reisen');

    expect(html).not.toContain('Meine Reisen');
  });
});
