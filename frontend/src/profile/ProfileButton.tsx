import { RightOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useAuth } from 'react-oidc-context';

import Sheet from '../components/Sheet';
import { InstallMenuItem } from '../pwa/InstallApp';

/** Avatar in the page header, opens the profile menu (profile, settings, logout) as a bottom sheet. */
export default function ProfileButton() {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const profile = auth.user?.profile;
  const name = profile?.name ?? profile?.preferred_username ?? profile?.sub ?? '';
  const initial = name.charAt(0).toUpperCase();

  return (
    <>
      <button
        type="button"
        className="va-avatar"
        aria-label="Profil, Einstellungen und Abmelden"
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
          <button type="button" className="va-button va-button-outline va-block" onClick={() => void auth.signoutRedirect()}>
            Abmelden
          </button>
        }
      >
        <nav aria-label="Profil" className="va-menu-list">
          <InstallMenuItem />
          {['Profil', 'Benachrichtigungen', 'Einstellungen', 'Verbundene KI-Assistenten'].map((label) => (
            <span key={label} className="va-menu-item" aria-disabled="true">
              <span>{label}</span>
              <span className="va-muted">folgt</span>
              <RightOutlined aria-hidden="true" />
            </span>
          ))}
        </nav>
      </Sheet>
    </>
  );
}
