/**
 * Share target of the installed app (Android/Chromium; Safari on iOS has none): the service worker
 * (public/share-target-sw.js) receives the POST to /teilen, keeps text and file in the cache "share-target" and
 * opens /teilen, which reads them here. Needed because the POST carries no access token.
 */

export const SHARE_PATH = '/teilen';
const CACHE = 'share-target';
const PAYLOAD = '/teilen/payload';
const FILE = '/teilen/file';

export interface SharedContent {
  /** Title, text and URL of the share, joined. */
  text: string;
  file: File | null;
}

/** What was shared last, null if nothing (or the browser has no Cache API). */
export async function readShared(): Promise<SharedContent | null> {
  if (typeof caches === 'undefined') {
    return null;
  }
  const cache = await caches.open(CACHE);
  const payload = await cache.match(PAYLOAD);
  if (!payload) {
    return null;
  }
  const { title, text, url } = (await payload.json()) as { title?: string; text?: string; url?: string };
  const fileResponse = await cache.match(FILE);
  let file: File | null = null;
  if (fileResponse) {
    const blob = await fileResponse.blob();
    const name = decodeURIComponent(fileResponse.headers.get('X-Filename') ?? 'geteilt');
    file = new File([blob], name, { type: fileResponse.headers.get('Content-Type') ?? blob.type });
  }
  const joined = [title, text, url].filter((part) => part && part.trim()).join('\n');
  return joined || file ? { text: joined, file } : null;
}

/**
 * What goes to the assistant: the shared text and the pasted text together (e.g. a shared link plus the pasted
 * confirmation), and one file, the chosen one before the shared one.
 */
export function combineInput(shared: SharedContent | null, text: string, file: File | null): { text: string; file: File | null } {
  return {
    text: [shared?.text, text]
      .map((part) => part?.trim())
      .filter(Boolean)
      .join('\n\n'),
    file: file ?? shared?.file ?? null,
  };
}

/** Removes the shared content, it is never kept longer than needed. */
export async function clearShared(): Promise<void> {
  if (typeof caches !== 'undefined') {
    await caches.delete(CACHE);
  }
}
