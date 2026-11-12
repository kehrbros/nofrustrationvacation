import type { FieldErrors } from '../components/form/validation';
import { hasErrors } from '../components/form/validation';
import { t } from '../i18n';
import type { Criteria } from './alternativesApi';
import { dateProblem } from './stayDates';

/** Fields of the search for an alternative accommodation. */
export type CriteriaField = 'location' | 'radius' | 'checkIn' | 'checkOut' | 'guests' | 'rooms' | 'minStars' | 'maxPrice' | 'currency';
export const CRITERIA_FIELDS: CriteriaField[] = ['location', 'radius', 'checkIn', 'checkOut', 'guests', 'rooms', 'minStars', 'maxPrice', 'currency'];

/** API names of the search (AlternativeSearch) → field of the form; others are named alike. */
export const CRITERIA_API_FIELDS: Partial<Record<string, CriteriaField>> = {
  latitude: 'location',
  longitude: 'location',
  radiusMeters: 'radius',
};

/** Values of the fields, to tell whether the user changed a field the backend refused. */
export function criteriaValues(criteria: Criteria | null): Partial<Record<CriteriaField, unknown>> {
  if (!criteria) {
    return {};
  }
  return {
    location: criteria.latitude,
    radius: criteria.radiusMeters,
    checkIn: criteria.checkIn,
    checkOut: criteria.checkOut,
    guests: criteria.guests,
    rooms: criteria.rooms,
    minStars: criteria.minStars,
    maxPrice: criteria.maxPrice,
    currency: criteria.currency,
  };
}

/**
 * What is wrong with the search, per field (same rules as AlternativeSearch). Dates before the earliest bookable day
 * are not in here: dateProblem shows them with a way out.
 */
export function criteriaErrors(criteria: Criteria): FieldErrors<CriteriaField> {
  const errors: FieldErrors<CriteriaField> = {};
  if (criteria.latitude == null || criteria.longitude == null) {
    errors.location = t('alternatives.regionMissing');
  }
  if (!criteria.checkIn) {
    errors.checkIn = t('form.checkInMissing');
  }
  if (!criteria.checkOut) {
    errors.checkOut = t('form.checkOutMissing');
  } else if (criteria.checkIn && criteria.checkOut <= criteria.checkIn) {
    errors.checkOut = t('form.checkOutBeforeCheckIn');
  }
  if (!Number.isInteger(criteria.rooms) || criteria.rooms < 1 || criteria.rooms > 9) {
    errors.rooms = t('alternatives.roomsRange');
  }
  if (criteria.guests == null) {
    errors.guests = t('alternatives.guestsMissing');
  } else if (!Number.isInteger(criteria.guests) || criteria.guests < 1 || criteria.guests > 36 || criteria.guests < criteria.rooms) {
    errors.guests = t('alternatives.guestsRange');
  }
  const maxPrice = criteria.maxPrice.trim().replace(',', '.');
  if (maxPrice && !(Number(maxPrice) > 0)) {
    errors.maxPrice = t('alternatives.maxPriceInvalid');
  }
  if (!/^[A-Z]{3}$/.test(criteria.currency)) {
    errors.currency = t('form.currencyInvalid');
  }
  return errors;
}

/** True if the search can be sent as it is. */
export function canSearch(criteria: Criteria): boolean {
  return !hasErrors(criteriaErrors(criteria)) && dateProblem(criteria) === null;
}
