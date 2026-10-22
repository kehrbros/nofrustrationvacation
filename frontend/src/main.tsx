import { ConfigProvider } from 'antd';
import deDE from 'antd/locale/de_DE';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { BrowserRouter } from 'react-router';

import { configureApiClient } from './api/apiClient';
import App from './App';
import { userManager } from './auth/oidcConfig';
import './index.css';
import { RoleProvider } from './role/RoleContext';
import { theme } from './theme';

configureApiClient(userManager);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthProvider userManager={userManager}>
      <ConfigProvider locale={deDE} theme={theme}>
        <RoleProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </RoleProvider>
      </ConfigProvider>
    </AuthProvider>
  </React.StrictMode>,
);
