import { describe, expect, it } from 'vitest';

import { sdkUrl } from './paypalSdk';

describe('sdkUrl', () => {
  it('matches the order: authorize, currency of the reservation, buttons only', () => {
    const url = new URL(sdkUrl({ clientId: 'abc', currency: 'nzd', intent: 'authorize' }));
    expect(url.origin + url.pathname).toBe('https://www.paypal.com/sdk/js');
    expect(url.searchParams.get('client-id')).toBe('abc');
    expect(url.searchParams.get('currency')).toBe('NZD');
    expect(url.searchParams.get('intent')).toBe('authorize');
    expect(url.searchParams.get('components')).toBe('buttons');
    expect(url.searchParams.get('commit')).toBe('true');
  });
});
