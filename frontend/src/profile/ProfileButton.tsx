import { RightOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useAuth } from 'react-oidc-context';

import Sheet from '../components/Sheet';
import { t } from '../i18n';
import LanguageSwitch from '../i18n/LanguageSwitch';
import { InstallMenuItem } from '../pwa/InstallApp';

/** Avatar in the page header, opens the profile menu (profile, settings, logout) as a bottom sheet. */
export default function ProfileButton() {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const profile = auth.user?.profile;
  const name = profile?.name ?? profile?.preferred_username ?? profile?.sub ?? '';
  const initial = name.charAt(0).toUpperCase();

  async function logout() {
    try {
      await auth.revokeTokens();
    } catch {
      // the refresh token expires on its own, logging out must not fail because of it
    }
    await auth.signoutRedirect();
  }

  return (
    <>
      <button
        type="button"
        className="va-avatar"
        aria-label={t('profile.open')}
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
      >
        {initial}
      </button>
      <Sheet
        open={open}
        onClose={() => setOpen(false)}
        icon={<span className="va-avatar va-avatar-large">{initial}</span>}
        title={name}
        eyebrow={profile?.email}
        footer={
          <button type="button" className="va-button va-button-outline va-block" onClick={() => void logout()}>
            {t('profile.logout')}
          </button>
        }
      >
        <nav aria-label={t('profile.menu')} className="va-menu-list">
          <InstallMenuItem />
          <LanguageSwitch />
          {[t('profile.profile'), t('profile.notifications'), t('profile.settings'), t('profile.assistants')].map((label) => (
            <span key={label} className="va-menu-item" aria-disabled="true">
              <span>{label}</span>
              <span className="va-muted">{t('profile.comingSoon')}</span>
              <RightOutlined aria-hidden="true" />
            </span>
          ))}
        </nav>
      </Sheet>
    </>
  );
}
