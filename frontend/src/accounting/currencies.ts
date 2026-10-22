/** Currencies users can pay and receive in, keep in sync with vacantagent.money.currencies of the backend. */
export const SUPPORTED_CURRENCIES = ['EUR', 'USD', 'GBP', 'CHF'] as const;

export const DEFAULT_CURRENCY = 'EUR';

/** Currency symbol in German notation, e.g. "€" for EUR. */
export function currencySymbol(currency: string): string {
  return (
    new Intl.NumberFormat('de-DE', { style: 'currency', currency, currencyDisplay: 'narrowSymbol' })
      .formatToParts(0)
      .find((part) => part.type === 'currency')?.value ?? currency
  );
}
