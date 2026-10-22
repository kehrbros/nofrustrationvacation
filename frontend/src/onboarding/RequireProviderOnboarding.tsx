import { Flex, Spin } from 'antd';
import type { ReactNode } from 'react';

import PageHeader from '../components/PageHeader';
import OnboardingChecklist from './OnboardingChecklist';
import { useProviderOnboarding } from './ProviderOnboardingContext';

/**
 * Renders its children only once the provider onboarding is complete, otherwise the open steps. The backend refuses
 * offers before that as well (403).
 */
export default function RequireProviderOnboarding({ title, children }: { title: string; children: ReactNode }) {
  const { status } = useProviderOnboarding();

  if (!status) {
    return (
      <Flex justify="center" style={{ padding: 48 }}>
        <Spin />
      </Flex>
    );
  }
  if (!status.complete) {
    return (
      <>
        <PageHeader title={title} description="Bevor du Kapazität anbietest, fehlen noch ein paar Schritte." />
        <div style={{ maxWidth: 420 }}>
          <OnboardingChecklist status={status} />
        </div>
      </>
    );
  }
  return children;
}
