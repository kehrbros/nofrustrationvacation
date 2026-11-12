# Demo trip (jury test)

- **Status:** implemented 2026-11-12 (module `demo`). Not yet run against the full NZ network, OpenAI, LiteAPI and PayPal sandboxes: the places of the incidents and the expected outcomes below are designed, not observed.
- **Goal:** the jury (and testers) can try the main workflow without typing a trip: trip with stops, routes and bookings is there, incidents come in, solutions are proposed automatically, the user taps the incident on the map, picks a solution or replans themselves.
- **Why not a Flyway migration** (decided 2026-11-12, Philipp asked for a migration first): routes (`route_section`) and incidents (`incident_segment`) point to `road_segment` ids that only exist after the GraphHopper import, which runs after Flyway. Fixed dates go stale, and every juror changes the trip by choosing solutions. The demo trip is therefore created on request through the normal logic, relative to the current date.

## Usage

- **My trips:** card "Load demo trip" below the list, only if the server offers it (`GET /api/v1/demo-trips` → 204). Each tap creates a new trip and opens it. Takes up to a minute (5 routes planned, 6 incidents processed).
- **API:** `POST /api/v1/demo-trips?language=de|en` (any logged-in user) → `tripId` and per incident `key`, `placedOnRoute`, `outcome`.
- **Availability:** only with the manual test source (`nfv.input.sources.manual.enabled=true`, env `NFV_MANUAL_SOURCE_ENABLED`, GitHub variable on the test server), because the demo reports its incidents through it, see [traffic-feed.md](traffic-feed.md#test-source-manual). Without it the endpoint does not exist (404) and the card is hidden.
- **Language:** trip name, booking notes and incident texts in the language of the app at the time of loading.

## The trip

- **Period:** 11 days, day 1 = the day before yesterday in New Zealand (`Pacific/Auckland`, decided 2026-11-12 with Philipp): the trip is under way, the user is in Kaikōura, the next drive is on day 4 (tomorrow) at 09:00. All times below are New Zealand time; the app shows them in the time zone of the device.
- **Travellers:** 2. Bookings are entered manually (source `MANUAL`, provider "Demo", reference `DEMO-…`), names marked "(Demo)", price in NZD.

| # | Stop | Arrival | Departure | Booking (check-in window) |
|---|---|---|---|---|
| 1 | Christchurch | – | day 1 09:00 | – |
| 2 | Kaikōura | day 1 13:00 | day 4 09:00 | Kaikōura Seaview Motel, 3 nights, 14:00–21:00 |
| 3 | Lake Tekapo | day 4 16:00 | day 6 10:00 | Tekapo Starlight Lodge, 2 nights, 15:00–19:00 |
| 4 | Wānaka | day 6 14:00 | day 8 10:00 | Wānaka Lakefront Apartments, 2 nights, 14:00–20:00 |
| 5 | Queenstown | day 8 12:15 | day 10 07:30 | Queenstown Lakeview Hotel, 2 nights, 15:00–22:00 |
| 6 | Milford Sound | day 10 12:30 | day 11 10:00 | Milford Sound Rainforest Lodge, 1 night, 14:00–20:00 |

- **Route Christchurch → Kaikōura** was driven on day 1: no incidents there, a passage in the past is never affected.
- **Route Wānaka → Queenstown** gets a user waypoint at Cardrona, so it runs over the Crown Range instead of through Cromwell (shows a chosen route and gives the closure there a real alternative).

## The incidents

- Reported through the manual source like the test tool, placed onto the planned route (`RoutePlacement`, at most 5 km from the given point; otherwise reported at the given point and `placedOnRoute` is false). Road number from the placement, both directions.
- **Shared:** event id `demo-<key>-<day 1>`, so all demo trips loaded on the same day share one event per key (loading again only re-reports it). Like every test event they affect the routes of all users there.
- Texts contain no times of day, otherwise a closure would count as "times unconfirmed".

| Key | Route | Incident | Validity | Expected |
|---|---|---|---|---|
| `burkes-pass` | Kaikōura → Lake Tekapo (tomorrow) | closure SH 8 at Burkes Pass (snow) | day 4 06:00 – day 5 11:00 | `TOO_LATE` (wait > `max-wait`) → automatic plans, e.g. long detour via the Waitaki valley (late, tell the lodge) or a night in Fairlie/Geraldine with a LiteAPI hotel and PayPal, onward the next morning, Tekapo booking kept with a to-do |
| `cheviot` | Kaikōura → Lake Tekapo | traffic jam at Cheviot (SH 1), 3 min | from now until day 4 18:00 | `NO_IMPACT`: listed, not counted |
| `lindis-pass` | Lake Tekapo → Wānaka | crash on Lindis Pass, 25 min | from now until day 6 16:00 | `DELAYED`, arrival still in time → no automatic run; tap → "Find solutions" or "Replan myself" |
| `omarama` | Lake Tekapo → Wānaka | roadworks near Omarama, 10 min | reported and ended at once | resolved alert with green tick on the map, removable |
| `crown-range` | Wānaka → Queenstown | closure Crown Range (black ice) | from now, until further notice | `TOO_LATE` (not passable) → automatic solutions; recommended: free detour via SH 6 / Cromwell, on time |
| `milford-road` | Queenstown → Milford Sound | closure SH 94 in the Eglinton Valley (avalanche risk) | from now, until further notice | `TOO_LATE`, the only road → plan "Go to … instead" (replacement destination within 80 km, e.g. Te Anau Downs) with a LiteAPI hotel and PayPal, Milford booking on the to-do list to cancel |

## Walkthrough for the jury

1. My trips → "Load demo trip" → the trip opens as the current trip ("day 3 of 11"), with 6 stops, 5 routes and 5 bookings.
2. Map: red chips on Kaikōura → Lake Tekapo, Wānaka → Queenstown and Queenstown → Milford Sound (too late), a chip on Lindis Pass (delay), a green tick near Omarama (resolved). Solutions for the red ones are prepared in the background (1–2 minutes).
3. Tap Wānaka → Queenstown → solutions → apply the detour via Cromwell → the route is planned again around the closure, the chip disappears.
4. Tap Kaikōura → Lake Tekapo → pick a plan; with a hotel: guest data, one PayPal approval (sandbox buyer account), then a new stop before Lake Tekapo and a to-do for the lodge.
5. Tap Lake Tekapo → Wānaka → "Find solutions" or "Replan myself".
6. Tap Queenstown → Milford Sound → replacement destination with hotel and PayPal; the old booking goes on the to-do list.
7. More: booking details → "Find alternative" (LiteAPI), edit a stop outside its booking dates (conflict dialog), report an own incident with the test tool (role `tester`).
8. Load again for a fresh trip; delete old ones with the trip menu.

## Implementation

- Module `demo` at the very top, nothing knows it: `demo → trips` (`TripLogic`, `TripEditing`), `bookings` (`BookingLogic`), `incidents` (`RoutePlacement`), `input` (`ManualSource`, `TrafficPolling`, `TrafficEventProcessor`, `TrafficEventInspection`).
- `demo.logic.DemoScenario`: the data relative to day 1 (pure), `demo.logic.DemoTrips`: creates trip, stops (each planned at once), bookings, the Cardrona waypoint, then reports the incidents (poll and process synchronously). `demo.api.DemoTripController`. No schema change.
- Not in `openapi.json` (the controller only exists with the manual source), the frontend calls it without generated types (`frontend/src/trips/demoTripApi.ts`, `DemoTripCard.tsx`).
- Tests: `DemoScenarioTest` (times in order, stays within their stops, keys, no incidents on the driven route, no times in texts), `DemoTripApiTest` (trip, stops, routes, bookings, incident results; the test network has no South Island, so nothing is planned there).

## Open

- Verify on the test server with the NZ network: are all incidents `placedOnRoute`, does SH 1 coastal get chosen for Christchurch → Kaikōura, do the expected categories and plans come out. Adjust points or times in `DemoScenario` if not.
- LiteAPI sandbox: hotels near Fairlie/Geraldine and Te Anau Downs not checked.
- Time zone: stops and test incidents are entered and shown in the device time zone, accommodations use their own. A juror outside New Zealand sees the times shifted (consistent, but surprising).
- Old demo trips are not cleaned up; demo events stay until their validity ends.
