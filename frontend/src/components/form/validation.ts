import { useEffect, useState } from 'react';

import { t } from '../../i18n';
import { TripsApiError } from '../../trips/tripsApi';

/** Message per field of a form (current language); a field without entry is fine. */
export type FieldErrors<F extends string = string> = Partial<Record<F, string>>;

export function hasErrors(errors: FieldErrors): boolean {
  return Object.values(errors).some(Boolean);
}

/**
 * The field the backend refused (problem detail "field" of INVALID_TRIP_INPUT / INVALID_BOOKING_INPUT), translated
 * to the form's field by `map` (API name → form field, unmapped names are taken as they are); null if it named none
 * or the form has no such field.
 */
export function failedField<F extends string>(failure: unknown, fields: readonly F[], map: Partial<Record<string, F>> = {}): F | null {
  const field = failure instanceof TripsApiError ? failure.body?.field : undefined;
  if (typeof field !== 'string') {
    return null;
  }
  const mapped = map[field] ?? field;
  return (fields as readonly string[]).includes(mapped) ? mapped as F : null;
}

const TOUCH_DELAY_MS = 300;

interface Refused<F extends string> {
  field: F;
  message: string;
  value: unknown;
}

/**
 * Validation feedback of a form, the same everywhere (Docs/i18n.md#forms): `all` holds what is wrong now; a field
 * shows its error once it was left (`touch`) or the user tried to submit (`check`). A field the backend refused
 * (`refuse`) shows the backend's verdict until its value changes. A failed `check` moves the focus to the first
 * invalid field.
 */
export function useFieldErrors<F extends string>(all: FieldErrors<F>, values: Partial<Record<F, unknown>>) {
  const [touched, setTouched] = useState<Partial<Record<F, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [refused, setRefused] = useState<Refused<F> | null>(null);
  const [focusRun, setFocusRun] = useState(0);

  useEffect(() => {
    if (focusRun === 0) {
      return;
    }
    // the form in front: the sheet opened last (e.g. the booking over the stop), else the page
    const sheets = document.querySelectorAll('.ant-drawer-open');
    const root: ParentNode = sheets.length ? sheets[sheets.length - 1] : document;
    const invalid = root.querySelector<HTMLElement>('[aria-invalid="true"]');
    invalid?.focus();
    invalid?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
  }, [focusRun]);

  const errors: FieldErrors<F> = {};
  for (const field of Object.keys(all) as F[]) {
    if (all[field] && (submitted || touched[field])) {
      errors[field] = all[field];
    }
  }
  const stillRefused = refused !== null && Object.is(values[refused.field], refused.value);
  if (stillRefused && !errors[refused.field]) {
    errors[refused.field] = refused.message;
  }

  return {
    /** Errors to show now, per field. */
    errors,
    /**
     * The user left the field. Its error shows a moment later: leaving a field by tapping the submit button must not
     * move the button away (error text above it) before the tap ends.
     */
    touch: (field: F) => {
      window.setTimeout(() => setTouched((current) => (current[field] ? current : { ...current, [field]: true })), TOUCH_DELAY_MS);
    },
    /** Before submitting: true if the form can be sent, otherwise all errors show and the first one gets the focus. */
    check: (): boolean => {
      setSubmitted(true);
      const ok = !hasErrors(all) && !stillRefused;
      if (!ok) {
        setFocusRun((run) => run + 1);
      }
      return ok;
    },
    /** The backend refused the field; shown until the user changes it. */
    refuse: (field: F, message: string = t('validation.refused')) => {
      setRefused({ field, message, value: values[field] });
      setFocusRun((run) => run + 1);
    },
    /** Starts over, e.g. for a new entry in the same form. */
    reset: () => {
      setTouched({});
      setSubmitted(false);
      setRefused(null);
    },
  };
}

export type FieldValidation<F extends string> = ReturnType<typeof useFieldErrors<F>>;
