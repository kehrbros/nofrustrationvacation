import { describe, expect, it } from 'vitest';

import { todoActionText, type BookingTodo } from './todosApi';

function todo(patch: Partial<BookingTodo>): BookingTodo {
  return {
    id: 1, bookingId: 2, kind: 'CANCEL', reason: 'REPLACED', title: 'Hotel Adler', provider: 'Booking.com',
    reference: '4711', manageUrl: null, phone: null, email: null, startsAt: null, endsAt: null,
    cancellationDeadline: null, bookedInApp: false, cancellableInApp: false, newArrivalOn: null, doneAt: null, ...patch,
  };
}

describe('todoActionText', () => {
  it('names the provider to cancel at', () => {
    expect(todoActionText(todo({}))).toBe('Bei Booking.com stornieren');
    expect(todoActionText(todo({ provider: null }))).toBe('Hotel Adler stornieren');
  });

  it('booked in the app or a late arrival', () => {
    expect(todoActionText(todo({ bookedInApp: true }))).toBe('Hotel Adler stornieren');
    expect(todoActionText(todo({ kind: 'NOTIFY_LATE_ARRIVAL' }))).toBe('Hotel Adler informieren');
  });
});
