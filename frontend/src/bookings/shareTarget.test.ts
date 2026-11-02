import { describe, expect, it } from 'vitest';

import { combineInput } from './shareTarget';

const pdf = new File(['%PDF'], 'bestaetigung.pdf', { type: 'application/pdf' });
const screenshot = new File(['png'], 'screenshot.png', { type: 'image/png' });

describe('combineInput', () => {
  it('joins a shared link and pasted text', () => {
    const input = combineInput({ text: 'https://booking.com/x', file: null }, '  Hotel Adler, 12.–15. Juli ', null);
    expect(input).toEqual({ text: 'https://booking.com/x\n\nHotel Adler, 12.–15. Juli', file: null });
  });

  it('keeps the shared file next to pasted text', () => {
    expect(combineInput({ text: '', file: pdf }, 'Buchungsnummer 4711', null)).toEqual({ text: 'Buchungsnummer 4711', file: pdf });
  });

  it('prefers the chosen file over the shared one', () => {
    expect(combineInput({ text: 'Hotel Adler', file: pdf }, '', screenshot)).toEqual({ text: 'Hotel Adler', file: screenshot });
  });

  it('works without shared content', () => {
    expect(combineInput(null, ' Text ', null)).toEqual({ text: 'Text', file: null });
  });
});
