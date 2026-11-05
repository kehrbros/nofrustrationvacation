/** Bookings at stops, mapped from the generated API types (where every field is optional). */

export type BookingType = 'ACCOMMODATION' | 'TRANSPORT' | 'ACTIVITY' | 'OTHER';
export type BookingStatus = 'CONFIRMED' | 'CANCELLED';
export type BookingSource = 'MANUAL' | 'SEARCH' | 'DOCUMENT' | 'SHARE' | 'EMAIL';

export interface Accommodation {
  name: string;
  addressLine: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  /** IANA zone of the place, check-in and check-out are local there. */
  timeZone: string;
  phone: string | null;
  email: string | null;
  website: string | null;
  placeRef: string | null;
  /** House rules as HH:MM:SS, local at the place. */
  checkInFrom: string | null;
  checkInUntil: string | null;
  checkOutUntil: string | null;
  guests: number | null;
  rooms: number | null;
}

export interface Booking {
  id: number;
  stopId: number;
  type: BookingType;
  status: BookingStatus;
  source: BookingSource;
  title: string;
  provider: string | null;
  reference: string | null;
  manageUrl: string | null;
  /** ISO 8601 with the offset of the place: check-in for accommodations. Show as local time of the place. */
  startsAt: string | null;
  /** Check-out for accommodations. */
  endsAt: string | null;
  /** Decimal as string, e.g. "412.50". */
  price: string | null;
  currency: string | null;
  cancellationDeadline: string | null;
  notes: string | null;
  accommodation: Accommodation | null;
}

/** Accommodation read from a confirmation, not stored yet. Dates yyyy-mm-dd, times HH:MM:SS, local at the place. */
export interface AccommodationDraft {
  found: boolean;
  name: string | null;
  addressLine: string | null;
  countryCode: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  provider: string | null;
  reference: string | null;
  manageUrl: string | null;
  checkInDate: string | null;
  checkInTime: string | null;
  checkOutDate: string | null;
  checkOutTime: string | null;
  checkInFrom: string | null;
  checkInUntil: string | null;
  checkOutUntil: string | null;
  guests: number | null;
  rooms: number | null;
  price: string | null;
  currency: string | null;
  cancellationDeadline: string | null;
  uncertainFields: string[];
}

/** Body of create and change: local times without offset, the backend determines the zone of the place. */
export interface AccommodationBookingInput {
  source: BookingSource;
  name: string;
  addressLine: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  placeRef: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  provider: string | null;
  reference: string | null;
  manageUrl: string | null;
  /** yyyy-mm-ddTHH:MM:00 */
  checkIn: string;
  checkOut: string;
  /** Zone of the user, used only without coordinates. */
  fallbackTimeZone: string;
  checkInFrom: string | null;
  checkInUntil: string | null;
  checkOutUntil: string | null;
  guests: number | null;
  rooms: number | null;
  price: string | null;
  currency: string | null;
  cancellationDeadline: string | null;
  notes: string | null;
}

/** Accommodation found by name in the LiteAPI hotel data. */
export interface AccommodationSuggestion {
  hotelId: string;
  name: string;
  addressLine: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  stars: number | null;
}

/** Contact and house rules of an accommodation; times as HH:MM:SS, local at the place. */
export interface AccommodationFacts {
  addressLine: string | null;
  countryCode: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  checkInFrom: string | null;
  checkInUntil: string | null;
  checkOutUntil: string | null;
  /** Field names by origin: LiteAPI hotel data or found on the web by the assistant. */
  fromLiteApi: string[];
  fromAssistant: string[];
  researched: boolean;
  sources: string[];
}
