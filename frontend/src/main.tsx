import { ConfigProvider } from 'antd';
import deDE from 'antd/locale/de_DE';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { BrowserRouter } from 'react-router';

import { configureApiClient } from './api/apiClient';
import App from './App';
import { userManager } from './auth/oidcConfig';
import { CALLBACK_PATH } from './auth/paths';
import './index.css';
import { theme } from './theme';

configureApiClient(userManager);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* only the IdP callback carries OIDC parameters */}
    <AuthProvider userManager={userManager} skipSigninCallback={window.location.pathname !== CALLBACK_PATH}>
      <ConfigProvider locale={deDE} theme={theme}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ConfigProvider>
    </AuthProvider>
  </React.StrictMode>,
);
