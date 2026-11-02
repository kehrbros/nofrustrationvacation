/*
 * Share target of the installed app, imported into the Workbox service worker (vite.config.ts, workbox.importScripts).
 * Android shares a confirmation (text, URL, screenshot or PDF) as POST to /teilen. The POST carries no access token,
 * so the content goes into the cache "share-target" and /teilen (src/bookings/ShareTargetPage.tsx) reads it after
 * login. See Docs/architecture.md#frontend.
 */
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'POST' || url.origin !== self.location.origin || url.pathname !== '/teilen') {
    return;
  }
  event.respondWith(
    (async () => {
      const data = await event.request.formData();
      const cache = await caches.open('share-target');
      const text = (name) => (typeof data.get(name) === 'string' ? data.get(name) : '');
      await cache.put(
        '/teilen/payload',
        new Response(JSON.stringify({ title: text('title'), text: text('text'), url: text('url') }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
      const file = data.getAll('files').find((entry) => entry instanceof File && entry.size > 0);
      if (file) {
        await cache.put(
          '/teilen/file',
          new Response(file, { headers: { 'Content-Type': file.type, 'X-Filename': encodeURIComponent(file.name) } }),
        );
      } else {
        await cache.delete('/teilen/file');
      }
      return Response.redirect('/teilen?geteilt=1', 303);
    })(),
  );
});
