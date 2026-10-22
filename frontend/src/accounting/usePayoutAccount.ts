import { useCallback, useEffect, useState } from 'react';

import { getPayoutAccount, type PayoutAccountStatusDto } from '../generated/api';

/** Payout account of the user, required for offers. undefined while loading. */
export function usePayoutAccount() {
  const [status, setStatus] = useState<PayoutAccountStatusDto>();
  const [failed, setFailed] = useState(false);

  const reload = useCallback(async () => {
    const { data } = await getPayoutAccount();
    setFailed(!data);
    setStatus(data);
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { status, failed, reload };
}
