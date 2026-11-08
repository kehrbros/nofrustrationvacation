import { afterEach, describe, expect, it } from 'vitest';

import { formatAgo, formatPeriod } from '../trips/time';
import { de } from './de';
import { en } from './en';
import { detectLanguage, i18n, t } from '.';

/** All keys of a message tree, e.g. "trips.title". */
function keys(tree: object, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'string' ? [`${prefix}${key}`] : keys(value as object, `${prefix}${key}.`),
  );
}

/** Placeholders of a text, e.g. ["count"] for "{{count}} Stops". */
function placeholders(text: string): string[] {
  return [...text.matchAll(/\{\{(\w+)\}\}/g)].map((match) => match[1]).sort();
}

function lookup(tree: object, key: string): string {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], tree) as string;
}

describe('translations', () => {
  afterEach(() => void i18n.changeLanguage('de'));

  it('have the same keys in German and English', () => {
    expect(keys(en).sort()).toEqual(keys(de).sort());
  });

  it('use the same placeholders and no empty texts', () => {
    for (const key of keys(de)) {
      expect(lookup(en, key).trim(), key).not.toBe('');
      expect(placeholders(lookup(en, key)), key).toEqual(placeholders(lookup(de, key)));
    }
  });

  it('switches texts, plurals and formats with the language', () => {
    expect(t('common.stops', { count: 2 })).toBe('2 Stops');
    void i18n.changeLanguage('en');
    expect(t('common.stops', { count: 1 })).toBe('1 stop');
    expect(t('trips.title')).toBe('My trips');
    expect(formatPeriod('2027-07-12T00:00:00+02:00', '2027-07-26T23:59:59+02:00')).toBe('12 – 26 July 2027');
    expect(formatAgo('2027-07-14T09:40:00+02:00', new Date('2027-07-14T10:00:00+02:00'))).toBe('20 min ago');
  });
});

describe('language detection', () => {
  it('takes the system language if it is German or English, otherwise English', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
    const withLanguages = (languages: string[]) =>
      Object.defineProperty(globalThis, 'navigator', { value: { languages, language: languages[0] }, configurable: true });
    try {
      withLanguages(['de-AT']);
      expect(detectLanguage()).toBe('de');
      withLanguages(['fr-FR', 'en-US']);
      expect(detectLanguage()).toBe('en');
      withLanguages(['fr-FR']);
      expect(detectLanguage()).toBe('en');
    } finally {
      if (original) Object.defineProperty(globalThis, 'navigator', original);
    }
  });
});
