import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same variable as the backend (vacantagent.frontend-url). Set it to serve the dev server under another host than
// localhost, e.g. http://localtest.me:5173 for the PayPal sandbox, see Docs/paypal.md.
const frontendUrl = process.env.VACANTAGENT_FRONTEND_URL;

export default defineConfig({
  plugins: [react()],
  server: {
    allowedHosts: frontendUrl ? [new URL(frontendUrl).hostname] : [],
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
