# Pläne bei Incidents

- **Status:** Zielbild beschlossen 2026-11-11 (Philipp), umgesetzt 2026-11-11 (`V50`, Tools, Ausführung, Frontend). Getestet mit Mocks für Modell, LiteAPI und PayPal, noch nicht gegen OpenAI, LiteAPI- und PayPal-Sandbox geprüft.
- **Ziel:** Bei einem Incident schlägt der Agent ganze Pläne vor, nicht nur andere Routen. Ein Plan bündelt mehrere Schritte (Umfahrung, Route teilen, Übernachtung unterwegs, Ziel-Unterkunft behalten oder ersetzen, Ersatzziel). Der Nutzer wählt einen Plan und führt ihn mit einem Klick aus, einschließlich aller Buchungen.
- **Priorität:** rechtzeitig ankommen. Geht das nicht, das Ziel später erreichen. Geht auch das nicht, ein Ersatzziel.
- Baut auf [advisor.md](advisor.md), [recommendations.md](recommendations.md), [accommodation-alternatives.md](accommodation-alternatives.md) auf.

## Beispiel

- Kaikōura → Wānaka bis auf Weiteres gesperrt, Unterkunft in Wānaka gebucht.
- Plan: heute bis Geraldine, dort Hotel, morgen über die Umfahrung nach Wānaka, Unterkunft in Wānaka erfährt die spätere Ankunft (Buchung bleibt). Ein Knopf: „Übernehmen und 128 NZD zahlen“.

## Rollen

- **Modell baut Pläne** aus Bausteinen über das Tool `compose_plan`, vergleicht und begründet.
- **Backend rechnet und prüft** jeden Plan: Zeitleiste über alle Tage, Weiterfahrt am nächsten Morgen um Sperrungen herum, Ankunft, Frist, Kosten, Wirkung auf Folgestops. Das Modell erfindet keine Zahlen.
- **Nutzer bestätigt.** Kein Tool ändert die Reise oder bucht.

## Bausteine und Tools

- **Bausteine** aus den Tools des Laufs: `route_<n>`, `departure_<n>`, `keep_route`, `area_<n>`, `stay_<n>`, dazu `place_<n>` (Ersatzziel).
- **`find_other_targets`** (`OtherTargetsTool`, Logik `recommendations.Destinations`): Orte im Umkreis von 80 km um das Ziel (mindestens 5 km entfernt), sortiert nach Abstand zum Ziel + halbem Abstand zum nächsten Stop, Dorf + 20 km. Bis zu 6 Kandidaten werden geroutet (`Drives`), erreichbare werden `place_<n>` mit Fahrt dorthin. Optional `latest_arrival`.
- **`search_accommodations`** nimmt auch `place_<n>`: Check-in am Ankunftstag, Check-out wie die gebuchte Ziel-Unterkunft, Angebote sind `only_in_plan` (nur in einem Plan nutzbar, `replacesBookingId` = gebuchte Ziel-Unterkunft). Währung immer die der gebuchten Ziel-Unterkunft (eine PayPal-Order je Plan).
- **`compose_plan`** (`PlanTool`) mit `drive` (`keep_route`, `route_`, `departure_`), `overnight` (`stay_` an einem `area_`, muss am selben Weg liegen) und `target` (`keep_booking`/null, `stay_` am Ziel, `stay_` an einem `place_`). Antwort: Schritte, Ankunft, Frist, `meets_deadline`, Verspätung, zusätzliche Nächte, Mehrkosten, `later_stops_move`, `problems`, `warnings`.
  - Nur ein Plan ohne `problems` bekommt eine `plan_id`. Probleme: `TARGET_NOT_REACHED`, `NO_ROUTE`, `CONTINUATION_BLOCKED`, `BOOKED_STAY_LOST`, `STAY_DATES_MISMATCH`, `CURRENCIES_DIFFER`, `NO_LOCATION`. Warnung: `LATE_FOR_CHECK_IN`.
  - Ersatzziel nur ohne `drive` und `overnight` (die Fahrt kommt mit dem `place_`). Übernachtung plus Ersatzziel gibt es noch nicht.
- **`submit_proposals`** nennt `plan_<n>` oder wie bisher einzelne Optionen (eine Umfahrung braucht keinen Plan).
- **Mehr Runden:** `max-turns` 16, `parallel-tool-calls` an (`nfv.advisor.*`), `plans-enabled` schaltet `compose_plan` und `find_other_targets` ab.

## Regeln

- **Leiter** (Anweisung an das Modell):
  1. rechtzeitig und kostenlos (Umfahrung, andere Abfahrt)
  2. verspätet, aber Unterkunft bleibt: weiterfahren, Unterkunft informieren
  3. Ziel heute erreichbar, aber Unterkunft verloren: andere Unterkunft am Ziel
  4. Ziel heute nicht erreichbar: Übernachtung unterwegs + geprüfte Weiterfahrt
  5. Ziel nicht erreichbar: Ersatzziel
