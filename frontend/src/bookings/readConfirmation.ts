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
      return { ok: false, message: 'Darin haben wir keine Unterkunftsbuchung gefunden. Du kannst sie manuell eingeben.' };
    }
    return { ok: true, filled: formFromDraft(draft) };
  } catch (failure) {
    return { ok: false, message: readErrorMessage(failure) };
  }
}

function readErrorMessage(failure: unknown): string {
  if (failure instanceof TripsApiError) {
    if (failure.code === 'ASSISTANT_RATE_LIMITED') {
      return 'Gerade zu viele Anfragen. Bitte später noch einmal versuchen oder manuell eingeben.';
    }
    if (failure.code === 'INVALID_BOOKING_INPUT') {
      return 'Diese Datei können wir nicht lesen. Bitte Text, ein Bild (JPEG, PNG, WebP) oder ein PDF bis 5 MB verwenden.';
    }
    if (failure.status === 413) {
      return 'Die Datei ist zu groß (höchstens 5 MB).';
    }
  }
  return 'Die Bestätigung konnte gerade nicht gelesen werden. Du kannst die Buchung manuell eingeben.';
}
