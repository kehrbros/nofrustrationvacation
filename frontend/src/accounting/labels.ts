import type { LedgerEntryDto, PayoutDto, TopUpDto } from '../generated/api';

export const entryTypeLabel: Record<LedgerEntryDto['type'], string> = {
  TOP_UP: 'Aufladung',
  PAYOUT: 'Auszahlung',
  PAYOUT_REVERSAL: 'Auszahlung zurückgebucht',
  USAGE_CHARGE: 'Nutzung',
  USAGE_EARNING: 'Verkaufte Tokens',
  OPENING_BALANCE: 'Übernommenes Guthaben',
};

export const topUpStatus: Record<TopUpDto['status'], { label: string; color: string }> = {
  CREATED: { label: 'offen', color: 'default' },
  PENDING: { label: 'wird geprüft', color: 'processing' },
  COMPLETED: { label: 'gutgeschrieben', color: 'success' },
  CANCELLED: { label: 'abgebrochen', color: 'default' },
  FAILED: { label: 'fehlgeschlagen', color: 'error' },
};

export const payoutStatus: Record<PayoutDto['status'], { label: string; color: string }> = {
  REQUESTED: { label: 'angefordert', color: 'processing' },
  SUBMITTED: { label: 'in Bearbeitung', color: 'processing' },
  COMPLETED: { label: 'ausgezahlt', color: 'success' },
  FAILED: { label: 'zurückgebucht', color: 'error' },
  UNKNOWN: { label: 'wird geprüft', color: 'warning' },
};

export function formatDate(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' }) : '–';
}
