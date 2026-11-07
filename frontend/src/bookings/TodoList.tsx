import { CheckOutlined, MailOutlined, PhoneOutlined, UndoOutlined } from '@ant-design/icons';
import { Alert, Popconfirm } from 'antd';
import { useEffect, useState } from 'react';

import { cancelWithRefund } from './alternativesApi';
import { formatLocalDateTime, formatStay } from './localTime';
import { fetchTodos, markTodoDone, reopenTodoAgain, todoActionText, todoReasonText, type BookingTodo } from './todosApi';
import { formatShortDate } from '../proposals/proposalText';

/**
 * To-do list of the trip: bookings that are not needed any more after the trip changed (cancel them at the
 * provider) and accommodations that expect the user earlier. Nothing is cancelled automatically. Done ones stay
 * below, struck through, until the trip is gone.
 */
export default function TodoList({ tripId, version, onChanged, compact, onExpand }: {
  tripId: number;
  /** Changes when the trip was loaded again. */
  version: unknown;
  onChanged: () => void;
  /** Only a line with the number of open to-dos, tapping it calls onExpand. */
  compact?: boolean;
  onExpand?: () => void;
}) {
  const [todos, setTodos] = useState<BookingTodo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showDone, setShowDone] = useState(false);

  useEffect(() => {
    let active = true;
    fetchTodos(tripId).then((loaded) => active && setTodos(loaded), () => undefined);
    return () => {
      active = false;
    };
  }, [tripId, version]);

  const replace = (todo: BookingTodo) => setTodos((current) => current.map((candidate) => (candidate.id === todo.id ? todo : candidate)));
  const done = async (todo: BookingTodo) => {
    try {
      replace(await markTodoDone(todo.id));
      onChanged();
    } catch {
      setError('Das hat nicht geklappt. Bitte versuche es noch einmal.');
    }
  };
  const reopen = async (todo: BookingTodo) => {
    try {
      replace(await reopenTodoAgain(todo.id));
    } catch {
      setError('Das hat nicht geklappt. Bitte versuche es noch einmal.');
    }
  };
  const cancelInApp = async (todo: BookingTodo) => {
    if (todo.bookingId == null) return;
    try {
      await cancelWithRefund(todo.bookingId);
      setTodos(await fetchTodos(tripId));
      onChanged();
    } catch {
      setError('Die Stornierung hat nicht geklappt. Ist die kostenlose Stornierung schon vorbei?');
    }
  };

  const open = todos.filter((todo) => todo.doneAt === null);
  const closed = todos.filter((todo) => todo.doneAt !== null);
  if (todos.length === 0 || (compact && open.length === 0)) {
    return null;
  }
  if (compact) {
    return (
      <button type="button" className="va-todo-line" onClick={onExpand}>
        <span className="va-chip va-chip-ink">{open.length}</span>
        <span className="va-strong">{open.length === 1 ? 'Buchung zu erledigen' : 'Buchungen zu erledigen'}</span>
        <span className="va-muted">Ansehen ↑</span>
      </button>
    );
  }

  return (
    <section className="va-todos" aria-labelledby="va-todos-title">
      <h3 id="va-todos-title" className="va-eyebrow">
        Zu erledigen{open.length > 0 ? ` (${open.length})` : ''}
      </h3>
      {error && <Alert type="error" showIcon closable message={error} onClose={() => setError(null)} />}
      {open.length === 0 && <p className="va-small va-muted">Alles erledigt.</p>}
      <ul className="va-plain-list va-column va-gap">
        {open.map((todo) => (
          <li key={todo.id} className="va-todo">
            <TodoText todo={todo} />
            <div className="va-row va-gap va-wrap">
              {todo.bookedInApp && todo.bookingId != null && todo.kind === 'CANCEL' ? (
                <Popconfirm title={`${todo.title} stornieren?`} description="Das Geld kommt per PayPal zurück, soweit die Stornierung kostenlos ist."
                  okText="Stornieren" okButtonProps={{ danger: true }} cancelText="Abbrechen" onConfirm={() => cancelInApp(todo)}>
                  <button type="button" className="va-button va-button-outline">{todoActionText(todo)}</button>
                </Popconfirm>
              ) : todo.manageUrl ? (
                <a className="va-button va-button-outline" href={todo.manageUrl} target="_blank" rel="noopener noreferrer">
                  {todoActionText(todo)}
                </a>
              ) : null}
              {todo.phone && (
                <a className="va-icon-button" href={`tel:${todo.phone}`} aria-label={`${todo.title} anrufen`}><PhoneOutlined /></a>
              )}
              {todo.email && (
                <a className="va-icon-button" href={`mailto:${todo.email}`} aria-label={`${todo.title} schreiben`}><MailOutlined /></a>
              )}
              <button type="button" className="va-button va-grow" onClick={() => void done(todo)}>
                <CheckOutlined aria-hidden="true" /> Erledigt
              </button>
            </div>
          </li>
        ))}
      </ul>
      {closed.length > 0 && (
        <button type="button" className="va-text-button" onClick={() => setShowDone((value) => !value)}>
          {showDone ? 'Erledigte ausblenden' : `${closed.length} erledigt`}
        </button>
      )}
      {showDone && (
        <ul className="va-plain-list va-column va-gap">
          {closed.map((todo) => (
            <li key={todo.id} className="va-todo va-todo-done">
              <TodoText todo={todo} />
              <button type="button" className="va-text-button" onClick={() => void reopen(todo)}>
                <UndoOutlined aria-hidden="true" /> Wieder öffnen
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function TodoText({ todo }: { todo: BookingTodo }) {
  const what = todo.kind === 'NOTIFY_LATE_ARRIVAL'
    ? `Ankunft erst am ${todo.newArrivalOn ? formatShortDate(todo.newArrivalOn) : 'nächsten Tag'}: Bescheid geben oder die erste Nacht stornieren`
    : 'Nicht mehr nötig, bitte stornieren';
  return (
    <span className="va-column">
      <span className="va-strong">{todo.title}</span>
      <span className="va-small">{what}</span>
      <span className="va-small va-muted">
        {[
          todoReasonText(todo),
          todo.startsAt && todo.endsAt && formatStay(todo.startsAt, todo.endsAt),
          todo.reference && `Nr. ${todo.reference}`,
          todo.cancellationDeadline && `kostenlos stornierbar bis ${formatLocalDateTime(todo.cancellationDeadline)}`,
        ].filter(Boolean).join(' · ')}
      </span>
    </span>
  );
}
