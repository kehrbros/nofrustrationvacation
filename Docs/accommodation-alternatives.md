# Concept: searching for alternative accommodations (LiteAPI + assistant)

- **Status:** implemented (2026-11-03), payment only via PayPal, LiteAPI's payment SDK is not used. Deviations from the original concept are listed under [Implementation](#implementation-as-of-2026-11-03).
- **Goal:** for a stored accommodation booking, find an alternative that meets all given criteria and book it in the app.
- **Criteria (first stage):** maximum price, region (address or coordinates + radius), availability for the number of guests, number of rooms, date from/to.
- **First use case "booking not usable":** booked wrongly, hotel forgot the booking, overbooked or similar. The criteria are filled from the current accommodation, the user can change them before the search.
- **Later:** the same search with other parameters, e.g. equivalent accommodation at another place (route closed, stop moved). That is why criteria are a separate object, the use case only prefills it.
- Belongs to the module `bookings` in domain terms (legacy code stays untouched).

## Assumption: "Lite-API" = LiteAPI by Nuitee

- Meant is **LiteAPI** (https://liteapi.travel, docs https://docs.liteapi.travel), a hotel API by Nuitee for search, availability, prices, prebook, booking and cancellation. Please confirm if another service is meant.
- Checked on 2026-11-02 against the reference (REST `v3.0`):
  - Search and master data under `https://api.liteapi.travel/v3.0`, booking under `https://book.liteapi.travel/v3.0`. Auth via header `X-API-Key`, sandbox keys start with `sand_`.
  - `POST /hotels/rates`: `latitude`, `longitude`, `radius` (metres); `checkin`, `checkout` (`YYYY-MM-DD`); `occupancies` (per room `{adults, children: [age]}`); `currency`, `guestNationality`; filters `starRating`, `boardType`, `refundableRatesOnly`, `maxRatesPerHotel`, `limit`, `sort`, `includeHotelData`, `margin`, `timeout`.
  - Response per hotel `roomTypes[]` with `offerId`, inside `rates[]` with `boardType`, `retailRate.total` (total price), `suggestedSellingPrice`, `cancellationPolicies` (`refundableTag` `RFN`/`NRFN`, deadlines with time zone).
  - `GET /data/hotel?hotelId=…`: name, address, coordinates, stars, facilities, check-in times, images.
  - `POST /rates/prebook` (`offerId`, `usePaymentSdk`): secures price and availability, reports price, cancellation and board changes, returns `prebookId`, with the payment SDK additionally `transactionId` + `secretKey`.
  - `POST /rates/book` (`prebookId`, `holder`, `guests`, `payment.method`, `clientReference`): returns `bookingId`, `status`. Duplicate `clientReference` → error `4005`.
  - `GET /bookings/{bookingId}`, `PUT /bookings/{bookingId}` (cancellation, returns fee and refund).
  - Payment SDK (script from `payment-wrapper.liteapi.travel`): card payment (according to the docs also Google Pay, no PayPal) in the browser, then booking with `TRANSACTION_ID`. Nuitee is then merchant of record, we get a commission (`margin` in percent, `0` = net price), paid out weekly after check-out.
  - Own payment (we are merchant of record): rates with `margin: 0` (net price), own commission via an own payment method, booking with `ACC_CREDIT_CARD` (charges the card stored in the LiteAPI account; in the sandbox a hidden test card per key). `CREDIT` (credit line) only after a contract and not in the sandbox.
  - Prices below the suggested selling price (`suggestedSellingPrice`) only for logged-in users or in the app, not publicly.
  - Error `2001` on prebook or book: offer no longer available, search again.
  - There is a LiteAPI MCP server, deliberately not used (see roles).

## Roles: assistant and LiteAPI

- **Principle:** whether an offer meets the criteria is decided by the backend based on LiteAPI's data, not by the language model. The model only compares offers that already meet all criteria with the previous accommodation. It does not invent prices or availability and does not trigger a booking.
- **Since 2026-11-10 the model may exclude:** candidates that do not match the previous accommodation (different kind or clearly different standard, e.g. backpackers instead of hotel) are dropped, it orders the rest. Before, it was only allowed to order. Reason: in Nelson the result consisted almost entirely of cheap backpackers.
  - **In both directions** (2026-11-10): clearly more upmarket is not comparable either, e.g. luxury or 5-star hotel instead of backpackers, motel or simple hotel, 2 or more stars above the previous one, price per night above about 2.5 times. Somewhat better (one star more, single instead of dorm room) remains comparable. Reason: an expensive Sofitel came up for a cheap backpackers. Before, the prompt said "clearly better is comparable". The max. price only limits this if the booking has a price. The result reports the number of excluded ones (`excludedByAssistant`), the frontend shows it as a note.

| Task | who |
|---|---|
| Prefill criteria from the current booking | backend, deterministic (`AlternativeCriteria.from(booking)`) |
| Availability for guests and rooms in the period, prices, cancellation | LiteAPI (`/hotels/rates`) |
| Hard check of every offer against all criteria (price ≤ max., distance ≤ radius, period, occupancy, minimum stars) | backend, after LiteAPI's response |
| "Equivalent to the previous accommodation": exclude non-comparable offers, order of the rest, best alternative, short justification | assistant (`StructuredExtraction`, schema in `bookings.logic`) |
| Secure the price, book, cancel if necessary | LiteAPI, only after the user's confirmation |
| Payment | PayPal (module `paypal`) or later payment SDK, see [Payment](#payment); never the model |

- **What the model gets:** the previous accommodation (name, address, total price, nights, price per night; if `placeRef` is a LiteAPI hotel id, additionally stars, kind, description, facilities) and per candidate only facts (hotel id, name, stars, rating, distance, total price, price per night, rooms, board, cancellable, kind, description, facilities).
  - The nearest `nfv.bookings.alternatives.ranking-pool` (25) candidates by distance, then price. Before, the 20 cheapest, so better hotels never reached the model.
  - Kind, description and facilities from `GET /data/hotel` (`hotelType`, `hotelDescription` as text without HTML, shortened to 300 characters, `hotelFacilities` at most 15), one call per hotel, in parallel (at most 6 at a time), cached in memory for one day (`HotelProfiles`). If a profile is missing, the model compares with the facts of the offer.
  - Descriptions come from the hotels: the model should treat them as data and ignore instructions in them.
- **What it returns:** per hotel id `comparable` (yes/no), the comparable ones in ranking order, a justification only for the first 10 comparable ones (otherwise empty). Non-comparable ones are dropped, ids that are not among the candidates are discarded by the backend, forgotten ones are appended at the end. At most `max-candidates` (20) are shown.
- **Response time (2026-11-10):** the user waits for the result, therefore with `ReasoningEffort.LOW` (`reasoning.effort` of the Responses API, otherwise the model's default applies, `medium` for `gpt-5-mini`), small pool, short descriptions and justifications only for the first 10, because the length of the answer determines the time. A model without reasoning rejects the parameter, then the order by price applies. `AlternativeSearch` logs the duration of rates, profiles and ranking per search (`Alternatives search: …`). Possible next step, not decided yet: show hits right after the hard check and deliver the ranking afterwards.
- **Without the assistant** (no OpenAI key, rate limit, error): the search still works, nothing is excluded, order by price, then distance, without justification. In an emergency the search should not fail because of the model.
- **Without a LiteAPI key:** the feature is not offered (status endpoint like `GET /bookings/assistant`).
- **Why not `aiSearch` or LiteAPI's MCP server:** criteria must apply strictly and verifiably, and booking is expensive and irreversible. Flow, confirmation and storage stay in the backend.

## Criteria

- `AlternativeCriteria` (record in `bookings.logic`), independent of the use case:
  - `latitude`, `longitude`, `radiusMeters` (region). Address only for display.
  - `checkIn`, `checkOut` (`LocalDate`, local time of the accommodation).
  - `guests` (adults), `rooms`. Distribution to `occupancies`: guests evenly across rooms (5 guests, 2 rooms → 3 + 2).
  - `maxPrice` + `currency` (total price of the stay).
  - `minStars` (1–5, optional, since 2026-11-10): to LiteAPI as filter `starRating` [minStars..5], additionally in the hard check; hotels without stars do not meet it. The agent (`search_accommodations`) does not set it.
  - Later optional: children with age, board, refundable only, exclusion of hotels (e.g. the previous one).
- **Prefill in the use case "booking not usable":**

  | Criterion | from the booking | if the value is missing |
  |---|---|---|
  | Region | coordinates of `AccommodationDetails` (otherwise of the stop), radius default 5 km (`nfv.bookings.alternatives.default-radius`) | user must choose a place (Photon as for the stop) |
  | Date from/to | date of `startsAt`/`endsAt` in the zone of the accommodation, also if the check-in is already in the past (since 2026-11-11); `earliestCheckIn` = today at the place. The form does not offer days before it and, if the booked check-in is earlier, says which day is the earliest and offers to search from it | required input |
  | Guests | `guests` | required input, suggested 2 |
  | Rooms | `rooms` | 1 |
  | Max. price | `price` + `currency` of the booking, default without markup (user increases if needed); the form reduces it proportionally per night when the user moves check-in to the earliest day; the agent (`search_accommodations`) gets the stay from the earliest day on with the price reduced the same way (`CriteriaProposal.fromEarliest`) | no upper limit, user can set one |
  | Minimum stars | stars of the previous accommodation (rounded down) from its LiteAPI profile, only if `placeRef` is a LiteAPI hotel id; an error at LiteAPI leaves the field empty | "Any", user can choose 2–5 |

- The previous accommodation is excluded if its `placeRef` is a LiteAPI hotel id; otherwise hits with very small distance (< 50 m) and a similar name are marked as "previous accommodation?".
- Later use cases prefill differently, e.g. "other place": region from another stop or place, other values from the booking.

## Flow

1. **Entry:** in the details of an accommodation booking "Search alternative" (optional reason: booked wrongly, hotel has no booking, other).
2. **Check criteria:** form with the prefilled values (origin "from the booking" marked, missing required values highlighted). The user can change everything.
3. **Search:** the backend calls `/hotels/rates` with coordinates, radius, period, `occupancies`, `currency` = currency of the max. price, `starRating` with minimum stars, `includeHotelData`, `maxRatesPerHotel` small, `limit` limited.
4. **Hard check:** per hotel the cheapest offer that meets all criteria (total price ≤ max., distance via haversine ≤ radius, occupancy fits, stars ≥ minimum stars). Hotels without a matching offer are dropped.
5. **Comparison by the assistant** (if available) over the nearest 25 with their profiles: non-comparable ones are dropped, result: recommended alternative plus ordered further hits with justification (✦ as with extraction) and the note "N accommodations hidden".
6. **Result:** list and map with the previous accommodation as reference (distance, price difference). No hits → note which criterion excludes the most (e.g. "from 7 km there would be 4 hits", via a new search with a larger radius, only on request).
7. **Choose offer → prebook:** `/rates/prebook` (with PayPal `usePaymentSdk: false`). The new price is checked again against the max. price; a price, cancellation or board change is shown before payment.
8. **Confirm and pay:** summary with final price (net price + commission), guest data (name, email prefilled from the profile), payment via PayPal, see [Payment](#payment).
9. **Book:** `/rates/book` with `ACC_CREDIT_CARD` (or `TRANSACTION_ID` with the payment SDK) and `clientReference` (id of the reservation, protects against double booking). Then `BookingLogic` creates a new `Booking` at the same stop: `source` `SEARCH`, booking number, price, cancellation deadline, times via the existing time zone logic, `AccommodationDetails` from the hotel master data, `placeRef` `liteapi:hotel/<hotelId>`.
10. **Previous booking:** is not changed automatically. The user is asked whether it should be marked as cancelled (`Booking.cancel()`, already exists); cancelling at the foreign provider they must do themselves (link `manageUrl`). Since 2026-11-07 it also goes on the trip's to-do list (`REPLACED`, [domain.md](domain.md#bookings)), "Done" marks it as cancelled.

## Payment

- **Decision (2026-11-02):** the first payment method is PayPal via the existing module `paypal`, which is adapted and reused for this (no longer legacy code). We are merchant of record and keep a commission.
- **Price:** rates with `margin: 0`, final price = net price + commission (`nfv.bookings.alternatives.fee-percent`, optional minimum amount). The max. price of the criteria applies to the final price. Choose the commission such that the final price is usually not above the `suggestedSellingPrice`.
- **Flow with PayPal (Orders API v2, `intent=AUTHORIZE`):**
  1. Prebook successful → `PayPalCheckout` creates an order over the final price (`reference` = id of the reservation, idempotent), the user approves at PayPal (`approveUrl`, return to the sub-route of the alternatives).
  2. The backend authorises the payment (money reserved, not yet collected) and checks amount and currency.
  3. Booking at LiteAPI with `ACC_CREDIT_CARD`.
  4. Successful → capture of the authorisation, reservation `BOOKED`, create `Booking`. Failed (`2001`, price change) → release the authorisation (void), no money moved, no refund fee.
  - If the flow aborts between 3 and 4, a poller (as with the top-up in `accounting`) reconciles reservations with authorisation via `clientReference` at LiteAPI and captures or releases. Authorisations expire after a few days, which is enough for the reconciliation.
- **Several hotels of a plan (2026-11-11):** one order with one `purchase_unit` per hotel (`reference_id` = reservation), one approval, authorisation per unit, LiteAPI booking per hotel, capture only after the plan has changed the trip. If one hotel fails, the others are cancelled and all authorisations released (`bookings.PlanPayments`), see [incident-plans.md](incident-plans.md#one-click-execution).
- **Cancellation of an alternative booked via the app:** LiteAPI refunds to our card according to the conditions, we refund via PayPal (refund of the capture). Whether to keep or refund the commission on cancellation: open.
- **Adaptations to the module `paypal`** (stays a technical integration without domain knowledge, `PayPalModuleTest` still applies):
  - Return and cancel URL per call instead of fixed configuration (`checkout-return-url` points to a removed page of `accounting`).
  - `intent=AUTHORIZE` with authorize, capture and void of the authorisation (`/v2/checkout/orders/{id}/authorize`, `/v2/payments/authorizations/{id}/capture|void`).
  - Refund of a capture (`/v2/payments/captures/{id}/refund`), idempotent via `PayPal-Request-Id`.
  - `PayPalConnect` and `PayPalPayouts` stay unchanged (see [Further PayPal potential](#further-paypal-potential)).
  - New dependency: `bookings --> paypal`. `accounting` and `onboarding` stay legacy code.
- **Later additionally the payment SDK ("Pay with card / Google Pay"):** technically possible to offer both methods side by side. With the payment SDK Nuitee is merchant of record, with PayPal we are. Consequences:
  - Two pricing models: SDK via `margin` (commission paid out by Nuitee), PayPal via `margin: 0` + own commission. Final prices should be the same, otherwise the user sees a different price per payment method; therefore fetch offers separately per payment method or set `margin` and commission equal.
  - Cancellation and refund run at Nuitee or with us depending on the method; the reservation remembers the method (`payment_method`).
  - Legally and fiscally two roles at the same time.
- **Alternative to two providers:** also offer card, Google Pay and Apple Pay via PayPal (PayPal JS SDK with card fields and wallet buttons). Then it stays one merchant of record, one pricing model and one settlement. For the hackathon the PayPal button is enough.

## Further PayPal potential

- Ideas, not decided. Notes on PayPal products from general knowledge, check in the PayPal docs before implementing.
- **Fast payment in an emergency:** save the user's PayPal account (vault), then pay for an alternative with one tap, without redirect. Fits the use case in the evening at the reception.
- **Refund on cancellation:** refund as above, also automatically if LiteAPI cancels a booking.
- **Splitting costs among fellow travellers:** sharing trips is open ([domain.md](domain.md#open)). Afterwards collect a share of a booking from fellow travellers via PayPal, forwarding to the booker via `PayPalPayouts` (already exists).
- **Paid features:** e.g. notifications for incidents as a subscription (PayPal Subscriptions). Already mentioned as an idea for `accounting` in [architecture.md](architecture.md#legacy-code).
- **Pay later** (PayPal Pay Later / instalments) for expensive alternatives, available depending on the country.
- **Further booking types:** transport and activities (planned) via the same checkout.
- **Payment via AI assistants:** MCP tools (`/mcp`) could propose an alternative and send a PayPal link for approval; the user always confirms the payment themselves at PayPal. PayPal offers its own agent tools for this, to be checked.
- **Prefill guest data:** "Log in with PayPal" (`PayPalConnect`) provides name and email; deliberately separate from the app's login (`auth` stays isolated).

## Implementation in the module `bookings`

- `bookings.logic`:
  - `AlternativeCriteria` (record) and `AlternativeSearch` (`@Service`): `criteriaFor(appUserId, bookingId)`, `search(appUserId, bookingId, criteria)`.
  - `AlternativeRanking` (package-private): instruction and schema for `StructuredExtraction`, like `AccommodationExtractor`.
  - `AlternativeReservations` (`@Service`): prebook, booking, reconciliation, later cancellation.
  - LiteAPI client `LiteApiClient` (package-private or `bookings.logic.liteapi`), `RestClient` directly as in the assistant, no SDK. Returns own records, no LiteAPI types to the outside.
- `bookings.config`: `@ConfigurationProperties` `nfv.bookings.alternatives.*`: `api-key` (`LITEAPI_API_KEY`, Docker secret in the stack), `base-url`, `book-url`, `margin`, `default-radius`, `max-candidates`, `timeout`, `searches-per-hour` (per user).
- **Dependencies:** as before `bookings --> trips` (ownership, stop) and `bookings --> assistant`, new `bookings --> paypal`. LiteAPI is added as an external system (system context), PayPal is already there. `assistant` stays without domain knowledge; the only new thing is the domain-free parameter `ReasoningEffort` on `StructuredExtraction.extract` (2026-11-10). The ranking counts against its limit per user.
- **Alternative:** if LiteAPI is later needed outside `bookings` as well, move the client into a technical module like `assistant` that knows no domain modules.

## Persistence

- **Search results are not stored** (prices and offers expire). Optionally a short in-memory cache per search (a few minutes) for details and prebook.
- **New table `booking_reservation`** (Flyway, next free version, according to [architecture.md](architecture.md#persistence) currently `V29`), one row per booking attempt via LiteAPI:
  - `stop_id` (FK), `replaces_booking_id` (FK to `booking`, the unusable booking, empty for other use cases), `booking_id` (FK, `UNIQUE`, empty until booked), `hotel_id`, `offer_id`, `prebook_id`, `provider_booking_id`, `client_reference` (`UNIQUE`), `status` (`PREBOOKED`, `AUTHORIZED`, `BOOKED`, `FAILED`, `CANCELLED`), `net_price` according to prebook, `fee`, `price` (final price), `currency`, `payment_method` (`PAYPAL`, later `LITEAPI_SDK`), `paypal_order_id`, `paypal_authorization_id`, `paypal_capture_id`, `created_at`, `booked_at`.
  - Purpose: resumption if payment happened but the response was lost (reconciliation via `clientReference`/`GET /bookings`), later cancellation with the LiteAPI id, traceability towards Nuitee.
  - Within the module, hence `@ManyToOne` to `Booking` is allowed. Envers for status changes, `_aud` in the same migration.
- `booking` and `booking_accommodation` stay unchanged. The new booking brings coordinates, so `location` (`V28`) also applies.
- **Reason "not usable":** initially only on the reservation (`reason` `VARCHAR(32)`), no new `BookingStatus`. Whether the old booking needs its own status like `UNUSABLE` is open.

## API (proposal)

- `GET /bookings/alternatives/status` (LiteAPI available? Assistant available?).
- `GET /accommodation-bookings/{id}/alternative-criteria` → prefilled criteria with missing required fields.
- `POST /accommodation-bookings/{id}/alternative-searches` (body: criteria) → checked hits, recommendation, justifications.
- `POST /accommodation-bookings/{id}/alternative-reservations` (body: `offerId`) → reservation after prebook with final price and `approveUrl` of the PayPal order.
- `POST /booking-reservations/{id}/booking` (after returning from PayPal) → authorise, book at LiteAPI, capture; returns the new `Booking`. Errors: 422 `PAYMENT_NOT_APPROVED`/`PAYMENT_DECLINED` (as in `accounting`).
- `POST /stops/{stopId}/stay-reservations` (implemented 2026-11-07): reservation without a replaced booking for the hotel of an agent proposal (`STOPOVER`, `TARGET_STAY` without a booked accommodation), same body as `alternative-reservations`, PayPal returns to `/reisen/{trip}/stops/{stopId}/unterkunft`. The new booking is attached to the stop, see [advisor.md](advisor.md#accepting-an-overnight-stay).
- Later in general `POST /stops/{stopId}/accommodation-searches` for use cases without an existing booking, same criteria.
- Errors via `BookingExceptionHandler`: 503 `ALTERNATIVES_UNAVAILABLE`, 409 `OFFER_EXPIRED` (LiteAPI `2001`), 409 `PRICE_EXCEEDED` (prebook above max. price), 429 `ALTERNATIVES_RATE_LIMITED`, 400 `INVALID_BOOKING_INPUT` (criteria incomplete).
- **MCP:** later read-only `find_alternative_accommodation`; booking deliberately not via MCP (the user confirms payment at PayPal).

## Frontend

- Button "Search alternative" in the booking details, sub-route `buchungen/:bookingId/alternativen`.
- Criteria form, result list with map, "Pay with PayPal" (redirect, return to the sub-route). The PayPal return needs a public URL, locally via ngrok as in [paypal.md](paypal.md#local-without-localhost-sandbox).
- Later payment SDK: script from `payment-wrapper.liteapi.travel`, adapt CSP and service worker (do not cache) for it.
- Mobile first: the typical case is in the evening in front of the reception.

## Privacy and legal

- To LiteAPI: coordinates, period, occupancy, on booking name and email of the booker and guests. To OpenAI: facts of the previous accommodation and of the candidates, no names or contact details of the travellers, no booking number. Notes in the frontend and in the privacy policy.
- Payment only at PayPal (or in the payment SDK), payment data reaches neither backend nor model. PayPal receives the final price and a description without guest data.
- With PayPal we are merchant of record: we resell the overnight stay. To be clarified before live operation: role as intermediary or travel seller (information obligations, terms and conditions, right of withdrawal, legal notice), taxes on the commission, liability on cancellation by the hotel. For the hackathon sandbox only.

## Tests

- LiteAPI client, `StructuredExtraction` and `PayPalCheckout` mocked in integration tests, `nfv.bookings.alternatives.api-key=` empty in the test properties. New PayPal calls in `PayPalClientTest` against `MockRestServiceServer`.
- Payment cases: booking fails after authorisation → void, no capture; abort between booking and capture → poller captures; duplicate capture → idempotent.
- Cases: prefill (missing guests/coordinates, stay in progress, proportional price), the hard check discards offers that are too expensive, too far away and below the minimum stars (also without stars), `starRating` to LiteAPI, ranking discards foreign ids, the assistant excludes non-comparable ones and gets the profiles, search without assistant, prebook above max. price → `PRICE_EXCEEDED`, `2001` → `OFFER_EXPIRED`, booking idempotent via `clientReference`, new booking with `source` `SEARCH`.
- Manually against the sandbox (`sand_` key, test cards of the payment SDK).

## Implementation (as of 2026-11-03)

- **Code:** `bookings.logic` `LiteApi` (client, own records), `AlternativeSearch` (criteria, hard check), `AlternativeRanking` (assistant: comparison, exclusion, ranking), `HotelProfiles` (profiles from `/data/hotel`, cache), `AlternativeReservations` (flow) with `ReservationSteps` (one transaction per step), `Pricing`, `SearchLimiter`; `bookings.api.AlternativeController`; `bookings.config.AlternativesProperties` (`nfv.bookings.alternatives.*`); frontend `src/bookings/AlternativesSheet.tsx`.
- **Payment only PayPal** (decision 2026-11-03), no payment SDK. Booking at LiteAPI always with `ACC_CREDIT_CARD`, rates and prebook with `margin: 0`.
- **Commission:** `fee-percent` (default 10 %) on the net price, capped at the `suggestedSellingPrice` if that is above the net price. The max. price applies to the final price.
- **Status** of the reservation: `PREBOOKED`, `PAYMENT_PENDING` (order created), `AUTHORIZED`, `PROVIDER_BOOKED` (booked at LiteAPI, money not yet collected), `BOOKED`, `FAILED`.
- **Resumption:** `POST /booking-reservations/{id}/booking` continues at the last stored step and can be repeated (PayPal request ids, `clientReference` `nfv-stay-<id>`). Since 2026-11-06 a background job does this too, `4005` is resolved via the search for the `clientReference`, a declined capture leads to `NEEDS_ATTENTION`, see [paypal.md](paypal.md#stability).
- **Errors from LiteAPI:** according to the reference there is no code `2001`. Every 4xx response to prebook or book (except 401/403) is `OFFER_UNAVAILABLE`, 5xx and network errors `ALTERNATIVES_UNAVAILABLE`.
- **Guests:** LiteAPI needs one guest per room; the booker is entered for every room. The number of guests is distributed evenly across the rooms (`Pricing.adultsPerRoom`).
- **Hotel and guest data** are stored in the reservation (table `booking_reservation`, `V30`), not historised. Guest data is deleted on `BOOKED` or `FAILED`. Exception on request: "Remember for this trip" stores it on the trip (`Trip.guest`, [domain.md](domain.md)).
- **Checking the guest data:** frontend (`bookings/holder.ts`) and backend check the same rules. Errors are shown at the field (after leaving the field or on clicking Next), the Next button stays clickable for this. If the backend rejects a field, `INVALID_BOOKING_INPUT` names it in `field` (`firstName`, `lastName`, `email`, `phone`), the form marks it. Without `field` the problem is not the guest data (e.g. the offer), the message says so. Hotel data comes from `GET /data/hotel`, check-in/check-out times from there, otherwise 15:00/11:00.
- **Reason** ("booked wrongly" etc.) is not stored. The previous booking is only marked as cancelled in the app on request (`POST /bookings/{id}/cancellation`).
- **Cancellation** of a booked alternative with PayPal refund since 2026-11-06 ([paypal.md](paypal.md#stability)). The commission is refunded as well for a free cancellation.
- **Payment in the sheet** via the PayPal button instead of redirect ([paypal.md](paypal.md#paypal-button-in-the-sheet)). Guest data comes from the trip (`Trip.guest`), otherwise from the device (`localStorage`); if it is complete, choosing an offer leads directly to payment. If the criteria from the booking are complete, the sheet searches immediately, the form only exists under "Adjust search".
- **Not implemented:** reconciliation via `GET /bookings/{id}`, MCP tools, exclusion of the previous accommodation via similar names (only via `placeRef` and the marker "possibly the previous accommodation" up to 50 m).
- **Trying it locally:** `LITEAPI_API_KEY` (sandbox key `sand_…`), `PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` (sandbox), frontend via ngrok (PayPal does not accept `localhost` as return URL), see [paypal.md](paypal.md#local-without-localhost-sandbox). `OPENAI_API_KEY` optional for the ranking. In the stack Docker secret `nfv_liteapi_api_key`, see [deployment.md](deployment.md).

## Route after rebooking

- **Starting point:** routes run from stop to stop via the coordinates of the stops (`RoutePlanning`), the accommodation plays no role for this. If the alternative is in the same place, nothing changes on the route.
- **Other place:** more than 10 km from the stop (`trips.logic.StayPlaces.OTHER_PLACE_METERS`, straight line). After booking (`AlternativeReservations.followStay`, also in the background job) the stop moves to the hotel (`TripEditing.moveStopToStay`):
  - Coordinates of the hotel, name = place of the hotel according to LiteAPI (`city` from `GET /data/hotel`, not stored in the reservation). Without a place the name stays. A stop without coordinates only gets the coordinates and keeps its name.
  - Waypoints from suggestions (`SUGGESTION`) of the routes to and from the stop are dropped, they lead to the old place. Waypoints of the user (`USER`) stay. Both routes are replanned (fastest way), incidents then assess them as usual.
  - If moving fails, the booking remains (log only).
- **Response:** `ReservationDto.replannedRouteIds` names the replanned routes, only in the response that booked (not stored). If the background job books, the list is empty; the stop is moved anyway.
- **Frontend:** for "Rebooked" it says "The stop is now in …, the route has been replanned". If the suggestions for a route return more than one option, there is the button "Choose route …" to the existing RouteSheet, otherwise nothing is asked.

## Checking the route before booking

- `GET /booking-reservations/{id}/route-check`, called in the payment step (after prebook, before PayPal), calculated and not stored (`bookings.logic.StayRouteCheck`).
- Only for another place: best way from the departure at the previous stop to the hotel (`recommendations.Suggestions.towards`: without waypoints, without reference, incidents as in planning). Result: arrival incl. delay, latest check-in (`checkInUntil` of the hotel on the arrival day in the hotel's time zone, midnight counts as unknown), `late`, `blocked` (even the best way leads through a closure).
- Without a check (`problem`): first stop (`FIRST_STOP`), departure already passed (`UNDER_WAY`, e.g. on site), missing departure/coordinates, no way, no graph. Then the app only shows the note about the change of place.
- Only a note in the payment step ("Other place", arrival, warning), booking remains possible. Only the chosen accommodation is checked, not the whole result list (routing per candidate would be too slow).
- New dependency `bookings --> recommendations` (no cycle, `recommendations` does not know `bookings`).

## Open points

- Live key and activation at Nuitee, card in the LiteAPI account for `ACC_CREDIT_CARD`.
- Amount of the commission, PayPal fees (who bears them, include them in the commission), commission on cancellation.
- Max. price: total price or per night, markup on the previous price as default (e.g. +20 %)? The previous booking is already paid, so the price of the alternative is additional.
- Children and age, `guestNationality` (profile or device), language of the hotel data.
- Status of the unusable booking (`CANCELLED` or its own status) and whether it stays visible in the stop list.
- Check LiteAPI webhooks for status changes; until then reconciliation via `GET /bookings/{id}` when opening.
- Further use cases: other place (region from another stop), recommendation when a route cannot be driven in time (module `advisor`, interface `StayOptions`, see [advisor.md](advisor.md#later-accommodations-and-message-to-the-accommodation)), relation to incidents at accommodations.
