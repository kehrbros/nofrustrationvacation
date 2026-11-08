import { t } from '../i18n';
import { TripsApiError } from '../trips/tripsApi';
import { extractAccommodation } from './bookingsApi';
import { formFromDraft, type FilledForm } from './form';
import { combineInput, type SharedContent } from './shareTarget';

/** Read result of "Bestätigung einfügen": the filled form or the message for the user. */
export type ReadResult = { ok: true; filled: FilledForm } | { ok: false; message: string };

/** Reads shared and pasted content together with the assistant (AddBookingSheet, ShareTargetPage). */
export async function readConfirmation(shared: SharedContent | null, text: string, file: File | null): Promise<ReadResult> {
  const input = combineInput(shared, text, file);
  try {
    const draft = await extractAccommodation(input.text, input.file);
    if (!draft.found) {
      return { ok: false, message: t('confirmation.notFound') };
    }
    return { ok: true, filled: formFromDraft(draft) };
  } catch (failure) {
    return { ok: false, message: readErrorMessage(failure) };
  }
}

function readErrorMessage(failure: unknown): string {
  if (failure instanceof TripsApiError) {
    if (failure.code === 'ASSISTANT_RATE_LIMITED') {
      return t('confirmation.rateLimited');
    }
    if (failure.code === 'INVALID_BOOKING_INPUT') {
      return t('confirmation.invalidInput');
    }
    if (failure.status === 413) {
      return t('confirmation.tooLarge');
    }
  }
  return t('confirmation.readFailed');
}
