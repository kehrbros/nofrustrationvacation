import type { User } from 'oidc-client-ts';

/** Role of the IdP that unlocks the operator view. */
export const OPERATOR_ROLE = 'admin';

function rolesOf(claims: Record<string, unknown> | undefined): string[] {
  const roles = claims?.roles;
  return Array.isArray(roles) ? roles.filter((role): role is string => typeof role === 'string') : [];
}

function accessTokenClaims(token: string | undefined): Record<string, unknown> | undefined {
  const payload = token?.split('.')[1];
  if (!payload) {
    return undefined;
  }
  try {
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return undefined;
  }
}

/** Whether the user may switch to the operator view, read from the roles claim of the ID or access token. */
export function isOperator(user: User | null | undefined): boolean {
  return [...rolesOf(user?.profile), ...rolesOf(accessTokenClaims(user?.access_token))].includes(OPERATOR_ROLE);
}
