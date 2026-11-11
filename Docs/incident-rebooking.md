# Concept: from trip planner to replanning app

- **Status:** draft (2026-11-05). Implemented 2026-11-06: PayPal stable (button in the sheet, completion in the background, special cases, cancellation with refund), PayPal added value in the UI, parts of the UX revision. Stayed with the LiteAPI sandbox (Philipp: no demo hotels). The plan after an incident is implemented separately.
- **Goal:** the app becomes the help for the moment a trip goes wrong. An incident hits the route or an accommodation falls through, the app already has a ready plan B, the user confirms it with one tap and pays with PayPal.
- **Trip planning remains a means to an end:** it provides stops, routes and bookings so that the app can assess the situation. It should be quick and moves into the background in the UI.
- **AI stays invisible:** the user sees recommendations and reasons in plain text, but no hints of an assistant. Hard facts such as price, availability and reachability are still checked by the backend. The model only selects and justifies ([accommodation-alternatives.md](accommodation-alternatives.md#roles-assistant-and-liteapi)).
- Builds on what exists: assessment of consequences ([consequence-assessment.md](consequence-assessment.md)), route suggestions ([recommendations.md](recommendations.md)), search and booking of alternative accommodations ([accommodation-alternatives.md](accommodation-alternatives.md)), consequences on the map and `TripAlternativesSheet`.

## Core promise

> "Something happened on your route. We already have a solution, tap confirm."

| Step | today | goal |
|---|---|---|
| Detect | traffic feed → incident → consequence with category (`TOO_LATE`, `DELAYED`, `NO_IMPACT`) | stays |
| Inform | only visible when the user opens the trip | push notification with situation and solution in one sentence, banner on the trip |
| Find solution | dialog with two ways (route, accommodation), each opens its own sheet with a form | **one recommended plan** per consequence, fully calculated, alternatives collapsed below |
| Decide | check criteria, search, pick a hit, type guest data | one tap on "Rebook for 142 NZD", everything prefilled, adjusting only on request |
| Pay | redirect to PayPal and back | PayPal button in the sheet (popup), later with saved PayPal without redirect |
| Complete | new booking created, old one marked as cancelled only on request | confirmation with directions, old booking marked, link to cancel at the old provider, message to the old accommodation pre-written |

## Two entry points, one flow

1. **Incident on the route** (automatic): consequence `TOO_LATE` or `DELAYED` above a threshold (e.g. 30 min). Push and banner open the plan.
2. **"Problem with my accommodation"** (by the user, on site): big button at the current stop as soon as the check-in day is reached. Reasons as chips ("No booking found", "Overbooked", "Can't get there"), no form. Then the same plan screen.

Both end in the same **plan sheet**. It replaces `TripAlternativesSheet` as the target and bundles `RouteSheet` and `AlternativesSheet`. Those two remain as detail views behind "Other options".

## The plan (backend)

- **New:** `GET /api/v1/consequences/{routeId}/{incidentId}/plan` and `POST /api/v1/stops/{stopId}/accommodation-problem` (entry point 2). Both return a `RescuePlan`, calculated on every call and never stored (principle [route-choice](recommendations.md): only the choice is stored).
- `RescuePlan`: `situation` (category, delay at the deadline, plain text), `recommended` (one option), `others` (up to three), `computedAt`.
- **Option types** and when they are built (deterministic rules, in this order):

| Situation | Option | Source |
|---|---|---|
| Detour meets the deadline | `REROUTE`: other route, free of charge | `Suggestions.forConsequence` (draft in recommendations.md, still open) |
| Closure with end, later/earlier departure suffices | `SHIFT_DEPARTURE` | `DepartureShiftGenerator` (open) |
| Destination not reachable in time, accommodation booked at the destination | `STAY_BEFORE`: accommodation before the closure, on the route, reachable before the latest check-in | LiteAPI search with region = point on the route before the affected section (new use case "other place") |
| Accommodation not usable (entry point 2) | `STAY_NEARBY`: equivalent accommodation nearby | `AlternativeSearch` with prefilled criteria, unchanged |
| Delay, but the deadline is narrowly met | `NOTIFY_HOST`: message "arriving around 21:30" to the accommodation, pre-written | contact from `AccommodationDetails` |
| always | `KEEP`: "Continue as is (+1 h 10 min)" | `KEEP_ROUTE` |

- **Choice of the recommendation:** free before paid, provided the deadline is met. Otherwise the cheapest accommodation that meets all criteria, in the order of the ranking. The model only writes the justification sentence ("Same category, 18 km before the closure, check-in until 23:00").
- **Criteria without a form:** max. price = previous price + markup (suggestion +25 %), guests/rooms from the booking or `Trip.travelers`, period from the booking. The user sees the values only under "Adjust".
- **Guest data** (name, email, phone) stored once in the profile, asked for on the first rebooking and remembered. Today `AlternativesSheet` starts with empty guest data.
- **Time budget:** the plan must be ready within a few seconds. Query LiteAPI and routing in parallel, the ranking with a timeout (today it already falls back to price/distance without the assistant).
- **Precompute for the push:** when a consequence `TOO_LATE` is created, a listener calculates the plan once, only to build the text of the push ("Detour via SH 7, +20 min"). On opening it is recalculated.

## Making PayPal stable

### What is unstable today

Derived from the code. The flow has never run against the sandbox.

1. **Full redirect to PayPal in an installed PWA.** The button is a link to `approveUrl` (`AlternativesSheet.tsx`). In the installed app (`display: standalone`) PayPal opens as a foreign page in a browser window outside the app, the return URL lands there, not in the app. The login is in `sessionStorage` (`oidcConfig.ts`), which does not exist there. Consequence: login again, and in the app itself the flow hangs at the "Pay" step. This is the most likely reason for aborts in the demo on the phone.
2. **Return URL depends on `nfv.frontend-url`.** Locally ngrok is needed, the URL changes with every tunnel start. Login redirect, CORS and PayPal return must then be changed together.
3. **Completion only if the user comes back.** Authorising, booking at LiteAPI and capture run synchronously in `POST /booking-reservations/{id}/booking`. If the user closes the page after approval at PayPal, the reservation remains in `PAYMENT_PENDING` or `AUTHORIZED`. A reconciliation in the background is missing (docs: "No poller").
4. **Special cases end in the log.** LiteAPI `4005` (already booked, response lost) and a declined capture after a successful booking are only logged. The reservation stays, the user sees an error although the room may be booked.
5. **Two external sandboxes in a row.** LiteAPI sandbox (`ACC_CREDIT_CARD` with hidden test card) and PayPal sandbox must both work at the same time. If one fails or changes responses, the demo fails, and PayPal looks guilty.
6. **No webhooks.** The state at PayPal is only read actively.
7. **Never tested end to end.** The tests mock PayPal and LiteAPI. Field names and status (e.g. `payer-action` link, `firstAuthorization`) are built only against the docs.

### Measures, in this order

1. **PayPal JS SDK instead of redirect (Smart Buttons).** The PayPal button is rendered in the plan sheet, `createOrder` calls our `POST …/alternative-reservations` (returns the order id), `onApprove` calls `POST /booking-reservations/{id}/booking`. The user stays in the app, without return URL, without ngrok and without losing the login. The redirect remains as a fallback. For this: script from `www.paypal.com/sdk/js` (client id is public), adapt CSP and service worker (do not cache), `intent=authorize` in the script parameter.
2. **Resumable in the background.** A `@Scheduled` job (every minute) continues every reservation in `PAYMENT_PENDING` (order status at PayPal `APPROVED`), `AUTHORIZED` or `PROVIDER_BOOKED` with the same logic as `complete`. Expired or aborted ones (`PAYMENT_PENDING` > 3 h) go to `FAILED`. When opening the trip, the frontend queries open reservations and shows "Rebooking in progress" or the result.
3. **Resolve special cases instead of logging.** `4005`: look up the booking at LiteAPI via `GET /bookings?clientReference=…`, on success continue with capture. Capture declined: reservation `NEEDS_ATTENTION`, in the frontend as "Being checked" with a note instead of an error.
4. **Demo provider for accommodations.** A second implementation behind `LiteApi` (`nfv.bookings.alternatives.provider=demo`) with fixed, realistic hotels in New Zealand, prebook and booking always successful. PayPal runs for real against the sandbox. That way the demo depends only on PayPal, and exactly that should be visible. For real operation LiteAPI stays.
5. **End-to-end run against the sandbox** on the test server (fixed domain, no ngrok): once with the demo provider, once with the LiteAPI sandbox. Check field names and status and record them as a test fixture for `PayPalClientTest`.
6. **Webhooks** (`CHECKOUT.ORDER.APPROVED`, `PAYMENT.AUTHORIZATION.VOIDED`, `PAYMENT.CAPTURE.COMPLETED/DENIED/REFUNDED`) on the test server, they only trigger the job from 2. Optional, the job is enough.

## Making PayPal's added value visible

- **One tap in an emergency:** Smart Buttons in the sheet (above, 1). The most important point for the presentation: no form, no card data, no leaving the app.
- **Move money only when the room is secured:** authorise → book → capture, otherwise release. Already exists, only needs to be explained in the UI ("The amount is reserved, it is charged only when the hotel confirms").
- **Remember PayPal for next time (vault):** on the first rebooking "Save PayPal for emergencies" (`payment_source.paypal.attributes.vault`), afterwards rebooking without an approval window. Fits exactly the case "in the evening at the reception". Before building, check whether vaulting can be enabled in the sandbox app and works with `intent=AUTHORIZE`. Stretch goal.
- **Automatic refund:** if the user cancels an alternative booked in the app within the deadline, LiteAPI cancellation and PayPal refund (`refundCapture` exists) in one step.
- **Splitting costs** (later, needs shared trips): fellow travellers pay their share via a PayPal link, forwarding via Payouts (exists).
- **Pay Later** for expensive emergency bookings: only one parameter in the JS SDK, availability per country, check for New Zealand before the demo.

## Additional features

Sorted by benefit for the core idea.

1. **Push notifications** (Web Push, the service worker already exists): on a new or worsened consequence `TOO_LATE`/`DELAYED`, with the solution in the text. Without push the app stays passive, and the core idea does not come into its own. iOS only for installed PWAs.
2. **Incidents near the accommodation** (`booking_accommodation.location`, GIST index from `V28`): events in the vicinity (e.g. 2 km, `ST_DWithin`) during the stay, such as closure of the access road, storms, flooding. New consequence kind at the stop instead of at the route, plan option `STAY_NEARBY`. Closes "incidents at accommodations" from [domain.md](domain.md#open).
3. **Latest arrival from the booking:** deadline = `checkInUntil` of the accommodation instead of only `Stop.arrivalAt` (open in consequence-assessment.md). This makes "can't get in any more" precise and `NOTIFY_HOST` meaningful.
4. **Message to the accommodation:** pre-written email/SMS in the local language ("Due to a closure on SH 6 we will only arrive around 22:00") or cancellation request to the old accommodation after rebooking. Opens `mailto:`/`sms:`, the app sends nothing itself.
5. **Demo button "Simulate disruption"** for the presentation (only for test accounts, `TestTrafficEventController` exists): creates a closure before the next stop, the push arrives, the plan shows an accommodation before it.
6. **Assistant via MCP, read-only:** `get_rescue_plan` for Claude/ChatGPT, paying stays in the app.

## UX revisions of the existing UI

Derived from code and mockups, not clicked through on a device. Please add what you noticed.

- **Trip view with situation at the top:** status line above the map, "All on track · arrival Wanaka 17:40" or "1 problem · view solution" (yellow). Today the situation is only apparent from chips on the map.
- **"My trips":** current trip large with status, planned ones below, counter only for real problems (already exists in the backend).
- **Remove AI hints:** "Sorted by the assistant by equivalence ✦", "Recommendation ✦", "Found by the assistant on the web". Instead of ✦ a neutral marker "please check" only where the user has to confirm something (extracted bookings).
- **Remove internal sentences:** e.g. "Alternatives are recalculated when needed. Only what you select is stored." in `TripAlternativesSheet`.
- **Forms behind "Adjust":** collapse criteria and guest data in the rebooking flow, prefilled from booking and profile.
- **Progress while rebooking:** three visible steps ("Price secured", "Payment reserved", "Hotel confirmed") instead of a spinner. Errors in everyday language with the next step.
- **Confirmation page:** new accommodation with map, "Start navigation" (Google/Apple Maps link), booking number, "Cancel old booking" with link.
- **Faster planning:** start "New trip" directly with "Paste booking confirmation", derive stops and times from it. The fastest way to a trip that can be assessed.
- **Small things:** consistent empty and loading states (skeletons as in `RouteSheet`), target size of buttons ≥ 44 px in the sheet, back behaviour of the sheets (browser back closes the sheet).

## Prioritised implementation

**P0, core for the demo**

1. Demo provider for accommodations + end-to-end run with the PayPal sandbox on the test server.
2. PayPal Smart Buttons in the sheet instead of redirect, redirect as fallback.
3. Background job for open reservations, resolve special cases `4005` and capture errors.
4. Plan endpoint with `STAY_NEARBY`, `STAY_BEFORE`, `KEEP` (route initially via the existing `RouteSheet`), plan sheet with one tap, guest data in the profile.
5. Entry point "Problem with my accommodation" at the current stop.
6. Remove AI hints and internal texts, status line on the trip.

**P1, rounds off the core idea**

7. `Suggestions.forConsequence` (detour in the plan, `REROUTE`) and `SHIFT_DEPARTURE`.
8. Web Push for `TOO_LATE`/`DELAYED`.
9. Demo button "Simulate disruption".
10. Deadline from `checkInUntil`, option `NOTIFY_HOST`.
11. Cancellation of an alternative booked in the app with automatic refund.

**P2, afterwards**

12. Incidents near the accommodation (`V28`, `ST_DWithin`).
13. PayPal vault (without approval window), Pay Later.
14. Webhooks, MCP tool `get_rescue_plan`, splitting costs.

## Decisions for Philipp

1. **Demo provider for accommodations:** do you agree that the demo runs with fixed demo hotels and PayPal for real? (Recommendation: yes, LiteAPI stays for operation.)
2. **Smart Buttons instead of redirect:** do you agree with the PayPal JS SDK in the frontend? (Recommendation: yes, solves the PWA problem.)
3. **Max. price without a form:** previous price + 25 % as default, or no upper limit and only sorted by price?
4. **How invisible is the AI:** remove all ✦, also for extracted bookings, or keep a neutral "please check" marker there? (Recommendation: neutral marker.)
5. **Push in P0 or P1:** without push the app seems passive in the demo; with the demo button the banner is enough for the presentation. (Recommendation: P1.)
