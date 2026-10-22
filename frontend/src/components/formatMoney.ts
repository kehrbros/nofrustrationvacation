import type { MoneyDto } from '../generated/api';

/** Formats a money value of the API in German notation, e.g. "1.284,50 $". */
export function formatMoney(money: MoneyDto | undefined): string | undefined {
  if (!money) {
    return undefined;
  }
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: money.currency }).format(Number(money.amount));
}
