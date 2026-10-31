import { describe, expect, it } from 'vitest';

import { installMode, isIos } from './install';

describe('install', () => {
  it('recognizes iPhone and iPad, also iPadOS posing as Mac', () => {
    expect(isIos('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15', 5)).toBe(true);
    expect(isIos('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', 5)).toBe(true);
    expect(isIos('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', 0)).toBe(false);
    expect(isIos('Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/140.0 Mobile', 5)).toBe(false);
  });

  it('offers nothing without a browser', () => {
    expect(installMode()).toBe('none');
  });
});
