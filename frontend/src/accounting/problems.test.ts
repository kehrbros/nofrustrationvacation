import { describe, expect, it } from 'vitest';

import { problemCode, problemMessage } from './problems';

describe('problems', () => {
  it('reads the code of problem details', () => {
    expect(problemCode({ status: 409, code: 'INSUFFICIENT_BALANCE' })).toBe('INSUFFICIENT_BALANCE');
    expect(problemCode(undefined)).toBeUndefined();
    expect(problemCode('error')).toBeUndefined();
  });

  it('translates known codes and falls back for unknown errors', () => {
    expect(problemMessage({ code: 'PAYPAL_ACCOUNT_IN_USE' })).toContain('anderen Nutzer');
    expect(problemMessage({ code: 'SOMETHING_NEW' })).toContain('nicht geklappt');
    expect(problemMessage(undefined)).toContain('nicht geklappt');
  });
});
