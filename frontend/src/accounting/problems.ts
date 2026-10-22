/** Error codes of the accounting API (field "code" of the problem details), see Docs/accounting.md. */
const messages: Record<string, string> = {
  INSUFFICIENT_BALANCE: 'Dein Guthaben reicht dafür nicht aus.',
  AMOUNT_OUT_OF_RANGE: 'Der Betrag liegt außerhalb der erlaubten Grenzen.',
  INVALID_AMOUNT: 'Bitte gib einen gültigen Betrag ein.',
  PAYOUT_ACCOUNT_REQUIRED: 'Verknüpfe zuerst dein PayPal-Konto.',
  LINK_STATE_INVALID: 'Der Link ist abgelaufen. Bitte starte die Verknüpfung erneut.',
  PAYPAL_ACCOUNT_IN_USE: 'Dieses PayPal-Konto ist bereits mit einem anderen Nutzer verknüpft.',
  PAYOUTS_OPEN: 'Solange Auszahlungen laufen, kannst du das Konto nicht trennen.',
  TOP_UP_NOT_FOUND: 'Diese Zahlung kennen wir nicht.',
  PAYMENT_NOT_APPROVED: 'Die Zahlung wurde bei PayPal noch nicht bestätigt.',
  PAYMENT_DECLINED: 'PayPal hat die Zahlung abgelehnt. Versuche es mit einer anderen Zahlungsquelle.',
  PAYPAL_NOT_CONFIGURED: 'PayPal ist gerade nicht verfügbar.',
  PAYPAL_ERROR: 'PayPal hat die Anfrage nicht verarbeitet. Versuche es später noch einmal.',
  EXCHANGE_RATE_UNAVAILABLE: 'Für diese Währung gibt es gerade keinen aktuellen Kurs. Wähle eine andere Währung.',
};

/** Code of a problem detail returned by the API, if any. */
export function problemCode(error: unknown): string | undefined {
  if (error && typeof error === 'object' && 'code' in error && typeof error.code === 'string') {
    return error.code;
  }
  return undefined;
}

/** Message for the user, a generic one for unknown errors. */
export function problemMessage(error: unknown): string {
  const code = problemCode(error);
  return (code && messages[code]) ?? 'Das hat nicht geklappt. Bitte versuche es noch einmal.';
}
