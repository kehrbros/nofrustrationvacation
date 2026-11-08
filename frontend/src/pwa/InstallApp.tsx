import { DownloadOutlined, PlusSquareOutlined, UploadOutlined } from '@ant-design/icons';
import { useState } from 'react';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
import Rich from '../i18n/Rich';
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
      title={t('install.title')}
      footer={
        <button type="button" className="va-button va-button-accent va-block" onClick={onClose}>
          {t('install.understood')}
        </button>
      }
    >
      <p className="va-muted">{t('install.iosIntro')}</p>
      <ol className="va-steps">
        <li>
          <UploadOutlined aria-hidden="true" /> <Rich text={t('install.iosShare')} />
        </li>
        <li>
          <PlusSquareOutlined aria-hidden="true" /> <Rich text={t('install.iosHomeScreen')} />
        </li>
        <li>
          <Rich text={t('install.iosAdd')} />
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
        <span>{t('install.title')}</span>
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
    <section className="va-install" aria-label={t('install.title')}>
      <img src="/pwa-192x192.png" alt="" width={44} height={44} />
      <span className="va-column va-grow">
        <span className="va-strong">{t('install.banner')}</span>
        <span className="va-small va-muted">{t('install.bannerHint')}</span>
      </span>
      <button
        type="button"
        className="va-button va-button-accent va-button-small"
        onClick={() => (mode === 'prompt' ? void promptInstall() : setInstructions(true))}
      >
        {t('install.install')}
      </button>
      <button type="button" className="va-install-close" aria-label={t('install.dismiss')} onClick={dismiss}>
        ×
      </button>
      <IosInstructions open={instructions} onClose={() => setInstructions(false)} />
    </section>
  );
}
