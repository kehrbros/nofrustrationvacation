import { CheckOutlined, MailOutlined, PhoneOutlined, UndoOutlined } from '@ant-design/icons';
import { Alert, Popconfirm } from 'antd';
import { useEffect, useRef, useState } from 'react';

import { formatMoney, t } from '../i18n';
import { formatLocalDateTime, formatStay } from './localTime';
import { cancelTodoInApp, fetchTodos, markTodoDone, reopenTodoAgain, todoActionText, todoReasonText, type BookingTodo } from './todosApi';
import { formatShortDate } from '../proposals/proposalText';
import UndoSnackbar from '../components/UndoSnackbar';

/**
 * To-do list of the trip: bookings that are not needed any more after the trip changed (cancel them at the
 * provider) and accommodations that expect the user earlier. Nothing is cancelled automatically. Done ones stay
 * below, struck through, until the trip is gone. "Done" can be undone for a few seconds: the to-do moves to the done
 * ones at once, but is only sent when the hint at the bottom ran out (for a cancellation it also marks the booking
 * cancelled, which reopening does not take back).
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
  const [refund, setRefund] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<number | null>(null);

  /** Ticked as done, not sent yet: the to-do as it was before, for undo. */
  const [pending, setPending] = useState<BookingTodo | null>(null);
  const pendingRef = useRef<BookingTodo | null>(null);
  pendingRef.current = pending;

  useEffect(() => {
    let active = true;
    fetchTodos(tripId).then((loaded) => active && setTodos(withPending(loaded, pendingRef.current)), () => undefined);
    return () => {
      active = false;
    };
  }, [tripId, version]);

  // leaving the page sends a tick that is still waiting, the user did not undo it
  useEffect(() => () => {
    if (pendingRef.current) {
      void markTodoDone(pendingRef.current.id).catch(() => undefined);
    }
  }, []);

  const replace = (todo: BookingTodo) => setTodos((current) => current.map((candidate) => (candidate.id === todo.id ? todo : candidate)));
  const send = async (todo: BookingTodo) => {
    try {
      replace(await markTodoDone(todo.id));
      onChanged();
    } catch {
      replace(todo);
      setError(t('common.failedTryAgain'));
    }
  };
  const done = (todo: BookingTodo) => {
    setError(null);
    if (pending) {
      void send(pending);
    }
    replace(markedDone(todo));
    setPending(todo);
  };
  const undo = () => {
    if (pending) {
      replace(pending);
      setPending(null);
    }
  };
  const expire = () => {
    if (pending) {
      setPending(null);
      void send(pending);
    }
  };
  const reopen = async (todo: BookingTodo) => {
    if (pending?.id === todo.id) {
      undo();
      return;
    }
    try {
      replace(await reopenTodoAgain(todo.id));
    } catch {
      setError(t('common.failedTryAgain'));
    }
  };
  const cancelInApp = async (todo: BookingTodo) => {
    setError(null);
    setCancelling(todo.id);
    try {
      const result = await cancelTodoInApp(todo.id);
      setRefund(result.refunded && result.refundAmount
        ? t('todo.refunded', { amount: formatMoney(result.refundAmount, result.currency) })
        : t('todo.refundPending'));
      setTodos(await fetchTodos(tripId));
      onChanged();
    } catch {
      setError(t('todo.cancelFailed'));
    } finally {
      setCancelling(null);
    }
  };

  const open = todos.filter((todo) => todo.doneAt === null);
  const closed = todos.filter((todo) => todo.doneAt !== null);
  const snackbar = pending && (
    <UndoSnackbar
      key={pending.id}
      message={t(pending.kind === 'CANCEL' ? 'todo.markedCancelled' : 'todo.markedDone', { title: pending.title })}
      onUndo={undo}
      onExpire={expire}
    />
  );
  if (todos.length === 0 || (compact && open.length === 0)) {
    return snackbar || null;
  }
  if (compact) {
    return (
      <>
        <button type="button" className="va-todo-line" onClick={onExpand}>
          <span className="va-chip va-chip-ink">{open.length}</span>
          <span className="va-strong">{t('todo.compact', { count: open.length })}</span>
          <span className="va-muted">{t('todo.view')}</span>
        </button>
        {snackbar}
      </>
    );
  }

  return (
    <section className="va-todos" aria-labelledby="va-todos-title">
      <h3 id="va-todos-title" className="va-eyebrow">
        {open.length > 0 ? t('todo.titleCount', { count: open.length }) : t('todo.title')}
      </h3>
      {error && <Alert type="error" showIcon closable message={error} onClose={() => setError(null)} />}
      {refund && <Alert type="success" showIcon closable message={refund} onClose={() => setRefund(null)} />}
      {open.length === 0 && <p className="va-small va-muted">{t('todo.allDone')}</p>}
      <ul className="va-plain-list va-column va-gap">
        {open.map((todo) => (
          <li key={todo.id} className="va-todo">
            <TodoText todo={todo} />
            <div className="va-row va-gap va-wrap">
              {todo.cancellableInApp ? (
                <Popconfirm title={t('todo.cancelTitle', { title: todo.title })} description={t('todo.cancelText')}
                  okText={t('booking.cancelOk')} okButtonProps={{ danger: true }} cancelText={t('common.cancel')} onConfirm={() => cancelInApp(todo)}>
                  <button type="button" className="va-button va-button-outline" disabled={cancelling === todo.id}>
                    {cancelling === todo.id ? t('removeBooking.working') : todoActionText(todo)}
                  </button>
                </Popconfirm>
              ) : todo.manageUrl ? (
                <a className="va-button va-button-outline" href={todo.manageUrl} target="_blank" rel="noopener noreferrer">
                  {todoActionText(todo)}
                </a>
              ) : null}
              {todo.phone && (
                <a className="va-icon-button" href={`tel:${todo.phone}`} aria-label={t('todo.call', { title: todo.title })}><PhoneOutlined /></a>
              )}
              {todo.email && (
                <a className="va-icon-button" href={`mailto:${todo.email}`} aria-label={t('todo.write', { title: todo.title })}><MailOutlined /></a>
              )}
              <button type="button" className="va-button va-grow" onClick={() => done(todo)}>
                <CheckOutlined aria-hidden="true" /> {t('todo.done')}
              </button>
            </div>
          </li>
        ))}
      </ul>
      {closed.length > 0 && (
        <button type="button" className="va-text-button" onClick={() => setShowDone((value) => !value)}>
          {showDone ? t('todo.hideDone') : t('todo.doneCount', { count: closed.length })}
        </button>
      )}
      {showDone && (
        <ul className="va-plain-list va-column va-gap">
          {closed.map((todo) => (
            <li key={todo.id} className="va-todo va-todo-done">
              <TodoText todo={todo} />
              <button type="button" className="va-text-button" onClick={() => void reopen(todo)}>
                <UndoOutlined aria-hidden="true" /> {t('todo.reopen')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {snackbar}
    </section>
  );
}

/** Shown as done right away, until it is sent or undone. */
function markedDone(todo: BookingTodo): BookingTodo {
  return { ...todo, doneAt: new Date().toISOString() };
}

/** A to-do loaded again while its tick still waits stays shown as done. */
function withPending(todos: BookingTodo[], pending: BookingTodo | null): BookingTodo[] {
  return pending ? todos.map((todo) => (todo.id === pending.id && todo.doneAt === null ? markedDone(todo) : todo)) : todos;
}

function TodoText({ todo }: { todo: BookingTodo }) {
  const what = todo.kind === 'NOTIFY_LATE_ARRIVAL'
    ? todo.newArrivalOn
      ? t('todo.lateOn', { date: formatShortDate(todo.newArrivalOn) })
      : t('todo.lateNextDay')
    : t('todo.notNeeded');
  return (
    <span className="va-column">
      <span className="va-strong">{todo.title}</span>
      <span className="va-small">{what}</span>
      <span className="va-small va-muted">
        {[
          todoReasonText(todo),
          todo.startsAt && todo.endsAt && formatStay(todo.startsAt, todo.endsAt),
          todo.reference && t('todo.reference', { reference: todo.reference }),
          todo.cancellationDeadline && t('todo.freeUntil', { time: formatLocalDateTime(todo.cancellationDeadline) }),
        ].filter(Boolean).join(' · ')}
      </span>
    </span>
  );
}
