import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// Same variable as the backend (vacantagent.frontend-url). Set it to serve the dev server under another host than
// localhost, e.g. http://localtest.me:5173 for the PayPal sandbox, see Docs/paypal.md.
const frontendUrl = process.env.VACANTAGENT_FRONTEND_URL;

export default defineConfig({
  plugins: [
    react(),
    // installable web app: manifest, icons and a service worker (Workbox) that caches the app shell, see
    // Docs/architecture.md#frontend
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'apple-touch-icon-180x180.png'],
      manifest: {
        name: 'NoFrustrationVacation',
        short_name: 'NoFrustrationVacation',
        description: 'Reisen planen und über Sperrungen und Störungen auf der Route informiert werden.',
        lang: 'de',
        start_url: '/reisen',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#ffffff',
        theme_color: '#ffffff',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // the map chunk is about 1 MB
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
        navigateFallback: '/index.html',
        // backend and identity provider paths must reach the server, never the cached app shell
        navigateFallbackDenylist: [/^\/api\//, /^\/oauth2\//, /^\/login/, /^\/logout/, /^\/\.well-known\//, /^\/mcp/],
        runtimeCaching: [
          {
            // map tiles and styles: last seen map also offline
            urlPattern: ({ url }) => url.hostname === 'tiles.openfreemap.org',
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'map-tiles',
              expiration: { maxEntries: 2000, maxAgeSeconds: 30 * 24 * 60 * 60 },
            },
          },
          {
            urlPattern: ({ url }) => url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com',
            handler: 'CacheFirst',
            options: { cacheName: 'fonts', expiration: { maxEntries: 20, maxAgeSeconds: 365 * 24 * 60 * 60 } },
          },
        ],
      },
    }),
  ],
  server: {
    allowedHosts: frontendUrl ? [new URL(frontendUrl).hostname] : [],
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    // fixed time zone for the date formatting tests (summer time +02:00, winter time +01:00)
    env: { TZ: 'Europe/Berlin' },
  },
});
