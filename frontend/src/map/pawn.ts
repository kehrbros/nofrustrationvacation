import { t } from '../i18n';

/** Green board-game pawn standing on the user's position: ball head on a bell-shaped body, no rings. */
const PAWN =
  '<svg width="30" height="42" viewBox="0 0 32 44" aria-hidden="true">' +
  '<g fill="#2fa84f" stroke="#111111" stroke-width="2" stroke-linejoin="round">' +
  '<path d="M12.5 15 C12 25 7.5 33 4.5 38.5 Q3.5 42 7 42 H25 Q28.5 42 27.5 38.5 C24.5 33 20 25 19.5 15 Z"/>' +
  '<circle cx="16" cy="9.5" r="7.5"/>' +
  '</g>' +
  '<circle cx="13.2" cy="6.9" r="2.2" fill="#ffffff" opacity="0.75"/>' +
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
