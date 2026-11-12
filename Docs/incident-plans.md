# Plans for incidents

- **Status:** target design decided 2026-11-11 (Philipp), implemented 2026-11-11 (`V50`, tools, execution, frontend). Tested with mocks for the model, LiteAPI and PayPal, not yet verified against OpenAI, the LiteAPI and PayPal sandboxes.
- **Goal:** for an incident the agent proposes complete plans, not just other routes. A plan bundles several steps (detour, splitting the route, overnight stay on the way, keeping or replacing the target accommodation, replacement destination). The user picks a plan and executes it with one click, including all bookings.
- **Priority:** arrive on time. If that is not possible, reach the destination later. If that is not possible either, a replacement destination.
- Builds on [advisor.md](advisor.md), [recommendations.md](recommendations.md), [accommodation-alternatives.md](accommodation-alternatives.md).

## Example

- Kaikōura → Wānaka closed until further notice, accommodation booked in Wānaka.
- Plan: today as far as Geraldine, hotel there, tomorrow via the detour to Wānaka, the accommodation in Wānaka learns of the later arrival (booking stays). One button: "Accept and pay 128 NZD".

## Roles

- **The model builds plans** from building blocks via the tool `compose_plan`, compares and justifies them.
- **The backend calculates and checks** every plan: timeline over all days, onward journey the next morning around closures, arrival, deadline, cost, effect on later stops. The model does not invent numbers.
- **The user confirms.** No tool changes the trip or books.

## Building blocks and tools

- **Building blocks** from the tools of the run: `route_<n>`, `departure_<n>`, `keep_route`, `area_<n>`, `stay_<n>`, plus `place_<n>` (replacement destination).
- **`find_other_targets`** (`OtherTargetsTool`, logic `recommendations.Destinations`): places within 80 km of the destination (at least 5 km away), sorted by distance to the destination + half the distance to the next stop, village + 20 km. Up to 6 candidates are routed (`Drives`), reachable ones become `place_<n>` with the drive there. Optional `latest_arrival`.
- **`search_accommodations`** also accepts `place_<n>`: check-in on the arrival day, check-out like the booked target accommodation, offers are `only_in_plan` (usable only within a plan, `replacesBookingId` = booked target accommodation). Currency is always that of the booked target accommodation (one PayPal order per plan).
- **`compose_plan`** (`PlanTool`) with `drive` (`keep_route`, `route_`, `departure_`), `overnight` (`stay_` in an `area_`, must lie on the same way) and `target` (`keep_booking`/null, `stay_` at the destination, `stay_` at a `place_`). Response: steps, arrival, deadline, `meets_deadline`, delay, additional nights, extra cost, `later_stops_move`, `problems`, `warnings`.
  - Only a plan without `problems` gets a `plan_id`. Problems: `TARGET_NOT_REACHED`, `NO_ROUTE`, `CONTINUATION_BLOCKED`, `BOOKED_STAY_LOST`, `STAY_DATES_MISMATCH`, `CURRENCIES_DIFFER`, `NO_LOCATION`. Warning: `LATE_FOR_CHECK_IN`.
  - Replacement destination only without `drive` and `overnight` (the drive comes with the `place_`). Overnight stay plus replacement destination does not exist yet.
- **`submit_proposals`** names `plan_<n>` or, as before, individual options (a detour does not need a plan).
- **More turns:** `max-turns` 16, `parallel-tool-calls` on (`nfv.advisor.*`), `plans-enabled` switches off `compose_plan` and `find_other_targets`.

## Rules

- **Ladder** (instruction to the model):
  1. on time and free of charge (detour, other departure)
  2. late, but the accommodation stays: drive on, inform the accommodation
  3. destination reachable today, but accommodation lost: other accommodation at the destination
  4. destination not reachable today: overnight stay on the way + checked onward journey
  5. destination not reachable: replacement destination
- **Deadline** is the latest check-in of the booked accommodation (`checkInUntil`) on the check-in day, before 06:00 the night after; without it `Stop.arrivalAt`. With a new accommodation at the destination or a replacement destination there is no deadline.
- **Onward journey** (`recommendations.Drives`): from the hotel at 09:00 on the check-out day, fastest route without closure, up to 3 rounds around found closures. If that fails and all closures of the direct route end within 10 h, departure 15 min after the end (`waitedFor`). Otherwise `CONTINUATION_BLOCKED`.
- **Target accommodation:** arrival before check-out: the booking stays. Arrival on a later day than check-in or after the deadline: step `KEEP_BOOKING` (on acceptance to-do `NOTIFY_LATE_ARRIVAL`, only if the day changes). Arrival on the check-out day or later: `BOOKED_STAY_LOST`, the model must take a new accommodation at the destination. The replaced one goes on the to-do list after booking (`CANCEL`). Never cancel automatically.
- **Replacement destination:** a place that comes as close as possible to the planned holiday experience and still allows the rest of the trip later if possible. The plan moves the target stop there (name = place, position = hotel). If the user has other ideas, they replan themselves.
- **Cost:** free before paid, then earlier arrival, then fewer changes. Extra cost above previous price + 25 % only as an alternative. 1 recommended plan, at most 2 more of a different kind.

