import { t } from '../i18n';

/** Green board-game pawn standing on the user's position: head, collar, bell-shaped body, base. */
const PAWN =
  '<svg width="30" height="42" viewBox="0 0 32 44" aria-hidden="true">' +
  '<g fill="#2fa84f" stroke="#111111" stroke-width="2" stroke-linejoin="round">' +
  '<path d="M12.5 19 C12 27 8 33 5.5 38 H26.5 C24 33 20 27 19.5 19 Z"/>' +
  '<rect x="3" y="37" width="26" height="6" rx="3"/>' +
  '<ellipse cx="16" cy="19" rx="8" ry="2.6"/>' +
  '<circle cx="16" cy="9" r="7.5"/>' +
  '</g>' +
  '<circle cx="13.2" cy="6.4" r="2.2" fill="#ffffff" opacity="0.75"/>' +
  '</svg>';

/** Element of the pawn marker; MapLibre anchors it at the bottom, so the base stands on the position. */
export function pawnElement(): HTMLElement {
  const element = document.createElement('div');
  element.className = 'va-map-pawn';
  element.innerHTML = PAWN;
  element.setAttribute('role', 'img');
  element.setAttribute('aria-label', t('map.position.pawn'));
  return element;
}
