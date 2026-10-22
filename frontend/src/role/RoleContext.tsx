import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

/** View the user is working in. Operator is only available to users with the operator role. */
export type Role = 'consumer' | 'provider' | 'operator';

const STORAGE_KEY = 'vacantagent.role';

interface RoleContextValue {
  /** Selected role, undefined until the user picked a primary role in the onboarding. */
  role?: Role;
  setRole: (role: Role) => void;
}

const RoleContext = createContext<RoleContextValue | undefined>(undefined);

function readStoredRole(): Role | undefined {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored === 'consumer' || stored === 'provider' || stored === 'operator' ? stored : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Keeps the selected role. It is stored in the browser for now, until the backend stores the primary role.
 */
export function RoleProvider({ children, initialRole }: { children: ReactNode; initialRole?: Role }) {
  const [role, setRoleState] = useState<Role | undefined>(() => initialRole ?? readStoredRole());

  const setRole = useCallback((next: Role) => {
    setRoleState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // the role then only lasts until the next reload
    }
  }, []);

  const value = useMemo(() => ({ role, setRole }), [role, setRole]);
  return <RoleContext.Provider value={value}>{children}</RoleContext.Provider>;
}

export function useRole() {
  const context = useContext(RoleContext);
  if (!context) {
    throw new Error('useRole must be used inside a RoleProvider');
  }
  return context;
}