- **Frist** ist der späteste Check-in der gebuchten Unterkunft (`checkInUntil`) am Tag des Check-ins, vor 06:00 die Nacht danach; ohne ihn `Stop.arrivalAt`. Mit neuer Unterkunft am Ziel oder Ersatzziel gibt es keine Frist.
- **Weiterfahrt** (`recommendations.Drives`): ab Hotel um 09:00 am Check-out-Tag, schnellste Strecke ohne Sperrung, bis zu 3 Runden um gefundene Sperrungen herum. Geht das nicht und enden alle Sperrungen der direkten Strecke innerhalb von 10 h, Abfahrt 15 min nach dem Ende (`waitedFor`). Sonst `CONTINUATION_BLOCKED`.
- **Ziel-Unterkunft:** Ankunft vor dem Check-out: Buchung bleibt. Ankunft an einem späteren Tag als der Check-in oder nach der Frist: Schritt `KEEP_BOOKING` (beim Übernehmen To-do `NOTIFY_LATE_ARRIVAL`, nur wenn der Tag sich ändert). Ankunft am Check-out-Tag oder später: `BOOKED_STAY_LOST`, das Modell muss eine neue Unterkunft am Ziel nehmen. Die ersetzte kommt nach der Buchung auf die To-do-Liste (`CANCEL`). Nie automatisch stornieren.
- **Ersatzziel:** Ein Ort, der dem geplanten Urlaubserlebnis möglichst nahekommt und die restliche Reise später möglichst noch zulässt. Der Plan verlegt den Ziel-Stop dorthin (Name = Ort, Position = Hotel). Hat der Nutzer andere Vorstellungen, plant er selbst um.
- **Kosten:** kostenlos vor kostenpflichtig, dann frühere Ankunft, dann weniger Änderungen. Mehrkosten über bisheriger Preis + 25 % nur als Alternative. 1 empfohlener Plan, höchstens 2 weitere anderer Art.

## Datenmodell (`V50`)

- `AdvisorProposal` mit `kind = PLAN` ist der Plan, dazu `PlanResult` (eingebettet: `plan_deadline`, `reaches_target`, `extra_nights`, `extra_cost`, `cost_currency`, `affects_following`, `new_target_name`).
- `advisor_proposal_step` (`AdvisorProposalStep`): Position, Art (`DRIVE`, `OVERNIGHT`, `CONTINUE`, `KEEP_BOOKING`, `REPLACE_STAY`, `NEW_TARGET`), Art der Fahrt, Zeiten, `waited_for`, Ort, `booking_id`, `arrival_on`, Punkte (`advisor_proposal_step_point`), Angebot (`stay_*` wie `V46`).
- `plan_payment` (`bookings.PlanPayment`): eine PayPal-Order für alle Hotels eines Plans, Status `PREBOOKED` → `PAYMENT_PENDING` → `SECURED` → `SETTLED` oder `FAILED`. Die Hotels sind `booking_reservation`-Zeilen mit `plan_payment_id` und `plan_key` (`overnight`, `target`).
- `advisor_plan_execution`: ein übernommener Plan mit Kopie seiner Schritte als JSON (der Lauf verschwindet, wenn eine Übernachtung die Route teilt), Status `AWAITING_PAYMENT`, `APPLYING`, `APPLIED`, `APPLIED_PARTLY`, `FAILED`, Ergebnis als JSON. Route und Lauf nur als Id ohne Fremdschlüssel.

## Ausführen mit einem Klick

- **Ohne Hotels** (`POST …/proposals/{rank}/acceptance`): Schritte sofort anwenden.
- **Mit Hotels** (`POST …/proposals/{rank}/plan-checkout` mit Gastdaten): alle Hotels prebooken, eine Order mit einer `purchase_unit` je Hotel (`reference_id` = Reservierung). Danach PayPal-Button, dann `POST /plan-executions/{id}/completion`:
  1. alle Einheiten autorisieren, Betrag je Hotel prüfen,
  2. je Hotel bei LiteAPI buchen. Scheitert eins, werden die schon gebuchten bei LiteAPI storniert, alle Autorisierungen freigegeben, nichts gezahlt, die Reise bleibt unverändert,
  3. Ereignis `PlanPaymentSecured` → `PlanExecutions` ändert die Reise (Umfahrung/Abfahrt; Übernachtung: Stop vor dem Ziel, Weiterfahrt mit den geprüften Punkten, Ankunft am Ziel verschoben; Ersatzziel: Ziel-Stop verlegt, Fahrt dorthin; To-dos),
  4. capturen und Buchungen an den Stops des Plans anlegen (Übernachtung am neuen Stop).
- **Scheitert Schritt 3**, werden die Hotels trotzdem bezahlt und am Ziel-Stop gebucht, Status `APPLIED_PARTLY`, der Nutzer plant selbst um.
- **Hintergrund:** `ReservationRecovery` setzt Plan-Zahlungen fort (freigegeben, aber niemand kam zurück → Schritte 1–4; `SECURED` → Ereignis erneut, ein schon angewandter Plan wird nur noch bezahlt).
- **Frontend:** Plan-Karte mit Schritten in `ProposalsPanel`, Sheet `PlanBookingSheet` (`/reisen/:id/plan`, Rückkehr der Weiterleitung `/reisen/:id/plan/:executionId?planzahlung=…`).

## Grenzen

- Die erste Teilstrecke einer Übernachtung wird neu geplant, Punkte einer Umfahrung vor dem Hotel werden nicht übernommen.
- Ohne Modell (`COMPLETED_WITHOUT_MODEL`) gibt es keine Pläne, nur einzelne Routen.
- Ein Absturz mitten in Schritt 3 lässt die Ausführung in `APPLYING`, sie wird nicht automatisch fortgesetzt.
- PayPal-Fehler (`PAYPAL_NOT_CONFIGURED`, nicht erreichbar) beim Plan kommen ohne eigenen Code an, weil `advisor` `paypal` nicht kennen darf.

## Ausbaustufe

- **Feedback zum Plan:** Der Nutzer sagt, was nicht passt („lieber an der Küste bleiben“), der Agent überarbeitet den Plan mit dieser Vorgabe.
- Übernachtung plus Ersatzziel, Nachricht an die Unterkunft (`draft_late_arrival_notice`), Pläne ohne Modell aus der Leiter.
