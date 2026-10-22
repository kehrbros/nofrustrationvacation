import { Button, Card, Empty } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';

import BalanceHero from '../../components/BalanceHero';
import { formatMoney } from '../../components/formatMoney';
import { getBalances, type BalancesDto } from '../../generated/api';

/**
 * Start page of the provider: earnings. Open onboarding steps are shown by the layout's checklist.
 */
export default function ProviderHome() {
  const navigate = useNavigate();
  const [balances, setBalances] = useState<BalancesDto>();

  useEffect(() => {
    void getBalances().then(({ data }) => setBalances(data));
  }, []);

  return (
    <>
      <BalanceHero
        greeting="Deine Kapazität arbeitet."
        amount={formatMoney(balances?.earnings)}
        caption="auszahlbare Einnahmen"
        actions={
          <>
            <Button className="va-accent" size="large" onClick={() => navigate('/provider/angebote/neu')}>
              Neues Angebot
            </Button>
            <Button size="large" ghost onClick={() => navigate('/provider/einnahmen')}>
              Auszahlen
            </Button>
          </>
        }
      />
      <Card title="Meine Angebote">
        <Empty description="Noch keine Angebote" />
      </Card>
    </>
  );
}
