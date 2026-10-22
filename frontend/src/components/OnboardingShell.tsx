import { Layout } from 'antd';
import type { ReactNode } from 'react';

import { brand } from '../theme';

/** Full-page frame of the onboarding steps: logo bar, centered content. */
export default function OnboardingShell({ children }: { children: ReactNode }) {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ borderBottom: `2px solid ${brand.ink}`, display: 'flex', alignItems: 'center' }}>
        <span style={{ fontWeight: 800, fontSize: 24, letterSpacing: '-0.01em' }}>VacantAgent</span>
      </Layout.Header>
      <Layout.Content
        style={{ width: '100%', maxWidth: 1000, margin: '0 auto', padding: '56px 32px', boxSizing: 'border-box' }}
      >
        {children}
      </Layout.Content>
    </Layout>
  );
}
