import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { TripsApiError } from '../../trips/tripsApi';
import Field from './Field';
import { failedField, hasErrors } from './validation';

describe('Field', () => {
  it('marks a required field with * and wires the input', () => {
    const html = renderToString(
      <Field id="trip-name" label="Name" required hint="Hinweis">
        <input type="text" />
      </Field>,
    );
    expect(html).toContain('<label for="trip-name">Name<span class="va-required" aria-hidden="true"');
    expect(html).toMatch(/<input type="text" id="trip-name" required=""[^>]*aria-describedby="trip-name-hint"/);
    expect(html).not.toContain('aria-invalid');
  });

  it('shows the error at the field and marks the input invalid', () => {
    const html = renderToString(
      <Field id="trip-to" label="Bis" error="Das Ende liegt vor dem Beginn.">
        <input type="date" />
      </Field>,
    );
    expect(html).toContain('class="va-field va-field-invalid"');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('aria-describedby="trip-to-error"');
    expect(html).toContain('<p id="trip-to-error" class="va-field-error" role="alert">Das Ende liegt vor dem Beginn.</p>');
    expect(html).not.toContain('va-required');
  });
});

describe('failedField', () => {
  const refused = (field: unknown) => new TripsApiError(400, 'INVALID_BOOKING_INPUT', { field });

  it('maps the API name of the refused field to the form field', () => {
    expect(failedField(refused('checkOut'), ['checkOutDate', 'name'], { checkOut: 'checkOutDate' })).toBe('checkOutDate');
    expect(failedField(refused('name'), ['checkOutDate', 'name'])).toBe('name');
  });

  it('ignores fields the form does not have and failures without field', () => {
    expect(failedField(refused('notes'), ['name'])).toBeNull();
    expect(failedField(refused(undefined), ['name'])).toBeNull();
    expect(failedField(new Error('offline'), ['name'])).toBeNull();
  });
});

describe('hasErrors', () => {
  it('counts only fields with a message', () => {
    expect(hasErrors({})).toBe(false);
    expect(hasErrors({ name: undefined })).toBe(false);
    expect(hasErrors({ name: 'Bitte angeben.' })).toBe(true);
  });
});
