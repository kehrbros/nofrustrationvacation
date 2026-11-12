import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { t } from '../i18n';

/** Seconds an undo stays offered, see Docs/architecture.md#frontend (Material: 4–10 s for a snackbar with an action). */
export const UNDO_SECONDS = 5;

/** Whole seconds left that the countdown shows: 5 right after the tap, 1 in the last second. */
export function secondsLeft(remainingMs: number): number {
  return Math.max(0, Math.ceil(remainingMs / 1000));
}

/**
 * Hint at the bottom of the screen with "Undo" and a small countdown. When it runs out it calls onExpire, the
 * caller only then sends the change. The countdown pauses while the pointer or the focus is on the hint, so it
 * can be read and reached in time. Give it a new key for every change so the countdown starts again.
 */
export default function UndoSnackbar({ message, onUndo, onExpire, seconds = UNDO_SECONDS }: {
  message: string;
  onUndo: () => void;
  onExpire: () => void;
  seconds?: number;
}) {
  const [remaining, setRemaining] = useState(seconds * 1000);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const paused = hovered || focused;
  const expire = useRef(onExpire);
  expire.current = onExpire;

  useEffect(() => {
    if (paused) {
      return undefined;
    }
    const started = Date.now();
    const from = remaining;
    const tick = setInterval(() => setRemaining(Math.max(0, from - (Date.now() - started))), 200);
    const end = setTimeout(() => expire.current(), from);
    return () => {
      clearInterval(tick);
      clearTimeout(end);
    };
    // restart only when pausing or resuming; remaining is read as the starting point
  }, [paused]);

  const left = secondsLeft(remaining);
  return createPortal(
    <div
      className="va-snackbar"
      role="status"
      onPointerEnter={() => setHovered(true)}
      onPointerLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
    >
      <span className="va-snackbar-text">{message}</span>
      <span className="va-snackbar-countdown" aria-label={t('undo.secondsLeft', { count: left })} title={t('undo.secondsLeft', { count: left })}>
        {left}
      </span>
      <button type="button" className="va-snackbar-action" onClick={onUndo}>
        {t('undo.action')}
      </button>
      <span
        className={`va-snackbar-progress ${paused ? 'va-snackbar-progress-paused' : ''}`}
        style={{ animationDuration: `${seconds}s` }}
        aria-hidden="true"
      />
    </div>,
    document.body,
  );
}
