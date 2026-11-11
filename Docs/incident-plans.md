# Pläne bei Incidents (Analyse)

- **Status:** Analyse 2026-11-11, nichts umgesetzt. Entscheidungen offen, siehe [unten](#entscheidungen-für-philipp).
- **Ziel (Philipp, 2026-11-11):** Der Agent schlägt bei einem Incident nicht nur eine andere Route vor, sondern **ganze Pläne**. Ein Plan bündelt mehrere Aktionen (Umfahrung, Route teilen, Übernachtung unterwegs, Ziel-Unterkunft neu buchen). Priorität: rechtzeitig ankommen. Ist das Ziel nicht rechtzeitig oder gar nicht erreichbar, sucht er Zwischenstopps. Der Nutzer wählt einen Plan und führt alle Buchungen per Knopfklick aus.
- Baut auf [advisor.md](advisor.md), [recommendations.md](recommendations.md), [accommodation-alternatives.md](accommodation-alternatives.md) und dem Entwurf [incident-rebooking.md](incident-rebooking.md) (`RescuePlan`) auf.

## Beispiel

- Route Kaikōura → Wānaka, Sperrung bis auf Weiteres auf der geplanten Strecke, Unterkunft in Wānaka gebucht (z. B. 2 Nächte ab heute).
- Gewünschter Plan:
  1. Heute bis zu einem Ort vor der Sperrung bzw. auf einer Umfahrung fahren (Route teilen).
  2. Dort ein Hotel buchen.
  3. Morgen auf einer Strecke weiterfahren, die dann frei ist.
  4. Unterkunft in Wānaka: erste Nacht absagen bzw. neu buchen ab morgen, die alte auf die To-do-Liste.
  5. Ein Knopf: alles buchen, Reise anpassen.

## Was es schon gibt

| Baustein | Stand | Wo |
|---|---|---|
| Bewertung `TOO_LATE`/`DELAYED`/nicht passierbar, Frist = `Stop.arrivalAt` des Ziels | umgesetzt | `incidents`, [consequence-assessment.md](consequence-assessment.md) |
| Umfahrung, Abfahrt verschieben, so weiterfahren | umgesetzt, Tools `find_alternative_routes`, `find_departure_shifts`, `keep_route` | `recommendations.Suggestions`, `advisor` |
| Orte vor dem Hindernis, auch auf einer Umfahrung, erreichbar vor der Abendgrenze (22:00) | umgesetzt, Tool `find_overnight_areas` | `recommendations.Stopovers` |
| Hotels um einen Ort oder den Ziel-Stop, Zeitraum frei wählbar | umgesetzt, Tool `search_accommodations` → `STOPOVER`/`TARGET_STAY` | `advisor.AccommodationSearchTool`, `bookings.AlternativeSearch.offers` |
| Läufe und Vorschläge gespeichert | umgesetzt, `advisor_run`, `advisor_proposal` (`V44`, `V46`, `V47`) | `advisor` |
| `STOPOVER` übernehmen: Stop anlegen, Route teilen, beide neu planen, Ankunft am Ziel um die Nächte verschieben, To-do `NOTIFY_LATE_ARRIVAL` | umgesetzt | `ProposalDecisions.stopover` |
| `TARGET_STAY` buchen als Ersatz (`replacesBookingId`), alte Buchung → To-do `CANCEL`/`REPLACED` | umgesetzt | `AlternativeReservations`, `BookingTodos` |
| Zahlung: PayPal autorisieren → LiteAPI buchen → Capture, Hintergrund-Job, Storno mit Erstattung | umgesetzt (`V43`) | `bookings`, `paypal` |
| Ersatzunterkunft > 10 km entfernt zieht den Stop mit, Routen neu geplant | umgesetzt | `TripEditing.moveStopToStay` |

Alle Einzelteile des Beispiels gibt es also als **einzelne** Aktion. Es fehlt, sie zu einem Plan zu verbinden und den Plan als Ganzes zu prüfen und auszuführen.

## Warum der Agent heute keinen Plan erstellen kann

1. **Ein Vorschlag ist genau eine Aktion.** `AdvisorProposal.kind` ist eines von `REROUTE`, `SHIFT_DEPARTURE`, `KEEP_ROUTE`, `STOPOVER`, `TARGET_STAY`. `submit_proposals` nimmt eine Liste von `option_id`, jede steht für eine Aktion. Eine Kombination (Übernachtung **und** Ziel neu buchen **und** Umfahrung am zweiten Tag) lässt sich weder ausdrücken noch speichern.
2. **Nacheinander übernehmen geht auch nicht.** Übernimmt der Nutzer einen `STOPOVER`, wird die alte Route gelöscht und mit ihr der Lauf (`ON DELETE CASCADE`). Ein `TARGET_STAY` aus demselben Lauf ist danach weg.
3. **Die Weiterfahrt wird nicht geprüft.** `find_overnight_areas` findet Orte vor dem Hindernis. Ob die Strecke vom Ort zum Ziel am nächsten Morgen frei oder umfahrbar ist, rechnet niemand (`evaluate_stopover` ist entfallen, advisor.md). Bei einer Sperrung bis auf Weiteres verschiebt der `STOPOVER` das Problem nur auf morgen.
   - Nach dem Übernehmen plant `trips` Ort → Ziel neu über den Bestweg. Die Umfahrung der Option wird nicht übernommen, also läuft die neue Route wieder in die Sperrung und erzeugt eine neue Consequence.
   - Die Weiterfahrt ist fest 09:00 (`ProposalDecisions.STOPOVER_DEPARTURE`), nicht abgeleitet aus dem Ende der Sperrung.
4. **Ziel-Unterkunft und Übernachtung hängen nicht zusammen.** `search_accommodations` am Ziel (`area_id = null`) sucht standardmäßig im gebuchten Zeitraum. Das Modell kann `check_in` auf morgen setzen, das Ergebnis ist aber ein eigenständiger `TARGET_STAY` auf der geplanten Route, ohne Bezug zur Übernachtung. Eine Regel, wann die alte Buchung bleibt (nur informieren) und wann neu gebucht wird, gibt es nicht.
5. **Nur eine Route im Blick.** Der Lauf gilt einer Route. Folgende Stops stehen nur als Text im Kontext (`following_stops`). `ProposalDecisions.stopover` verschiebt die Ankunft am Ziel nur, wenn sie danach noch vor dessen Abfahrt liegt. Bei einer Nacht in Wānaka passiert nichts: Ankunft bleibt, Folgeroute wird nicht geprüft.
6. **„Rechtzeitig ankommen“ ist nur ein Wort im Prompt.** `goal = ARRIVE_ON_TIME` steht in der Lage, eine Rangfolge der Lösungen gibt es nicht. `meets_deadline` bleibt bei `STOPOVER` leer. Frist ist `Stop.arrivalAt`, nicht der späteste Check-in (`checkInUntil`, offen in consequence-assessment.md). `draft_late_arrival_notice` fehlt (nur Interface `LateArrivalNotices`).
7. **Zu wenig Runden.** `max-turns = 8`, ein Tool-Aufruf je Runde (`parallel_tool_calls = false`). Ein Plan wie im Beispiel braucht etwa `find_alternative_routes`, `find_overnight_areas`, `search_accommodations` (Ort), Weiterfahrt prüfen, `search_accommodations` (Ziel, neuer Zeitraum), `submit_proposals`, also 6 Runden. Für zwei oder drei Pläne reicht das nicht. Jede Hotelsuche zählt zudem gegen `SearchLimiter` des Nutzers.
8. **Ausführen ist kein Knopfklick.** Zwei Hotels sind heute zwei Reservierungen mit zwei PayPal-Freigaben. Scheitert das zweite Hotel, ist das erste schon gebucht, eine Rücknahme über den Plan gibt es nicht. `STOPOVER` ändert die Reise schon vor der Zahlung.
9. **Ohne Modell** (`withoutModel`) gibt es nur Routen und Abfahrten, nie Unterkünfte, also nie einen Plan.
10. **Kleinigkeit im Prompt:** In `AlternativeAgent.INSTRUCTIONS` fehlen in der `stay_`-Zeile die `\`-Zeilenfortsetzungen, der Text enthält deshalb Zeilenumbrüche mit vielen Leerzeichen. Harmlos, beim Umbau mitbeheben.

## Was es bräuchte

### Grundsatz

- **Backend baut und prüft Pläne, das Modell wählt und begründet.** Passt zu „Strecken, Zeiten und Konflikte berechnet das Backend“ ([advisor.md](advisor.md)) und zu „KI unsichtbar“. Das Modell setzt keine Zeitleiste über zwei Tage selbst zusammen.
- **Ohne Modell** liefert die Leiter unten trotzdem einen Plan, nur ohne Begründung.

### Entitäten

- **Plan statt einzelnem Vorschlag.** Kleinste Änderung: `AdvisorProposal` bleibt der Plan (Rang, Begründung, Ergebnis), neue Tabelle `advisor_proposal_step` (Migration `V50`) mit Reihenfolge und Art je Schritt:
  - `ROUTE` (Waypoints einer Umfahrung oder neue Abfahrt) für ein Teilstück
  - `ADD_STOP` (Übernachtung unterwegs: Ort, Ankunft, Abfahrt)
  - `BOOK_STAY` (Angebot an einem Stop, heute `ProposalStay`)
  - `REPLACE_STAY` (Ersatz am Ziel mit neuem Zeitraum, `replacesBookingId`)
  - `NOTIFY_STAY` (alte Buchung bleibt, Unterkunft informieren, To-do `NOTIFY_LATE_ARRIVAL`)
  - `SHIFT_STOP` (Ankunft/Abfahrt eines folgenden Stops verschieben)
- **Ergebnis des Plans vom Backend** (am Plan gespeichert): Ankunft am Ziel, Verspätung gegen die Frist, zusätzliche Nächte, Mehrkosten gesamt, `reaches_target`, `meets_deadline`, betroffene Folgestops.
- **Ausführung:** Status je Schritt (`PENDING`, `DONE`, `FAILED`), damit ein Klick im Hintergrund weiterlaufen kann wie `ReservationRecovery`. Reservierungen einer Ausführung zusammengefasst (z. B. `booking_reservation.plan_execution_id`).
- `kind` am Plan bleibt als Zusammenfassung für die Anzeige (z. B. `REROUTE`, `STOPOVER_PLAN`).

### Neue Berechnung (deterministisch, `recommendations`)

- **Zweitagesprüfung:** Teilstück 1 ab geplanter Abfahrt bis zum Ort, Übernachtung, Teilstück 2 ab Weiterfahrt mit den Incidents, die **dann** gelten (`applies_until`), inklusive Umfahrung, falls die Sperrung noch besteht. Nutzt `CandidateEvaluator` mit anderer Abfahrt und dem Ort als Start.
- **Weiterfahrt-Zeit:** frühestens Ende der Sperrung, sonst Standard (heute 09:00), aber nicht so spät, dass die Frist am Ziel reißt.
- **Folgestops:** Verschiebt der Plan die Ankunft hinter die Abfahrt des Ziels, wird die Folgeroute mitgeprüft oder der Plan als „ändert weitere Tage“ markiert.
- **Tool für das Modell** (`compose_plan` o. ä.): bekommt Bausteine aus dem Lauf (`area_<n>`/`stay_<n>`, `route_<n>` für Teilstück 2, Ziel-Hotel oder „Buchung behalten“), liefert `plan_<n>` mit dem Ergebnis oben. `submit_proposals` nennt `plan_<n>`. Alternativ baut das Backend die Kandidaten selbst und das Modell rankt nur (spart Runden, siehe Entscheidung 1).

### Heuristiken und Regeln

**Leiter, in dieser Reihenfolge** (erste Stufe, die rechtzeitig ankommt, wird empfohlen, die übrigen als Alternativen):

1. Rechtzeitig, ohne Kosten: Umfahrung oder andere Abfahrt mit `meets_deadline`.
2. Verspätet, aber innerhalb des Check-in-Fensters: so weiterfahren und die Unterkunft informieren (braucht `checkInUntil` als Frist und `draft_late_arrival_notice`).
3. Ziel heute erreichbar, aber nach dem Check-in: andere Unterkunft am Ziel mit später Anreise (`TARGET_STAY`).
4. Ziel heute nicht erreichbar, morgen schon: Übernachtung unterwegs + geprüfte Weiterfahrt + Entscheidung zur Ziel-Unterkunft (Regel unten).
5. Ziel auch morgen nicht erreichbar (Sperrung bis auf Weiteres, keine Umfahrung): Ziel ersetzen, also Stop in einen anderen erreichbaren Ort verlegen und dort buchen. Neue Fähigkeit, Entscheidung 4.

**Regeln, die festgelegt werden müssen:**

- **Fahrtag:** höchstens Ankunft 22:00 (gibt es, `stopover.latest-arrival`), dazu eine Grenze für die Fahrzeit je Tag (Vorschlag 8 h) und keine Abfahrt vor 07:00.
- **Ort für die Übernachtung:** möglichst nah am Hindernis (wenig Restweg), `city`/`town` vor `village` (gibt es), dazu: nur Orte, von denen die Weiterfahrt geprüft ist.
- **Ziel-Unterkunft:**
  - Verliert der Nutzer nur die erste Nacht und bleiben ≥ 1 Nächte: Buchung behalten, informieren (To-do `NOTIFY_LATE_ARRIVAL`).
  - Verliert er alle Nächte: Ersatz am Ziel mit neuem Zeitraum oder Ziel ersetzen, alte Buchung → To-do `CANCEL`. Nie automatisch stornieren (Philipp, 2026-11-07).
  - In der App gebuchte Unterkünfte mit kostenloser Stornierung: Storno mit Erstattung anbieten (gibt es), aber nur als eigener Schritt mit Bestätigung.
- **Kosten:** Mehrkosten ohne Nachfrage bis zu einer Grenze (offen in incident-rebooking.md, Vorschlag bisheriger Preis + 25 %), teurere Pläne nur als Alternative.
- **Vielfalt:** 1 empfohlener Plan + höchstens 2 andere unterschiedlicher Art, nicht drei Varianten derselben Umfahrung.
- **Gleichstand:** kostenlos vor kostenpflichtig, dann frühere Ankunft, dann weniger Änderungen an der Reise.

### Ausführung per Knopfklick

- **Erst zahlen und buchen, dann die Reise ändern.** Heute legt `STOPOVER` den Stop vor der Zahlung an. Für einen Plan besser: alle Hotels reservieren, dann Trip-Änderungen in einer Transaktion. Scheitert eine Buchung, bleibt die Reise unverändert.
- **Eine PayPal-Freigabe für den ganzen Plan:** eine Order mit einer `purchase_unit` je Hotel, gesamt autorisieren, je Hotel bei LiteAPI buchen und capturen, bei einem Fehlschlag die übrigen Autorisierungen freigeben und bereits gebuchte stornieren (Erstattung gibt es). Einfachere Variante: Freigaben nacheinander, Plan bleibt dann zwischen den Schritten halb ausgeführt.
- **Fortschritt** im Sheet je Schritt („Hotel Geraldine bestätigt“, „Wānaka umgebucht“, „Route angepasst“), passend zu incident-rebooking.md.

## Entscheidungen für Philipp

1. **Wer baut die Pläne?** Backend nach der Leiter, Modell wählt und begründet (Empfehlung: robust, weniger Runden, funktioniert ohne Modell) oder Modell baut aus Bausteinen per `compose_plan` (flexibler, braucht mehr Runden und höheres `max-turns`).
2. **PayPal:** eine Freigabe für den ganzen Plan (Empfehlung) oder eine je Hotel.
3. **Ziel-Unterkunft:** Regel „erste Nacht verloren → behalten und informieren, alle verloren → neu buchen“ so übernehmen?
4. **Ziel gar nicht erreichbar:** darf ein Plan den Ziel-Stop in einen anderen Ort verlegen?
5. **Frist:** `checkInUntil` der Unterkunft als Frist mit aufnehmen (nötig für Stufe 2 und 3)?
