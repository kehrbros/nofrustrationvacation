import { completeTodo, listTodos, reopenTodo } from '../generated/api';
import type { BookingTodoDto } from '../generated/api';
import { TripsApiError } from '../trips/tripsApi';

/** To-do list of a trip: bookings to cancel or to tell about a later arrival, see Docs/domain.md#bookings. */

export type TodoKind = 'CANCEL' | 'NOTIFY_LATE_ARRIVAL';
export type TodoReason = 'REPLACED' | 'STOP_REMOVED' | 'ARRIVAL_LATER';

export interface BookingTodo {
  id: number;
  /** null once the booking is deleted, e.g. with its stop. */
  bookingId: number | null;
  kind: TodoKind;
  reason: TodoReason;
  title: string;
  provider: string | null;
  reference: string | null;
  manageUrl: string | null;
  phone: string | null;
  email: string | null;
  startsAt: string | null;
  endsAt: string | null;
  cancellationDeadline: string | null;
  bookedInApp: boolean;
  /** yyyy-mm-dd */
  newArrivalOn: string | null;
  doneAt: string | null;
}

async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response?: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (error !== undefined || !response?.ok) {
    const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : undefined;
    throw new TripsApiError(response?.status, code);
  }
  return data as T;
}

function toTodo(dto: BookingTodoDto): BookingTodo {
  return {
    id: dto.id ?? 0,
    bookingId: dto.bookingId ?? null,
    kind: (dto.kind ?? 'CANCEL') as TodoKind,
    reason: (dto.reason ?? 'REPLACED') as TodoReason,
    title: dto.title ?? '',
    provider: dto.provider ?? null,
    reference: dto.reference ?? null,
    manageUrl: dto.manageUrl ?? null,
    phone: dto.phone ?? null,
    email: dto.email ?? null,
    startsAt: dto.startsAt ?? null,
    endsAt: dto.endsAt ?? null,
    cancellationDeadline: dto.cancellationDeadline ?? null,
    bookedInApp: dto.bookedInApp ?? false,
    newArrivalOn: dto.newArrivalOn ?? null,
    doneAt: dto.doneAt ?? null,
  };
}

export async function fetchTodos(tripId: number): Promise<BookingTodo[]> {
  return (await unwrap(listTodos({ path: { tripId } }))).map(toTodo);
}

/** Done; a cancellation also marks the booking as cancelled in the app. */
export async function markTodoDone(todoId: number): Promise<BookingTodo> {
  return toTodo(await unwrap(completeTodo({ path: { todoId } })));
}

export async function reopenTodoAgain(todoId: number): Promise<BookingTodo> {
  return toTodo(await unwrap(reopenTodo({ path: { todoId } })));
}

const reasonTexts: Record<TodoReason, string> = {
  REPLACED: 'Ersetzt durch eine neue Unterkunft',
  STOP_REMOVED: 'Der Stop wurde gelöscht',
  ARRIVAL_LATER: 'Du kommst später an',
};

export function todoReasonText(todo: BookingTodo): string {
  return reasonTexts[todo.reason];
}

/** "Bei Booking.com stornieren", "Hotel Adler informieren" */
export function todoActionText(todo: BookingTodo): string {
  if (todo.kind === 'NOTIFY_LATE_ARRIVAL') {
    return `${todo.title} informieren`;
  }
  if (todo.bookedInApp) {
    return `${todo.title} stornieren`;
  }
  return todo.provider ? `Bei ${todo.provider} stornieren` : `${todo.title} stornieren`;
}
