import { describe, expect, it } from 'vitest';

import { t } from '../i18n';
import { secondsLeft, UNDO_SECONDS } from './UndoSnackbar';

describe('UndoSnackbar', () => {
  it('offers undo for five seconds', () => {
    expect(UNDO_SECONDS).toBe(5);
  });

  it('counts down in whole seconds', () => {
    expect(secondsLeft(5000)).toBe(5);
    expect(secondsLeft(4001)).toBe(5);
    expect(secondsLeft(4000)).toBe(4);
    expect(secondsLeft(1)).toBe(1);
    expect(secondsLeft(0)).toBe(0);
    expect(secondsLeft(-200)).toBe(0);
  });

  it('names the countdown and the action', () => {
    expect(t('undo.action')).toBe('Rückgängig');
    expect(t('undo.secondsLeft', { count: 1 })).toBe('Noch 1 Sekunde');
    expect(t('undo.secondsLeft', { count: 3 })).toBe('Noch 3 Sekunden');
    expect(t('todo.markedCancelled', { title: 'Hotel Adler' })).toBe('Hotel Adler als storniert markiert');
  });
});
