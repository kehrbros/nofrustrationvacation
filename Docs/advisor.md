# Vorschläge durch den Agenten (`advisor`)

- **Status:** beschlossen 2026-11-06 (Lukas). Läufe auf Anfrage des Nutzers und automatische Läufe (`RouteTooLate`) umgesetzt (`V43`, Agent, Tools, API), getestet mit geskriptetem Modell, noch nie gegen die echte API. Automatische Läufe sind vorerst ausgeschaltet, siehe [Konfiguration](#konfiguration). Das Frontend fehlt noch.
- **Ziel:** Trifft ein Incident eine Route, schlägt ein Agent vor, was der Nutzer tun kann. Bei `TOO_LATE` automatisch, bei `DELAYED` nur auf Anfrage des Nutzers.
- **Grundsatz:** Das Modell wählt, vergleicht und begründet. Strecken, Zeiten und Konflikte berechnet das Backend. Das Modell ändert nichts an der Reise, das bestätigt immer der Nutzer.
- **Abgrenzung:** `recommendations` berechnet Strecken deterministisch und ohne Modell ([recommendations.md](recommendations.md)). `advisor` nutzt diese Berechnung, entscheidet, was vorgeschlagen wird, und speichert das Ergebnis.
- **Abweichung von der bisherigen Entscheidung:** Vorschläge zu Incidents waren flüchtig (Philipp, 2026-11-05). Ein automatischer Lauf hat aber niemanden, der auf das Ergebnis wartet, Benachrichtigungen gibt es noch nicht. Deshalb speichert `advisor` seine Läufe und Vorschläge (Lukas, 2026-11-06). `recommendations` selbst speichert weiter nichts.

## Ablauf

```mermaid
flowchart LR
    a[incidents<br>ConsequenceAssessment.assessRoute] -.->|RouteTooLate, nach dem Commit| l[RouteTooLateListener]
    u[Nutzer<br>POST alternative-proposals] --> c[AlternativeProposalController]
    l --> p[AlternativeAgent.propose]
    c --> p
    p --> t[ToolConversation<br>assistant]
    t --> m[(OpenAI)]
    t --> w[AdvisorTool]
    w --> s[recommendations<br>Suggestions.forConsequence]
    p --> d[(advisor_run<br>advisor_proposal)]
    d --> g[Nutzer wählt<br>PUT routes/id/choice]
```

1. **Auslösen:** automatisch über `RouteTooLate` oder durch den Nutzer über die [API](#api).
2. **Lauf anlegen:** `AdvisorRun` mit Status `PENDING` in eigener Transaktion, dann auf einem virtuellen Thread weiter.
3. **Agent:** `ToolConversation` führt das Gespräch mit dem Modell, ruft die Tools auf und endet mit `submit_proposals`. Kein Modellaufruf und kein Routing in einer Transaktion.
4. **Speichern:** Vorschläge mit den Werten aus dem Backend, Lauf `COMPLETED`.
5. **Anzeigen und wählen:** Das Frontend liest den Lauf. Gewählt wird über die bestehenden Endpunkte.

## Auslöser

### Automatisch: nur `TOO_LATE`

- `ConsequenceAssessment.assessRoute` ist die einzige Stelle, die Kategorien schreibt. Sie läuft in der Transaktion der Änderung und veröffentlicht dort `RouteTooLate(routeId, incidentIds, deadline)`, wenn eine Consequence der Route zu `TOO_LATE` wechselt oder neu mit `TOO_LATE` entsteht und ihre Durchfahrt nicht vorbei ist (`Consequence.isOver`). **Nur bei diesem Wechsel (Lukas, 2026-11-07):** Eine erneute Bewertung ohne Wechsel, z. B. mit anderer Frist oder Verzögerung bei weiterhin `TOO_LATE`, oder ein Wechsel weg von `TOO_LATE` startet nichts. `incidentIds` enthält alle `TOO_LATE`-Incidents der Route, auch schon passierte: Dass nur Zeit vergeht, ist keine neue Lage.
- `advisor` hört mit `@TransactionalEventListener(AFTER_COMMIT)` und rechnet auf einem virtuellen Thread, wie `TrafficEventProcessor.afterPoll`. Wird die Transaktion zurückgerollt, startet kein Lauf.
- **Warum der Wechsel allein nicht reicht:** Eine Neuplanung löscht die Consequences mit den Sections, eine weiterhin verspätete Route sähe neu aus und veröffentlicht das Event wieder. `NetworkRefreshed` könnte das für viele Routen zugleich auslösen. Deshalb bleibt `auto_key`.
- **Grenze:** Hat eine Neuplanung andere `TOO_LATE`-Incidents als zuvor (z. B. nur noch einen von zweien), ändert sich der Schlüssel und es startet ein Lauf, auch wenn kein einzelner Incident die Kategorie gewechselt hat. Die vorige Kategorie ist dann nicht mehr bekannt.
- **Keine doppelten Läufe:** `auto_key` = Hash aus den `TOO_LATE`-Incidents (sortiert) und der Frist, eindeutig je Route. Der Lauf wird zuerst angelegt, ein zweiter mit gleichem Schlüssel scheitert am Unique-Constraint und entfällt. Damit bleibt der Schutz über eine Neuplanung hinweg bestehen. Der übliche Fall (Lage schon bearbeitet) wird vorher abgefragt, damit er nicht jedes Mal als SQL-Fehler im Log steht.
- **Veraltetes Event:** `AlternativeProposals.startAutomatic` prüft die aktuelle Lage. Ist die Route nicht mehr `TOO_LATE` oder gelöscht, entfällt der Lauf. Er betrifft die schlimmste aktuelle `TOO_LATE`-Consequence, Ziel `ARRIVE_ON_TIME`.
- Scheitert ein Lauf (`FAILED`), wird `auto_key` geleert. Ein abgebrochener Lauf (`run-timeout`) gibt den Schlüssel ebenfalls frei: `startAutomatic` beendet ihn in eigener Transaktion, bevor es den neuen anlegt. **Kein automatischer neuer Versuch (Lukas, 2026-11-07):** Neu gestartet wird erst beim nächsten Wechsel zu `TOO_LATE` oder nach einer Neuplanung. Bis dahin bleibt nach `FAILED` (auch `INTERRUPTED` durch einen Neustart) nur der Lauf durch den Nutzer.
- **Nicht bearbeitet:** Ein `USER`-Lauf auf derselben Route hält keinen automatischen auf, beide laufen dann. Das kostet nur Modellaufrufe, der letzte Lauf gewinnt in der Anzeige.
- `DELAYED`, `NO_IMPACT` und Routen ohne Frist lösen nie automatisch aus. `MARGINAL` und `timesUnconfirmed` sind nie `TOO_LATE`, also auch nicht.

### Durch den Nutzer

- Für eine Route mit aktueller `TOO_LATE`- oder `DELAYED`-Consequence, z. B. bei verzögerten Segmenten.
- Läuft für die Route schon ein Lauf, kommt dessen Id zurück, es entsteht kein zweiter. Ein Lauf, der länger als `run-timeout` (15 min, etwas über dem ungünstigsten Fall von rund 12 min) in `PENDING` oder `RUNNING` steht, gilt als abgebrochen (`FAILED`) und blockiert keinen neuen (Lukas, 2026-11-07).

## Tools

- **Interface `AdvisorTool`** (`advisor.logic`), eine neue Bean ist ein neues Tool:

  ```java
  public interface AdvisorTool {
      String name();
      String description();
      JsonNode parameters();
      boolean appliesTo(RunContext run);
      JsonNode call(RunContext run, JsonNode arguments);
  }
  ```

- **Fest je Lauf:** Nutzer, Route und Consequence setzt das Backend (`RunContext`). Sie sind nie Parameter des Modells.
- **Kennungen:** Tools liefern eine `option_id`, die nur im Lauf gilt, die Daten dahinter hält der `RunContext` im Speicher. `submit_proposals` darf nur Kennungen aus diesem Lauf nennen, unbekannte und doppelte verwirft das Backend, wie `AlternativeRanking` fremde Hotel-Ids. Sind alle unbekannt, gilt der Lauf als ohne Modell.
- **Keine Tools zum Ändern:** Route übernehmen, Stops ändern, reservieren, zahlen und stornieren bleiben hinter der Bestätigung des Nutzers.

### Erste Stufe

| Tool | Parameter | Liefert | Nutzt |
|---|---|---|---|
| `find_alternative_routes` | `count` (1–5 oder `null`) | je Option `option_id`, `via`, Abfahrt, Ankunft, Strecke, Dauer, Verzögerung, `meets_deadline`, Konflikte, Anteil gemeinsamer Strecke, ohne Geometrie | `Suggestions.forConsequence` mit `ALTERNATIVES` |
| `find_departure_shifts` | keine, immer auf der geplanten Route | je Richtung höchstens eine Option, Felder wie oben | `Suggestions.forConsequence` mit `DEPARTURE_SHIFT` |
| `submit_proposals` | `summary`, `proposals[]` mit `option_id` und `reason`, beste zuerst | beendet den Lauf | `advisor` |

- Waypoints des Nutzers bleiben, alle aktiven Incidents der Route werden gesperrt bzw. verzögert. Das entscheidet das Szenario, nicht das Modell ([recommendations.md](recommendations.md#szenario-recommendationslogic)). Deshalb keine Parameter für Waypoints oder Segmente.
- `find_departure_shifts` entfällt, wenn der Nutzer schon unterwegs ist.
- **Keine andere Abfahrt für Routen-Optionen:** `find_alternative_routes` rechnet immer mit der geplanten Abfahrt. `choice` setzt entweder Waypoints oder eine Abfahrt, ein Vorschlag mit beidem ließe sich nicht wählen wie angezeigt.
- **`kind`:** `REROUTE`, `SHIFT_DEPARTURE`, `KEEP_ROUTE`, folgt aus der `option_id`. `keep_route` steht dem Modell immer zur Verfügung. `KEEP_ROUTE` ist kein Generator in `recommendations`: Strecke und Verzögerung aus `SuggestionResult.reference`, bei einer Sperrung Wartezeit und Ankunft aus der Consequence (die Referenz enthält das Warten nicht).
- **Grenze der ersten Stufe:** Gibt es bei einer Sperrung weder Umfahrung noch sinnvolle Abfahrt, kann der Lauf nur `KEEP_ROUTE` mit Verzögerung melden oder keine brauchbare Option. Eine Übernachtung unterwegs kommt erst mit den Unterkunfts-Tools.

### Später: Unterkünfte und Nachricht an die Unterkunft

Bis auf `find_overnight_areas` noch nicht umgesetzt, dort gibt es vorerst nur die Schnittstellen, ohne Implementierung und ohne Tool. Das Modell bekommt diese Tools also nicht angeboten, solange sie fehlen. `find_overnight_areas` ist fertig, aber ausgeschaltet, bis `search_accommodations` da ist (`stopover-enabled`, siehe [Konfiguration](#konfiguration)).

```java
/** Unterkünfte, wenn die Route nicht rechtzeitig fahrbar ist. Orte und Weiterfahrt: siehe Stopovers unten. */
public interface StayOptions {
    List<StayOffer> search(long appUserId, StaySearch search);

    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      @Nullable BigDecimal maxPrice) {}
    record StayOffer(String hotelId, String offerId, String name, double latitude, double longitude,
                     int distanceMeters, BigDecimal price, String currency, boolean refundable,
                     @Nullable OffsetDateTime freeCancellationUntil) {}
}

/** Entwurf einer Nachricht an die Unterkunft bei später Ankunft, senden muss der Nutzer. */
public interface LateArrivalNotices {
    Optional<LateArrivalNotice> draft(long appUserId, long stopId, OffsetDateTime expectedArrival);

    record LateArrivalNotice(long bookingId, String accommodationName, @Nullable String email,
                             @Nullable String phone, String message) {}
}
```

- Feldtypen beim Umsetzen gegen `bookings` prüfen. Die Implementierung liegt später als Adapter in `advisor`, damit `bookings` das Modul nicht kennt.
- **Tools:**
  - `find_overnight_areas(route_option_id?, latest_arrival?, max_results?)` (umgesetzt, ausgeschaltet): Orte vor dem Hindernis, auf der geplanten Route oder einer Umfahrung, die rechtzeitig erreichbar sind, siehe [unten](#find_overnight_areas). Nötig, weil `AlternativeSearch.criteriaFor` um die gebuchte Unterkunft sucht, also genau dort, wo der Nutzer nicht hinkommt.
  - `search_accommodations(area_id | TARGET_STOP, radius_meters, check_in, check_out, max_price?)`: braucht eine Variante von `AlternativeSearch` ohne `bookingId` und ohne `AlternativeRanking`, sonst ruft ein Tool des Modells wieder das Modell auf. Harte Prüfung und `SearchLimiter` bleiben. Jedes Angebot bekommt eine `offer_ref` im `RunContext`, mit Koordinaten des Hotels.
  - `evaluate_stopover(offer_ref, continue_at)`: Fahrt zum Hotel und Weiterfahrt am nächsten Morgen, siehe [unten](#evaluate_stopover).
  - `draft_late_arrival_notice`: oft die günstigste Lösung, wenn nur der Check-in zu spät ist. Kontaktdaten ergänzt das Backend, sie gehen nicht an das Modell.
- **Neue `kind`:** `STOPOVER`, `NOTIFY_ACCOMMODATION`, kommen mit den Tools (Spalte `VARCHAR`, keine Migration nötig). Ebenso das Ziel `STAY_OVERNIGHT` und die Buchungen im Kontext des Modells.
- Es gilt [accommodation-alternatives.md](accommodation-alternatives.md#rollen-assistant-und-liteapi): Das Modell löst keine Buchung aus.

#### Logik in `recommendations` (`Stopovers`)

- Orte und Weiterfahrt sind Routing ohne Modell, deshalb in `recommendations.logic` neben `Suggestions`. `recommendations` kennt `trips`, `incidents` und `network` schon, `advisor` braucht so keine neue Abhängigkeit.
- `advisor` ruft beides als Tools auf, wie `forConsequence`. Kein Aufruf in einer Transaktion.
- Orte sind umgesetzt, `evaluate` ist Entwurf (2026-11-07).

```java
/** Übernachtung unterwegs, wenn die Route nicht rechtzeitig fahrbar ist. */
public class Stopovers {
    OvernightAreas onPlannedRoute(long appUserId, long consequenceId, @Nullable OffsetDateTime latestArrival,
                                  @Nullable Integer maxResults);
    OvernightAreas onOption(long appUserId, long consequenceId, List<RoutedSegment> path, OffsetDateTime departure,
                            @Nullable OffsetDateTime latestArrival, @Nullable Integer maxResults);
    StopoverResult evaluate(long appUserId, long consequenceId, double latitude, double longitude,
                            OffsetDateTime continueAt); // Entwurf

    record OvernightAreas(List<OvernightArea> areas, @Nullable StopoverProblem problem) {}
    record OvernightArea(String name, PlaceKind kind, double latitude, double longitude, OffsetDateTime arrivalAt,
                         double remainingMeters, double offRouteMeters) {}
    enum StopoverProblem { NOTHING_REACHABLE, ARRIVES_BEFORE_LIMIT, ROUTE_NOT_USABLE }
    record StopoverResult(Leg toStay, Leg onward, @Nullable SuggestionProblem problem) {}
    record Leg(OffsetDateTime departure, OffsetDateTime arrival, double distanceMeters, Duration duration,
               Duration delay, List<Long> conflictingIncidentIds) {}
}
```

#### `find_overnight_areas`

- **Orte:** Tabelle `place` in `network`, siehe [network.md](network.md#orte). Kein externer Reverse-Geocoder: Massenabfragen verletzen die Nutzungsbedingungen von Nominatim, und die Route ginge an Dritte.
- **Eine Zeitleiste für alle Wege:** `CandidateEvaluator` liefert je Schritt die Durchfahrt (`Outcome.passages`), für die geplante Route und für Umfahrungen gleich. Dadurch zählen alle Verzögerungen davor wie in der Bewertung der Optionen, nicht nur die von `DELAYED`-Consequences.
- **Umfahrungen:** `SuggestedOption.path` hält die gefahrenen Segmente, `ProposalDraft` merkt sie sich für den Lauf. Nicht in der API, nicht im Kontext des Modells, nicht gespeichert.
- **Ablauf `Stopovers`:**
  1. Zeitleiste: geplante Route ab `ScenarioFactory.forConsequence` (unterwegs ab der geschätzten Position und jetzt), Umfahrung über `path` ab ihrer Abfahrt. Problem im Szenario (vorbei, ohne Plan oder Koordinaten) → `ROUTE_NOT_USABLE`.
  2. Hindernis: das früheste aus erster gesperrter Durchfahrt (auch bei einer früheren Sperrung als der Consequence des Laufs) und, nur auf der geplanten Route, erster Section der Consequence. Eine Umfahrung hat oft keines.
  3. Grenze: `latest_arrival` oder die erste Abendgrenze nach der Abfahrt in der Zeitzone der Region. Nicht nach der Abfahrt → Fehler. Ohne Hindernis und mit Ankunft vor der Grenze → `ARRIVES_BEFORE_LIMIT`, keine Übernachtung nötig.
  4. Orte: `Places.near` über den **ganzen** Weg, dann nur Orte, deren nächstes Segment vor dem Hindernis liegt. Sonst fände der Umkreis den Ort direkt hinter einer Sperrung.
  5. Ankunft und Restweg aus der Durchfahrt des nächsten Segments und der Lage des Orts darauf (`OvernightAreaFinder`). Orte nach der Grenze entfallen, ebenso Orte näher am Ziel-Stop als `min-spacing`.
  6. Auswahl, höchstens `max_results` (1–5, Standard 3):
     - `city`/`town` zuerst (dort gibt es eher Unterkünfte), jeweils nah am Hindernis zuerst (wenig Restweg für morgen).
     - Der Ort direkt vor dem Hindernis ist immer dabei, auch als `village`, außer ein größerer liegt näher als `min-spacing` daneben. Sonst verdrängen ihn Städte weit hinten.
     - Dann `village`, solange Platz ist. Mindestabstand entlang der Route `min-spacing`, sonst fünf Vororte derselben Stadt.
     - Keiner → `NOTHING_REACHABLE`.
  - **Grenze:** Eine Sperrung, die man abwarten könnte, beendet die Suche ebenfalls. Orte dahinter werden nicht angeboten, auch wenn die Consequence des Laufs weiter hinten liegt.
- **Tool:** `route_option_id` (`null` oder `keep_route`: geplante Route, `route_<n>`: Umfahrung), `latest_arrival` (ISO mit Offset, `null`: Abendgrenze), `max_results`. Unbekannte Id, `departure_<n>` (eine verschobene Abfahrt soll am Incident vorbeiführen, eine Übernachtung darauf ergibt keinen Sinn), falsches Format oder Grenze vor der Abfahrt → `ToolInputException`.
  - Liefert `problem`, `route_option_id` und je Ort `area_id`, `name`, `kind`, `arrival_at`, `remaining_km`, `off_route_km`.
  - `arrival_at` ist ungefähr: am nächsten Punkt des Wegs, ohne den Weg dorthin. `off_route_km` ist Luftlinie. Die Beschreibung des Tools sagt das, `evaluate_stopover` rechnet es genau.
- **`area_id`:** eigene Registrierung im `RunContext` (`registerArea`), getrennt von den Optionen, mit der `route_option_id`, damit eine spätere Übernachtung auf dieser Umfahrung bleibt. `submit_proposals` kann keinen Ort nennen, Orte sind nur Eingabe für `search_accommodations`.
- **`appliesTo`** (`RunContext.overnightRelevant`): eine zählende Consequence ist `TOO_LATE` oder nicht passierbar (`delay == null`). Das zweite auch ohne Frist: Ohne Frist gibt es nie `TOO_LATE`, und bei einer Sperrung ohne Ende kann die Übernachtung die einzige Lösung sein (Lukas, 2026-11-07). Später auch beim Ziel `STAY_OVERNIGHT`. Dazu muss `stopover-enabled` an sein.
- **Ohne Modell** (`withoutModel`): keine Orte, sie wären allein kein Vorschlag.

#### `evaluate_stopover`

- **Warum mit Angebot statt Ort:** Das Hotel liegt oft einige Kilometer vom Ortsmittelpunkt, der Umweg zählt. Deshalb `offer_ref` aus `search_accommodations`, Koordinaten aus den Stammdaten von LiteAPI.
- **Ablauf `Stopovers.evaluate`:**
  1. **Heute:** aktuelle Position bzw. Start → Hotel, im Szenario mit allen aktiven Incidents der Route (wie `forConsequence`), Abfahrt wie geplant bzw. jetzt. Liefert Ankunft, Strecke, Dauer, Verzögerung, Konflikte.
  2. **Morgen:** Hotel → Ziel-Stop, Abfahrt `continue_at`. Incidents mit ihren Zeitfenstern zu diesem Zeitpunkt, eine nächtliche Sperrung kann morgens vorbei sein oder noch gelten. Liefert dieselben Felder, dazu `meets_deadline` gegen die Ankunft des Ziel-Stops, falls die noch gilt.
  3. Kein Weg zum Hotel oder weiter → `problem`, das Modell kann ein anderes Angebot prüfen.
- **Tool:** `continue_at` muss nach der Ankunft im Hotel und nach dem Check-in liegen, sonst `ToolInputException`. Registriert einen Entwurf mit `kind` `STOPOVER` (`option_id` `stopover_<n>`): Angebot, beide Fahrten, Preis. Das Modell kann ihn neben `KEEP_ROUTE` und Umfahrungen vorschlagen.
- **Übernehmen (nach Bestätigung des Nutzers, nicht durch das Modell):**
  1. Buchen über den Ablauf in [accommodation-alternatives.md](accommodation-alternatives.md#ablauf): Prebook, PayPal, Book. Braucht eine Reservierung ohne `bookingId` (heute hängt jede Reservierung an einer bestehenden Buchung).
  2. Erst danach `trips`: neuer Stop am Hotel mit Ankunft und Abfahrt `continue_at`, Route wird geteilt (Start → Hotel, Hotel → Ziel), beide neu geplant, `RouteChanged` → `incidents` bewertet neu. Die neue Buchung hängt am neuen Stop.
  3. Reihenfolge, damit eine gescheiterte Buchung den Trip nicht ändert. Scheitert Schritt 2 nach der Buchung, bleibt die Buchung bestehen, der Nutzer bekommt einen Hinweis und kann den Stop von Hand anlegen.
- Beim Übernehmen prüft das Backend Angebot und beide Fahrten erneut, der Entwurf kann veraltet sein.

## Kontext des Modells

- Steht von Anfang an in der Anfrage, das spart Lese-Tools und Runden: aktuelle Zeit, Route (Start, Ziel, Waypoints, Planankunft, Frist, Puffer), Consequences nach [Rangfolge](consequence-assessment.md#rangfolge), folgende Stops (Name, Ankunft, Abfahrt), geschätzte Position, wenn der Nutzer unterwegs ist.
- **Tools je Lage:** Bei `DELAYED` und `TOO_LATE` zunächst dieselben, mit den Unterkunfts-Tools nur noch bei `TOO_LATE` oder Ziel `STAY_OVERNIGHT`.
- **Sprache:** `summary` und `reason` schreibt das Modell auf Englisch (Lukas, 2026-11-07). Die Sprache steckt damit im gespeicherten Text, andere Sprachen brauchen später eine Einstellung je Nutzer.
- **Fremde Texte:** Beschreibungen aus Verkehrsmeldungen stammen von Dritten. Sie werden als Daten gekennzeichnet. Auch deshalb darf kein Tool die Reise ändern.

## Datenmodell

- Eigene Tabellen, Migration `V43`, abgeleitet und nicht historisiert. `@ManyToOne` nur innerhalb des Moduls, `route_id` als Id mit Fremdschlüssel. Wird die Route gelöscht, löscht die Datenbank die Läufe mit (`ON DELETE CASCADE`).
- `AdvisorRun` (`advisor_run`):
  - `route_id`, `consequence_id` (vom Nutzer gewählt, sonst die schlimmste der Route, `ON DELETE SET NULL`)
  - `trigger` (`AUTO`, `USER`, Spalte `run_trigger`), `goal` (optional)
  - `status`: `PENDING`, `RUNNING`, `COMPLETED`, `COMPLETED_WITHOUT_MODEL`, `FAILED` mit `failure` (`ROUTING_UNAVAILABLE`, `CONSEQUENCE_GONE`, `INTERNAL_ERROR`, `ABANDONED`, `INTERRUPTED`)
  - `auto_key` (nur `AUTO`, eindeutig mit `route_id`; `NULL` kollidiert nicht)
  - `situation`: Hash aus Incidents mit Kategorie `TOO_LATE` oder `DELAYED`, Frist und Planankunft, siehe [Veraltete Vorschläge](#veraltete-vorschläge)
  - `summary`, `failure`, `created_at`, `finished_at`
- `AdvisorProposal` (`advisor_proposal`): `run`, `position`, `kind`, `reason`, `via`, `departure_at`, `arrival_at`, `distance_meters`, `duration`, `delay`, `shared_ratio`, `meets_deadline`.
- `advisor_proposal_point`: `proposal`, `position`, `latitude`, `longitude`, `road_hint`, `waypoint_id` (`ON DELETE SET NULL`). Reproduziert die Strecke beim Wählen.
- `advisor_proposal_conflict`: `proposal`, `incident_id` (`ON DELETE CASCADE`), `impact`, `delay`.
- Texte als `VARCHAR(n)`, Dauern über `DurationConverter`, siehe [architecture.md](architecture.md#persistence).

### Veraltete Vorschläge

- Beim Lesen vergleicht `advisor` `situation` mit der aktuellen Lage der Route. Weicht sie ab, kommt der Lauf mit `stale: true` zurück, es wird nichts neu berechnet.
- Der Nutzer kann dann einen neuen Lauf starten. Wählt er trotzdem, plant `RoutePlanning` neu und `ConsequenceMatcher` bewertet den neuen Weg, wie bei jeder Wahl.

## API

- `POST /api/v1/routes/{routeId}/alternative-proposals`: Body optional `consequenceId`, `goal` (`ARRIVE_ON_TIME`, `MINIMIZE_DELAY`, Standard `ARRIVE_ON_TIME`). Antwort `202` mit `runId`, läuft asynchron, weil mehrere Modellaufrufe nötig sind.
- `GET /api/v1/routes/{routeId}/alternative-proposals`: letzter Lauf der Route, auch der automatische. Ohne Lauf `200` mit `run: null`.
- `GET /api/v1/alternative-proposals/{runId}`: `status`, `stale`, `summary`, `proposals`.
- **Wählen:** `REROUTE` über `PUT /routes/{routeId}/choice` mit den Punkten des Vorschlags, `SHIFT_DEPARTURE` über dasselbe `choice` mit `departureAt` ([recommendations.md](recommendations.md#auswahl-speichern)). `KEEP_ROUTE` ändert nichts.
- **Fehler** (`AdvisorExceptionHandler`): fremde oder unbekannte Route bzw. fremder Lauf `404` `TRIP_NOT_FOUND`, keine aktuelle `TOO_LATE`- oder `DELAYED`-Consequence `409` `NO_ACTIVE_CONSEQUENCE`, `consequenceId` gehört nicht zur Route `400` `INVALID_PROPOSAL_INPUT`.
- Geometrie für die Karte: siehe [Offen](#offen).

## Modell, Kontingent, Ausfall

- **`assistant.logic.ToolConversation`** (umgesetzt, kennt keine Fachmodule): Schleife über die OpenAI Responses API mit Function Calling und `store: false`. Der Aufrufer bringt Anweisung, Eingabe und Tools mit, `submit_proposals` ist das letzte Tool (`finish`). Ein Tool-Aufruf je Runde. `StructuredExtraction` bleibt unverändert.
- **Fehler in Tools:** Ungültige Eingaben meldet das Tool als `ToolInputException`, das Modell bekommt die Meldung und kann es neu versuchen. Jede andere Ausnahme (z. B. `RoutingUnavailableException`) beendet das Gespräch und geht an `advisor`.
- **Kontingent:** Jede Runde ist ein Modellaufruf und zählt gegen das Limit. `AUTO` nutzt `system-requests-per-hour`, `USER` `requests-per-hour` des Nutzers. Das Systemkontingent (Standard 60) teilt sich `AUTO` mit der Anreicherung von Verkehrsmeldungen. Bei bis zu 8 Aufrufen je Lauf reicht es für etwa 7 volle Läufe je Stunde, danach endet ein Lauf als `COMPLETED_WITHOUT_MODEL`, behält seinen `auto_key` und wird für diese Lage nicht wiederholt.
- **Grenzen:** `max-turns` je Lauf. In der letzten Runde muss das Modell `submit_proposals` aufrufen (`tool_choice`), es schließt also mit dem bisher Gefundenen ab. Tut es das trotzdem nicht, gilt der Lauf als ohne Modell (siehe unten).
- **Ohne Modell** (kein Key, Limit erreicht, Fehler, kein Abschluss): die besten Optionen von `Suggestions.forConsequence` ohne Begründung, Status `COMPLETED_WITHOUT_MODEL`. Vorschläge dürfen wie bei der Suche nach Unterkünften nicht am Modell scheitern.
- **`RoutingUnavailableException`, sonstige Fehler:** `FAILED`, siehe [Auslöser](#automatisch-nur-too_late).
- **Neustart:** Läufe in `PENDING` oder `RUNNING` werden beim Start `FAILED`, damit sie `auto_key` freigeben.
- **Datenschutz:** An OpenAI gehen Namen und Zeiten von Stops und Waypoints, Straßen, Incident-Texte und die berechneten Optionen, also Ort und Zeit der Reise. Keine Namen oder Kontaktdaten der Reisenden, keine Buchungsnummern. Das `AUTO`-Verhalten braucht einen Hinweis in App und Datenschutzerklärung.

## Abhängigkeiten

- `advisor → recommendations`, `→ incidents`, `→ trips`, `→ assistant`. Kein anderes Modul kennt `advisor`, kein Zyklus. Rückwärts nur über das Event `RouteTooLate` aus `incidents.logic`.
- Vorerst kein `advisor → bookings`, das kommt mit dem Adapter für `StayOptions` und `LateArrivalNotices`.
- **`trips.logic`:** Eigentümer einer Route lesen, `RouteSnapshots.ownerOf(routeId)`. Der automatische Lauf kennt nur die `routeId`, `Suggestions` und `RouteSnapshots` verlangen `appUserId`.
- `TripTools` lassen sich nicht wiederverwenden: Sie lesen den Nutzer aus dem `SecurityContext`, den ein Hintergrundlauf nicht hat. Die Tools von `advisor` rufen dieselbe Logik auf.

## Konfiguration

```properties
nfv.advisor.max-turns=8
nfv.advisor.max-proposals=3
nfv.advisor.run-timeout=15m
nfv.advisor.auto-enabled=false
nfv.advisor.stopover-enabled=false
# Orte für Übernachtungen, Werte sind Standard
nfv.recommendations.stopover.latest-arrival=22:00
nfv.recommendations.stopover.max-off-route-meters=5000
nfv.recommendations.stopover.min-spacing-meters=15000
nfv.recommendations.stopover.default-areas=3
nfv.recommendations.stopover.max-areas=5
```

- `stopover-enabled`: Bietet dem Modell `find_overnight_areas` an. Standard `false`, bis `search_accommodations` da ist: Mit einem Ort allein kann das Modell nichts vorschlagen, das Tool kostet dann nur eine Runde.
- `auto-enabled`: Schaltet nur die Läufe durch `RouteTooLate`, die des Nutzers gehen immer. **Standard vorerst `false` (Lukas, 2026-11-07):** Eingeschaltet gehen Routen und Zeiten ohne Zutun des Nutzers an OpenAI. Einschalten erst nach dem Lauf gegen die echte API und mit dem Hinweis in App und Datenschutzerklärung. Solange gibt es trotz `TOO_LATE` keinen Lauf ohne Anfrage des Nutzers.
- In den Test-Properties steht es ausdrücklich auf `false`, sonst würde jeder Test mit `TOO_LATE` einen Lauf im Hintergrund starten, auch nach einem Wechsel des Standards. Nur `RouteTooLateListenerTest` schaltet es ein.

## Reihenfolge der Umsetzung

1. Doku.
2. `recommendations`: `forConsequence`, nur betroffene Abschnitte, `DEPARTURE_SHIFT`, bereits unterwegs, ohne Modell testbar.
3. `assistant`: `ToolConversation`, gegen eine gemockte API getestet (`ToolConversationTest`). Vor dem ersten Betrieb einmal gegen die echte API prüfen, besonders das Wiederholen des verschlüsselten Reasonings.
4. `advisor`: Migration `V43`, Agent, Tools, API, Schnittstellen `StayOptions` und `LateArrivalNotices`. Erste Läufe gegen die echte API.
5. `incidents`: `RouteTooLate` in `assessRoute`, Listener und `auto_key` in `advisor`, `RouteSnapshots.ownerOf`, Schalter `nfv.advisor.auto-enabled` (umgesetzt, Standard vorerst `false`). Erst damit und eingeschaltet laufen automatische Läufe.

## Tests

- `ConsequenceAssessment`: `RouteTooLate` nur mit aktuellem `TOO_LATE`, nicht bei zurückgerollter Transaktion.
- Listener: gleicher `auto_key` nach Neuplanung startet keinen Lauf, geänderte Incidents oder Frist schon, `FAILED` gibt den Schlüssel frei.
- Agent mit gemockter `ToolConversation` (Tests rufen OpenAI nie auf): unbekannte Kennungen verworfen, Werte kommen aus dem Backend, `max-turns`, ohne Modell `COMPLETED_WITHOUT_MODEL`.
- API: `202`, fremde Route `404`, `DELAYED` startet nur durch den Nutzer, `NO_ACTIVE_CONSEQUENCE`, laufender Lauf wird wiederverwendet, `stale` nach geänderter Lage.
- Umgesetzt in `AlternativeProposalsTest` (Tools, Kontext, Werte aus dem Backend, ohne Modell, abgebrochene Läufe, Neustart, API), `ConsequenceMatchingTest` (`RouteTooLate`), `AutomaticProposalsTest` (`auto_key`, `runForSystem`, gleichzeitige Events, veraltetes Event, ausgeschaltet) und `RouteTooLateListenerTest` (Commit, Rollback).
- `find_overnight_areas`: `OvernightAreaFinderTest` (Lage, Hindernis, Grenze, Auswahl), `StopoversTest` (Wellington: Ort hinter der Sperrung, frühere Sperrung, Verzögerung, Abendgrenze, unterwegs, Umfahrung, Eingaben), `OvernightAreaToolTest` (Kennungen, Fehler an das Modell, Schalter), `OvernightToolOfferedTest` (Lauf: Sperrung ohne Frist und `TOO_LATE` ja, Verzögerung innerhalb der Frist nein).

## Offen

- **Benachrichtigung:** Kanal und Schwellen offen ([architecture.md](architecture.md#offene-punkte)). Bis dahin sieht der Nutzer automatische Läufe nur in der App.
- **Geometrie der Vorschläge:** Nicht gespeichert, die Karte braucht aber eine Linie. Speichern oder beim Öffnen aus den Punkten neu routen, beim Frontend zu entscheiden.
- **Frontend:** Anzeige der Vorschläge im `IncidentSheet`, Hinweis `stale`, Knopf für den Lauf bei `DELAYED`.
- **Mehrere Replicas:** Der Schutz vor doppelten Läufen hält über den Unique-Constraint, das Zurücksetzen beim Start müsste dann auf Läufe der eigenen Instanz oder ein Zeitlimit umgestellt werden.
- **Aufräumen:** wie lange alte Läufe aufgehoben werden.
- **Übernachtung unterwegs:** Standard für `latest_arrival` (feste Uhrzeit, Check-in des Hotels oder Wahl des Nutzers). Werte für `max-off-route-meters` und `min-spacing-meters` an echten Routen prüfen. Mit `search_accommodations` und `evaluate_stopover` reichen `max-turns` (8) und `run-timeout` (15 min) knapp nicht mehr: Routen, Abfahrten, Orte, zwei Suchen, zwei Prüfungen und `submit_proposals` sind schon acht Runden.
- **Gleichzeitige Anfragen:** Zwei `POST` im selben Moment können zwei Läufe starten, der zweite kostet nur Modellaufrufe, der letzte Lauf gewinnt in der Anzeige.
- **Ein Lauf je Route:** Folgen für spätere Routen des Trips stehen nur als Kontext im Modell, siehe [recommendations.md](recommendations.md#offen).