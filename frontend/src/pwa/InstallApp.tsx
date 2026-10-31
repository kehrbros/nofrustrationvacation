import { DownloadOutlined, PlusSquareOutlined, UploadOutlined } from '@ant-design/icons';
import { useState } from 'react';

import Sheet from '../components/Sheet';
import { promptInstall, useInstallMode } from './install';

const DISMISSED_KEY = 'va.install-banner-dismissed';

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === '1';
  } catch {
    return false;
  }
}

/** Steps for Safari on iOS, which has no install dialog. */
export function IosInstructions({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Sheet
      open={open}
      onClose={onClose}
      title="App installieren"
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={onClose}>
          Verstanden
        </button>
      }
    >
      <p className="va-muted">Auf iPhone und iPad geht das über das Teilen-Menü:</p>
      <ol className="va-steps">
        <li>
          <UploadOutlined aria-hidden="true" /> Tippe unten in Safari auf <strong>Teilen</strong>.
        </li>
        <li>
          <PlusSquareOutlined aria-hidden="true" /> Wähle <strong>Zum Home-Bildschirm</strong>.
        </li>
        <li>
          Tippe oben rechts auf <strong>Hinzufügen</strong>.
        </li>
      </ol>
    </Sheet>
  );
}

/** Entry for the profile menu: installs the app or shows the iOS steps; hidden if not possible or installed. */
export function InstallMenuItem() {
  const mode = useInstallMode();
  const [instructions, setInstructions] = useState(false);
  if (mode === 'none') {
    return null;
  }
  return (
    <>
      <button
        type="button"
        className="va-menu-item va-menu-action"
        onClick={() => (mode === 'prompt' ? void promptInstall() : setInstructions(true))}
      >
        <span>App installieren</span>
        <DownloadOutlined aria-hidden="true" />
      </button>
      <IosInstructions open={instructions} onClose={() => setInstructions(false)} />
    </>
  );
}

/** Hint on the trip list to install the app, can be dismissed for good. */
export function InstallBanner() {
  const mode = useInstallMode();
  const [dismissed, setDismissed] = useState(readDismissed);
  const [instructions, setInstructions] = useState(false);
  if (mode === 'none' || dismissed) {
    return null;
  }
  const dismiss = () => {
    setDismissed(true);
    try {
      localStorage.setItem(DISMISSED_KEY, '1');
    } catch {
      // private mode: hidden for this visit only
    }
  };
  return (
    <section className="va-install" aria-label="App installieren">
      <img src="/pwa-192x192.png" alt="" width={44} height={44} />
      <span className="va-column va-grow">
        <span className="va-strong">Als App nutzen</span>
        <span className="va-small va-muted">Start vom Home-Bildschirm</span>
      </span>
      <button
        type="button"
        className="va-button va-button-accent va-button-small"
        onClick={() => (mode === 'prompt' ? void promptInstall() : setInstructions(true))}
      >
        Installieren
      </button>
      <button type="button" className="va-install-close" aria-label="Hinweis ausblenden" onClick={dismiss}>
        ×
      </button>
      <IosInstructions open={instructions} onClose={() => setInstructions(false)} />
    </section>
  );
}
