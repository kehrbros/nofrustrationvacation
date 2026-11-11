# Assessment of consequences – draft

- **Status:** backend implemented (`V35`, see [Implementation](#implementation)), frontend still open. Extends the matching (`ConsequenceMatcher`, [domain.md](domain.md#matching-logic-consequencematcher)) with an assessment against the stops.
- **Goal:** every consequence gets a category so that the user sees what matters for their trip:
  - `TOO_LATE`: with the incident the user arrives at the target stop after the arrival time (or does not get through at all).
  - `DELAYED`: noticeable delay, but the arrival time is still met.
  - `NO_IMPACT`: incident on the route at the time of passage, but without significant delay (e.g. NZTA `Caution`).
- **Not part of this draft:** solutions and alternatives (detour, later departure, other accommodation). The assessment only describes the situation. Solutions are proposed by the module `advisor`, see [advisor.md](advisor.md).

## Reference time

- **Deadline = arrival of the target stop** (`Stop.arrivalAt` of `Route.toStop`): the time by which the user must be there at the latest (start of the stay).
- **Planned arrival** = `expectedPassageEnd` of the last section of the route (departure at the start stop plus driving time, from `RoutePlanning`).
- **Slack** = deadline − planned arrival.
- **Without deadline** (target stop without arrival): no category `TOO_LATE`, only `DELAYED` or `NO_IMPACT`, note "arrival time missing".
- **Planned arrival already after the deadline** (route too late even without incidents, open point "comparison with the planned arrival" in [domain.md](domain.md#trips)): `TOO_LATE` only if an incident increases the delay. The route carries the note "arrival not reachable according to planning", the consequences are assessed as `DELAYED` or `NO_IMPACT` according to their delay.

## Delay per consequence

- The assessment runs per **route**, not per consequence: delays add up, and a delay shifts the passage of all later sections. This also resolves the open point "propagate delay" ([domain.md](domain.md#open)) for the assessment. The expected passages at the sections stay unchanged (planning).
- Flow (`ConsequenceAssessment.assessRoute(routeId)`, `incidents.logic`):
  1. Sections of the route in order, active incidents on their segments (like `matchRoute`).
  2. `shift` = 0. For each section with an incident: shifted passage = expected passage + `shift`.
  3. If the shifted passage hits the incident (`IncidentOverlap`, with time windows), determine the delay (table below), `shift` += delay.
  4. Expected arrival after this incident = planned arrival + `shift`.
- The category is assigned to **the consequence** that causes the situation: the first one after which the expected arrival exceeds the deadline is `TOO_LATE`, as are all later ones with their own delay. Before that `DELAYED` or `NO_IMPACT` according to their own delay.

| Incident | Delay |
|---|---|
| Closure (`BLOCKED`) with time windows | wait until the end of the hit window: `windowEnd` − shifted passage |
| Closure without window, with `validTo` | `validTo` − shifted passage |
| Closure without end (until further notice) or waiting time above `max-wait` (default 12 h) | not passable → `TOO_LATE` (without deadline: `DELAYED`, delay "unknown") |
| Delay (`DELAYED`) with `expectedDelay` (source or enrichment) | `expectedDelay` |
| Delay without value | assumption of the source (`assumedDelay` on the incident, for NZTA e.g. `Caution` 0, `Delays` 15 min, `Crash` 30 min, see [traffic-feed.md](traffic-feed.md#delay-without-a-value)), marked as "estimated"; without assumption 0 |

- **Category from the own delay:** below `no-impact-below` (default 5 min) → `NO_IMPACT`, otherwise `DELAYED`, with exceeded deadline `TOO_LATE`.
- **Relevant for cars only:** `vehicleScope` `HEAVY_ONLY` from the enrichment ([traffic-feed.md](traffic-feed.md#enrichment-messageenrichment)) → delay 0, `NO_IMPACT`.
- **Times unconfirmed:** `timesUnconfirmed` (closure with times only in the text, not read yet, [traffic-feed.md](traffic-feed.md#processing-trafficeventprocessor)) → `DELAYED` with delay 0, no shift, never `TOO_LATE`, note "check times".
- **New hits through shifting:** a delay can push a later passage into a time window (e.g. 20:40 planned, 30 min traffic jam before, tunnel closed from 21:00). The assessment finds this because it checks all active incidents of the route. The `ConsequenceMatcher` creates a consequence for it (hit `BY_SHIFT`) so that it is displayed.

## Passage tolerance (decided 2026-11-04)

- **Problem:** the passage is a calculated value. Someone who is supposed to be at the tunnel at 20:55 would otherwise not be warned about a closure from 21:00, although 10 min of delay are enough.
- **Rule:** for the overlap check the (shifted) passage is extended on both sides by `max(min-tolerance, tolerance-ratio × time since departure)`, default `max(15 min, 10 %)`. Example: 2 h after departure ±15 min, 4 h after departure ±24 min.
- The waiting time is still calculated from the passage without tolerance. If the passage without tolerance lies before the window, the waiting time is the time from window start to window end (assumption: the user arrives just too late).
- **Hit only through the tolerance:** `match` `MARGINAL`, category at most `DELAYED`, never `TOO_LATE`, note "tight: closure starts 21:00, passage planned 20:55". If such a consequence delays the arrival, its waiting time does not count towards `shift` (otherwise an uncertain hit would distort later assessments).
- Applies in `IncidentOverlap` as a separate call with tolerance; `ConsequenceMatcher` creates consequences also for tight hits. The queries on the hull (`findPassing`) extend their time window by the largest possible tolerance (`tolerance-max`, default 2 h).

## Ranking

- Within a trip and a route:
  1. `TOO_LATE`, descending by delay at the deadline (not passable first).
  2. `DELAYED`, descending by own delay.
  3. `NO_IMPACT`, ascending by passage.
  - On a tie: earlier passage first.
- One summary per route: worst category, expected arrival, slack or delay.
- Counter of the trip list (`GET /incidents/trip-counts`): only `TOO_LATE` and `DELAYED`. `NO_IMPACT` appears in the list but does not count.

## Data model (deviation from [domain.md](domain.md#incidents), decided with this draft)

- `Consequence` additionally (derived, not historised as before):
  - `category` (`TOO_LATE`, `DELAYED`, `NO_IMPACT`)
  - `assessedDelay` (duration, empty = not passable), `delayAssumed` (yes/no)
  - `expectedArrival` (point in time, empty without planned arrival), `deadline` (copy of the deadline at the time of assessment)
  - `match` (`DIRECT`, `BY_SHIFT`, `MARGINAL`, column `match_kind`)
  - `passageAt` (shifted passage of the hit section, for ranking and the "tight" note)
  - `assessedAt`
- `Incident` additionally `assumedDelay` (assumption of the source without a number, historised, `V33`), `vehicleScope` (`ALL`, `HEAVY_ONLY`, `LIGHT_ONLY`, historised, `V35`) and `timesUnconfirmed` (historised, `V36`)
- `impact` (`BLOCKED`, `DELAYED`) describes the incident at the location, the category the consequence for the trip. It follows from the type of the incident (`Incident.impact()`, `Consequence.impact()`) and is no longer stored per consequence since `V42`, the API returns it unchanged. `delay` remains the value of the incident.
- Schema: separate migration `V35` (instead of with the time windows, which already came in `V34`).

## When the assessment runs

- Always the whole route, synchronously in the same transaction as the matching:
  - after `matchIncident` for every route with a new, changed or removed consequence
  - after `matchRoute` (`RouteChanged`)
  - **new:** arrival of the target stop changed → `trips` publishes `StopArrivalChanged(stopId, routeIds)`, `incidents` reassesses the routes to the stop. Today a change of the arrival does not trigger planning ([domain.md](domain.md#trips)), hence a separate event instead of `RouteChanged`.
- **Triggering proposals (implemented, runs switched off for now):** if a consequence changes to `TOO_LATE` during the assessment or is newly created with `TOO_LATE` and its passage is not over, `assessRoute` publishes `RouteTooLate(routeId, incidentIds, deadline)`. Duplicate runs, also after a replanning (where all consequences count as new), are prevented by `advisor`, see [advisor.md](advisor.md#automatic-only-too_late).
- Time passes: no reassessment, the passages are planned. Expired incidents drop out of the display as before.
- **Passage over (implemented 2026-11-05):** consequences drop out of the list, summary and counter (`TripIncidents`) as soon as `passageAt` + own delay is over (not passable: `max-wait`), they remain stored. As long as the waiting time is running, they stay visible. Limit: if all consequences of a route are over, the summary shows the planned arrival again, even if the route is still under way.

## Dependencies

- `incidents` reads deadline and planned arrival via `trips.logic` (like `TripIncidents` via `TripLogic`) and the sections via `RouteSectionRepository` (exists).
- `trips -.->|StopArrivalChanged| incidents` new, same direction as `RouteChanged`.
- `incidents -.->|RouteTooLate| advisor`: `incidents` does not know `advisor`.

## API and frontend

- `GET /trips/{id}/incidents`: per notice additionally `category`, `match`, `assessedDelayMinutes` (empty = not passable), `delayAssumed`, `passageAt`, `expectedArrival`, `deadline`, `windowStart`/`windowEnd`; sorted by the [ranking](#ranking).
- **Deviation (implemented):** the summary per route is in a separate endpoint `GET /trips/{id}/incidents/routes` (`routeId`, `worstCategory`, `plannedArrival`, `expectedArrival`, `deadline`, `slackMinutes` negative = too late, `unpassable`, `plannedTooLate`), so that `GET /trips/{id}/incidents` stays a list and the existing frontend keeps working unchanged. Only planned routes or routes with notices.
- "Check times" = `timesUnconfirmed` on `GET /trips/{id}/incidents`.
- Resolved notices (incident ended) follow at the end of the list with `resolved`/`resolvedAt` and count nowhere, see [domain.md](domain.md#incidents).
- Notices without their own fields: "arrival time missing" = `deadline` empty; "arrival not reachable according to planning" = `plannedTooLate`; "tight" = `match` `MARGINAL` with `windowStart` and `passageAt`.
- Frontend (**still open**): category as colour and text ("Too late: 1 h 10 min after arrival", "Delay 25 min, arrival 17:40 still reachable", "No impact"), estimated values with ✦. The generated client (`frontend/src/generated`) has not been regenerated yet; `frontend/openapi.json` has (from `mvn verify`).

## Example Terrace Tunnel (`566003`, closed northbound Sun–Thu 21:00–04:30)

| Drive | Deadline at destination | Assessment |
|---|---|---|
| Wed 4 Nov passage 22:00, planned arrival 23:30 | 23:45 | wait until 04:30, 6 h 30 min → arrival 06:00 → `TOO_LATE` |
| Wed 4 Nov passage 22:00, planned arrival 23:30 | Thu 5 Nov 10:00 | 6 h 30 min, arrival 06:00 → `DELAYED` |
| Wed 4 Nov passage 12:00 | – | no hit, no consequence |
| Wed 4 Nov passage 20:55, 2 h after departure | 23:00 | tight (±15 min), `MARGINAL`, `DELAYED` with note |
| Wed 4 Nov passage 20:40, traffic jam 30 min before (`DELAYED`) | 22:00 | jam `DELAYED`; shifted 21:10 within the window → tunnel `TOO_LATE` (`BY_SHIFT`) |

## Tests

- Pure assessment as a function (sections, incidents, planned arrival, deadline → categories), table of the examples above plus: tolerance grows with the time since departure, tight hit never `TOO_LATE`, `Caution` without a number → `NO_IMPACT`, without deadline, planned arrival already too late, closure without end, delay without value (`delayAssumed`), `HEAVY_ONLY`, two delays add up beyond the deadline.
- Integration like `ConsequenceMatchingTest`: change the arrival at the target stop → category changes `DELAYED` ↔ `TOO_LATE`.

## Configuration

```properties
nfv.incidents.assessment.no-impact-below=5m
nfv.incidents.assessment.max-wait=12h
nfv.incidents.assessment.min-tolerance=15m
nfv.incidents.assessment.tolerance-ratio=0.10
nfv.incidents.assessment.tolerance-max=2h
# Assumptions without a number come from the source, e.g. nfv.input.sources.nz-nzta.assumed-delay.*
```

## Implementation

- `incidents.logic.RouteAssessor` (pure function), `ConsequenceAssessment.assessRoute` (stores), `ConsequenceMatcher` (finds the routes), configuration `incidents.config.AssessmentProperties`.
- Deadline and planned arrival via `trips.logic.RouteSchedules` (`RouteSchedule`: departure = start of the first section, planned arrival = end of the last, deadline = `arrivalAt` of the target stop). `TripLogic.updateStop` publishes `StopArrivalChanged` when the arrival changes.
- **Interpretations (decided during implementation):**
  - Tolerance relative to the shifted passage; `BY_SHIFT` if the shifted passage hits without tolerance and the planned one does not; `MARGINAL` if only with tolerance.
  - An incident is assessed once per route, at the first section it hits.
  - `TOO_LATE` only with an own delay from `no-impact-below` (a delay below 5 min stays `NO_IMPACT`, even if it narrowly misses the deadline).
  - Planned arrival already after the deadline: delays `DELAYED`/`NO_IMPACT` according to their own size, only "not passable" is `TOO_LATE`.
  - After "not passable" all later consequences with their own delay are `TOO_LATE`, their `expectedArrival` is empty.
  - Waiting time after the end of a window (hit only through tolerance afterwards): 0.
  - `matchIncident` searches routes in the hull extended by `max-wait + tolerance-max` before and `tolerance-max` after; a shift by more than `max-wait` into a new incident is only found by the next assessment of the route.
  - `HEAVY_ONLY` comes from the enrichment (`EnrichmentModel.Answer.vehicleScope`) or from the test source.
- Tests: `RouteAssessorTest` (example table and all special cases above, `timesUnconfirmed`), `ConsequenceMatchingTest` (change arrival → `DELAYED` ↔ `TOO_LATE`, `NO_IMPACT` listed, not counted, summary, passage over, running waiting time, ended before the start), `TripApiTest` (new fields, endpoint per route).

## Open

- Frontend (category, delay, expected arrival, ✦), see [API and frontend](#api-and-frontend).
- Calibrate tolerance and source assumptions on real data.
- Deadline from the booking (latest check-in `checkInUntil` of the accommodation) instead of or in addition to `Stop.arrivalAt`.
- Consequences beyond the stop: a delay at the target stop does not shift the departure to the next route (departure is a user input).
- Notification per category (e.g. immediately for `TOO_LATE`, batched for `DELAYED`, never for `NO_IMPACT`).
