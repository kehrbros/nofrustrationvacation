import { useEffect, useState } from 'react';

/**
 * Installing the web app from a button. Chromium browsers (Android, desktop) fire `beforeinstallprompt` when the app
 * is installable; the event is kept and its prompt() shown on click. Safari on iOS has no such event, there only
 * "Teilen → Zum Home-Bildschirm" works, so the UI shows instructions instead.
 */

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferredPrompt: BeforeInstallPromptEvent | null = null;
let installed = false;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((listener) => listener());

/** Registers the listeners; call once at startup, the event can fire before React has rendered. */
export function listenForInstallPrompt() {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    deferredPrompt = event as BeforeInstallPromptEvent;
    notify();
  });
  window.addEventListener('appinstalled', () => {
    deferredPrompt = null;
    installed = true;
    notify();
  });
}

/** Opened as installed app (home screen icon), not in a browser tab. */
export function isStandalone(): boolean {
  return (
    window.matchMedia?.('(display-mode: standalone)').matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

/** iPhone, iPod or iPad (iPadOS reports itself as Mac, but with touch). */
export function isIos(userAgent = navigator.userAgent, maxTouchPoints = navigator.maxTouchPoints): boolean {
  return /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && maxTouchPoints > 1);
}

export type InstallMode =
  /** Browser offers its install dialog. */
  | 'prompt'
  /** iOS: only manually via the share menu. */
  | 'ios'
  /** Installed, already running as app, or the browser offers nothing. */
  | 'none';

export function installMode(): InstallMode {
  if (typeof window === 'undefined' || installed || isStandalone()) {
    return 'none';
  }
  if (deferredPrompt) {
    return 'prompt';
  }
  return isIos() ? 'ios' : 'none';
}

/** Shows the install dialog of the browser, true if the user accepted. */
export async function promptInstall(): Promise<boolean> {
  const event = deferredPrompt;
  if (!event) {
    return false;
  }
  // the event can be used only once
  deferredPrompt = null;
  notify();
  await event.prompt();
  const { outcome } = await event.userChoice;
  return outcome === 'accepted';
}

export function useInstallMode(): InstallMode {
  const [mode, setMode] = useState<InstallMode>('none');
  useEffect(() => {
    const update = () => setMode(installMode());
    update();
    listeners.add(update);
    return () => {
      listeners.delete(update);
    };
  }, []);
  return mode;
}
