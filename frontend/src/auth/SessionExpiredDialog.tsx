import { useState, useSyncExternalStore } from 'react';
import { useAuth } from 'react-oidc-context';

import { t } from '../i18n';
import Sheet from '../components/Sheet';
import { loginState, sessionRejections, subscribeSession } from './session';

/**
 * Shown over the current page when the login has expired (refresh token expired or revoked). The login runs in a
 * popup, so the page with everything entered stays as it is and the user only has to save again. If the popup cannot
 * be opened (blocked, some installed apps), the login runs in this window and returns to the same page.
 */
export default function SessionExpiredDialog() {
  const auth = useAuth();
  const rejections = useSyncExternalStore(subscribeSession, sessionRejections, () => 0);
  const [dismissed, setDismissed] = useState(0);
  const [busy, setBusy] = useState(false);
  const [popupFailed, setPopupFailed] = useState(false);

  async function loginInPopup() {
    setBusy(true);
    try {
      await auth.signinPopup();
      setPopupFailed(false);
    } catch {
      setPopupFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet
      open={rejections > dismissed}
      onClose={() => setDismissed(rejections)}
      eyebrow={t('auth.session')}
      title={t('auth.loginAgain')}
      footer={
        <div className="va-actions">
          <button type="button" className="va-button va-button-accent va-block" disabled={busy} onClick={() => void loginInPopup()}>
            {busy ? t('auth.loggingIn') : t('auth.login')}
          </button>
          {popupFailed && (
            <button
              type="button"
              className="va-button va-button-outline va-block"
              onClick={() => void auth.signinRedirect({ state: loginState() })}
            >
              {t('auth.loginHere')}
            </button>
          )}
        </div>
      }
    >
      <p>{t('auth.expired')}</p>
      {popupFailed && (
        <p className="va-small va-muted" role="status">
          {t('auth.popupFailed')}
        </p>
      )}
    </Sheet>
  );
}
