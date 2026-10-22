import { Flex, Spin } from 'antd';

import { usePayoutAccount } from '../../accounting/usePayoutAccount';
import PageHeader from '../../components/PageHeader';
import PayPalConnectCard from '../../components/PayPalConnectCard';
import { useProviderOnboarding } from '../../onboarding/ProviderOnboardingContext';
import { PAYOUT_ACCOUNT_PATH } from '../../role/navigation';

/** Config page of the payout account (PayPal), target of the onboarding checklist. */
export default function PayoutAccountPage() {
  const { status, reload } = usePayoutAccount();
  const onboarding = useProviderOnboarding();

  return (
    <>
      <PageHeader
        eyebrow="Einstellungen"
        title="Auszahlungskonto"
        description="Deine Einnahmen zahlen wir auf dein PayPal-Konto aus."
      />
      <div style={{ maxWidth: 680 }}>
        {status ? (
          <PayPalConnectCard
            status={status}
            returnTo={PAYOUT_ACCOUNT_PATH}
            onChanged={() => void Promise.all([reload(), onboarding.reload()])}
          />
        ) : (
          <Flex justify="center" style={{ padding: 48 }}>
            <Spin />
          </Flex>
        )}
      </div>
    </>
  );
}
