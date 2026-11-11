import { ConfigProvider } from 'antd';
import deDE from 'antd/locale/de_DE';
import enGB from 'antd/locale/en_GB';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { BrowserRouter } from 'react-router';

import { configureApiClient } from './api/apiClient';
import App from './App';
import KiwiLoader from './components/KiwiLoader';
import LanguageRoot from './i18n/LanguageRoot';
import { userManager } from './auth/oidcConfig';
import { CALLBACK_PATH } from './auth/paths';
import { configureSession } from './auth/session';
import './index.css';
import { listenForInstallPrompt } from './pwa/install';
import { theme } from './theme';

configureSession(userManager);
configureApiClient();
listenForInstallPrompt();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* only the IdP callback carries OIDC parameters */}
    <AuthProvider userManager={userManager} skipSigninCallback={window.location.pathname !== CALLBACK_PATH}>
      <BrowserRouter>
        {/* rendered again when the language changes, see Docs/i18n.md */}
        <LanguageRoot>
          {(language) => (
            <ConfigProvider locale={language === 'de' ? deDE : enGB} theme={theme}
              spin={{ indicator: <KiwiLoader size="fill" /> }}>
              <App />
            </ConfigProvider>
          )}
        </LanguageRoot>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>,
);
