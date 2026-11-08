import i18next from 'i18next';

import { de } from './de';
import { en } from './en';

/**
 * Languages of the UI, see Docs/i18n.md. German is the source language: every key is defined in de.ts first, en.ts
 * has the same shape (checked by the compiler).
 */
export const LANGUAGES = ['de', 'en'] as const;
export type Language = (typeof LANGUAGES)[number];

const STORAGE_KEY = 'nfv.language';
/** For texts missing in a language (none, the compiler checks): German is the source. */
const FALLBACK: Language = 'de';
/** UI language when the system language is neither German nor English. */
const DEFAULT: Language = 'en';

function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (LANGUAGES as readonly string[]).includes(value);
}

/** Chosen language from the local storage, otherwise the language of the system (browser), otherwise English. */
export function detectLanguage(): Language {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (isLanguage(stored)) {
      return stored;
    }
  } catch {
    // storage blocked: use the browser language
  }
  const preferred = globalThis.navigator?.languages ?? [globalThis.navigator?.language];
  for (const tag of preferred) {
    const base = tag?.slice(0, 2).toLowerCase();
    if (isLanguage(base)) {
      return base;
    }
  }
  return DEFAULT;
}

export const i18n = i18next.createInstance();

void i18n.init({
  // resources are bundled, so the instance is ready right after this call
  initAsync: false,
  resources: { de: { translation: de }, en: { translation: en } },
  lng: detectLanguage(),
  fallbackLng: FALLBACK,
  supportedLngs: LANGUAGES,
  // React escapes, texts are never set as HTML
  interpolation: { escapeValue: false },
  returnNull: false,
});

/** Translation of a key from de.ts, e.g. t('trips.title') or t('trips.stops', { count: 3 }). */
export const t = i18n.t.bind(i18n);

export function currentLanguage(): Language {
  return isLanguage(i18n.language) ? i18n.language : FALLBACK;
}

/** BCP 47 locale for Intl number and date formats of the current language. */
export function locale(): string {
  return currentLanguage() === 'de' ? 'de-DE' : 'en-GB';
}

/** Switches the language and remembers it on the device; the app is rendered again (main.tsx). */
export function setLanguage(language: Language): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, language);
  } catch {
    // only for this visit then
  }
  void i18n.changeLanguage(language);
}

/** "412,50 €" / "€412.50"; amount and currency as written when the currency is unknown. */
export function formatMoney(amount: string | number, currency: string): string {
  try {
    return new Intl.NumberFormat(locale(), { style: 'currency', currency }).format(Number(amount));
  } catch {
    return `${amount} ${currency}`;
  }
}

/** "4,2" / "4.2" */
export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return value.toLocaleString(locale(), options);
}