## Data model (`V50`)

- `AdvisorProposal` with `kind = PLAN` is the plan, plus `PlanResult` (embedded: `plan_deadline`, `reaches_target`, `extra_nights`, `extra_cost`, `cost_currency`, `affects_following`, `new_target_name`).
- `advisor_proposal_step` (`AdvisorProposalStep`): position, kind (`DRIVE`, `OVERNIGHT`, `CONTINUE`, `KEEP_BOOKING`, `REPLACE_STAY`, `NEW_TARGET`), kind of drive, times, `waited_for`, place, `booking_id`, `arrival_on`, points (`advisor_proposal_step_point`), offer (`stay_*` as in `V46`).
- `plan_payment` (`bookings.PlanPayment`): one PayPal order for all hotels of a plan, status `PREBOOKED` → `PAYMENT_PENDING` → `SECURED` → `SETTLED` or `FAILED`. The hotels are `booking_reservation` rows with `plan_payment_id` and `plan_key` (`overnight`, `target`).
- `advisor_plan_execution`: an accepted plan with a copy of its steps as JSON (the run disappears when an overnight stay splits the route), status `AWAITING_PAYMENT`, `APPLYING`, `APPLIED`, `APPLIED_PARTLY`, `FAILED`, result as JSON. Route and run only as id without foreign key.

## One-click execution

- **Without hotels** (`POST …/proposals/{rank}/acceptance`): apply steps immediately.
- **With hotels** (`POST …/proposals/{rank}/plan-checkout` with guest data): prebook all hotels, one order with one `purchase_unit` per hotel (`reference_id` = reservation). Then the PayPal button, then `POST /plan-executions/{id}/completion`:
  1. authorise all units, check the amount per hotel,
  2. book each hotel at LiteAPI. If one fails, the ones already booked are cancelled at LiteAPI, all authorisations released, nothing paid, the trip stays unchanged,
  3. event `PlanPaymentSecured` → `PlanExecutions` changes the trip (detour/departure; overnight stay: stop before the destination, onward journey with the checked points, arrival at the destination moved; replacement destination: target stop moved, drive there; to-dos),
  4. capture and create bookings at the stops of the plan (overnight stay at the new stop).
- **If step 3 fails**, the hotels are still paid and booked at the target stop, status `APPLIED_PARTLY`, the user replans themselves.
- **Hotel gone or dearer** (`409 OFFER_UNAVAILABLE` or `PRICE_EXCEEDED` at checkout or completion, nothing paid): the frontend does not ask the user to search again by hand. `PlanBookingSheet` loads the run (`PlanExecutionDto.runId`, also after the redirect fallback), goes back to the solutions of its incident (`alternativen/:routeId/:incidentId`) and `ProposalsPanel` starts a new run right away with a note why (Philipp, 2026-11-12).
- **Background:** `ReservationRecovery` continues plan payments (approved, but nobody came back → steps 1–4; `SECURED` → event again, a plan already applied is only paid).
- **Frontend:** plan card with steps in `ProposalsPanel`, sheet `PlanBookingSheet` (`/reisen/:id/plan`, return of the redirect `/reisen/:id/plan/:executionId?planzahlung=…`).

## Limits

- The first leg of an overnight stay is replanned, points of a detour before the hotel are not taken over.
- Without the model (`COMPLETED_WITHOUT_MODEL`) there are no plans, only individual routes.
- A crash in the middle of step 3 leaves the execution in `APPLYING`, it is not continued automatically.
- PayPal errors (`PAYPAL_NOT_CONFIGURED`, unreachable) for a plan arrive without their own code, because `advisor` must not know `paypal`.

## Next stage

- **Feedback on the plan:** the user says what does not fit ("rather stay on the coast"), the agent revises the plan with this constraint.
- Overnight stay plus replacement destination, message to the accommodation (`draft_late_arrival_notice`), plans without the model from the ladder.
