import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { getProviderOnboarding, type ProviderOnboardingDto } from '../generated/api';

interface ProviderOnboardingContextValue {
  /** undefined while loading or outside the provider view */
  status?: ProviderOnboardingDto;
  /** Call after a step changed (PayPal linked or unlinked). */
  reload: () => Promise<void>;
}

const ProviderOnboardingContext = createContext<ProviderOnboardingContextValue>({ reload: async () => {} });

/** Loads the provider onboarding checklist while {@code enabled}, i.e. in the provider view. */
export function ProviderOnboardingProvider({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  const [status, setStatus] = useState<ProviderOnboardingDto>();

  const reload = useCallback(async () => {
    const { data } = await getProviderOnboarding();
    setStatus(data);
  }, []);

  useEffect(() => {
    if (enabled) {
      void reload();
    }
  }, [enabled, reload]);

  const value = useMemo(() => ({ status: enabled ? status : undefined, reload }), [enabled, status, reload]);
  return <ProviderOnboardingContext.Provider value={value}>{children}</ProviderOnboardingContext.Provider>;
}

export function useProviderOnboarding() {
  return useContext(ProviderOnboardingContext);
}
