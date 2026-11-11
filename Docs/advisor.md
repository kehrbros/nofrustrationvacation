# Vorschläge durch den Agenten (`advisor`)

- **Status:** beschlossen 2026-11-06 (Lukas). Läufe auf Anfrage des Nutzers und automatische Läufe (`RouteTooLate`) umgesetzt (`V44`, Agent, Tools, API), getestet mit geskriptetem Modell, noch nie gegen die echte API. Unterkünfte über `find_overnight_areas` und `search_accommodations` umgesetzt (`V46`, 2026-11-07), noch nicht gegen die LiteAPI-Sandbox geprüft, buchbar ist vorerst nur `TARGET_STAY` mit gebuchter Unterkunft. Automatische Läufe sind vorerst ausgeschaltet, siehe [Konfiguration](#konfiguration). Auswählen, Verwerfen und Frontend umgesetzt (`V47`, 2026-11-07), siehe [Auswählen und Verwerfen](#auswählen-und-verwerfen) und [Frontend](#frontend), nicht gegen LiteAPI- und PayPal-Sandbox geprüft.
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

`find_overnight_areas` und `search_accommodations` sind umgesetzt, an beiden hängt `stopover-enabled` (Standard `true`, siehe [Konfiguration](#konfiguration)). `draft_late_arrival_notice` fehlt noch, dafür gibt es nur die Schnittstelle ohne Implementierung und ohne Tool.

```java
/** Unterkünfte, wenn die Route nicht rechtzeitig fahrbar ist. Orte: siehe Stopovers unten. */
public interface StayOptions {
    boolean available();                                       // LiteAPI konfiguriert
    List<StayOffer> search(long appUserId, StaySearch search);  // ohne Modell, harte Prüfung, SearchLimiter
    Optional<BookedStay> bookedStay(long appUserId, long tripId, long stopId);

    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      int guests, int rooms, @Nullable BigDecimal maxPrice, String currency,
                      @Nullable String excludeHotelId, int maxResults) {}
    record StayOffer(String hotelId, String offerId, String name, double latitude, double longitude,
                     int distanceMeters, @Nullable Double stars, @Nullable Double rating, @Nullable String boardName,
                     BigDecimal price, String currency, boolean refundable,
                     @Nullable LocalDateTime freeCancellationUntil) {}
    record BookedStay(long bookingId, ..., @Nullable LocalDate checkIn, @Nullable LocalDate checkOut,
                      @Nullable Integer guests, int rooms, @Nullable BigDecimal maxPrice, String currency,
                      @Nullable String hotelId) {}
}

/** Entwurf einer Nachricht an die Unterkunft bei später Ankunft, senden muss der Nutzer. */
public interface LateArrivalNotices {
    Optional<LateArrivalNotice> draft(long appUserId, long stopId, OffsetDateTime expectedArrival);

    record LateArrivalNotice(long bookingId, String accommodationName, @Nullable String email,
                             @Nullable String phone, String message) {}
}
```

- Die Implementierungen liegen als Adapter in `advisor` (`LiteApiStayOptions`), damit `bookings` das Modul nicht kennt. Feldtypen wie in `bookings`: `freeCancellationUntil` ist lokal am Hotel (`LocalDateTime`), Preise sind Gesamtpreise des Aufenthalts in der Währung des Angebots.
- **Tools:**
  - `find_overnight_areas(route_option_id?, latest_arrival?, max_results?)` (umgesetzt): Orte vor dem Hindernis, auf der geplanten Route oder einer Umfahrung, die rechtzeitig erreichbar sind, siehe [unten](#find_overnight_areas). Nötig, weil `AlternativeSearch.criteriaFor` um die gebuchte Unterkunft sucht, also genau dort, wo der Nutzer nicht hinkommt.
  - `search_accommodations(area_id?, radius_km?, check_in?, check_out?, max_price?, max_results?)` (umgesetzt): Hotels um einen Ort oder den Ziel-Stop, siehe [unten](#search_accommodations).
  - `draft_late_arrival_notice`: oft die günstigste Lösung, wenn nur der Check-in zu spät ist. Kontaktdaten ergänzt das Backend, sie gehen nicht an das Modell.
- **Neue `kind`:** `STOPOVER` und `TARGET_STAY` (umgesetzt, das Angebot steht in `stay`, Migration `V46`), `NOTIFY_ACCOMMODATION` kommt mit `draft_late_arrival_notice`. Ebenso das Ziel `STAY_OVERNIGHT`. Buchungen stehen weiter nicht im Kontext des Modells, `search_accommodations` sagt nur `replaces_booked_stay`.
- Es gilt [accommodation-alternatives.md](accommodation-alternatives.md#rollen-assistant-und-liteapi): Das Modell löst keine Buchung aus.

#### Logik in `recommendations` (`Stopovers`)

- Orte sind Routing ohne Modell, deshalb in `recommendations.logic` neben `Suggestions`. `recommendations` kennt `trips`, `incidents` und `network` schon, `advisor` braucht so keine neue Abhängigkeit.
- `advisor` ruft das als Tool auf, wie `forConsequence`. Kein Aufruf in einer Transaktion.

```java
/** Übernachtung unterwegs, wenn die Route nicht rechtzeitig fahrbar ist. */
public class Stopovers {
    OvernightAreas onPlannedRoute(long appUserId, long consequenceId, @Nullable OffsetDateTime latestArrival,
                                  @Nullable Integer maxResults);
    OvernightAreas onOption(long appUserId, long consequenceId, List<RoutedSegment> path, OffsetDateTime departure,
                            @Nullable OffsetDateTime latestArrival, @Nullable Integer maxResults);

    record OvernightAreas(List<OvernightArea> areas, @Nullable StopoverProblem problem) {}
    record OvernightArea(String name, PlaceKind kind, double latitude, double longitude, OffsetDateTime arrivalAt,
                         double remainingMeters, double offRouteMeters) {}
    enum StopoverProblem { NOTHING_REACHABLE, ARRIVES_BEFORE_LIMIT, ROUTE_NOT_USABLE }
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
  - `arrival_at` ist ungefähr: am nächsten Punkt des Wegs, ohne den Weg dorthin. `off_route_km` ist Luftlinie. Die Beschreibung des Tools sagt das.
- **`area_id`:** eigene Registrierung im `RunContext` (`registerArea`), getrennt von den Optionen, mit der `route_option_id`, damit eine spätere Übernachtung auf dieser Umfahrung bleibt. `submit_proposals` kann keinen Ort nennen, Orte sind nur Eingabe für `search_accommodations`.
- **`appliesTo`** (`RunContext.overnightRelevant`): eine zählende Consequence ist `TOO_LATE` oder nicht passierbar (`delay == null`). Das zweite auch ohne Frist: Ohne Frist gibt es nie `TOO_LATE`, und bei einer Sperrung ohne Ende kann die Übernachtung die einzige Lösung sein (Lukas, 2026-11-07). Später auch beim Ziel `STAY_OVERNIGHT`. Dazu muss `stopover-enabled` an sein.
- **Ohne Modell** (`withoutModel`): keine Orte, sie wären allein kein Vorschlag.

#### `search_accommodations`

- **Wozu:** Macht aus einem Ort von `find_overnight_areas` oder dem Ziel-Stop Vorschläge, die der Nutzer buchen kann. Nutzt dieselbe Suche wie [accommodation-alternatives.md](accommodation-alternatives.md): `AlternativeSearch.offers` in `bookings`, eine Variante von `search` ohne `bookingId` und ohne `AlternativeRanking` (ein Tool des Modells ruft nicht wieder das Modell auf). Harte Prüfung, günstigstes Angebot je Hotel und `SearchLimiter` (Suchen je Nutzer und Stunde) bleiben, ein Aufruf des Tools ist eine Suche des Nutzers.
- **Parameter** (alle Pflicht, `null` für den Standard):
  - `area_id`: `area_<n>` aus `find_overnight_areas` → `STOPOVER`; `null` → Ziel-Stop der Route → `TARGET_STAY`. Unbekannte Id → `ToolInputException`.
  - `radius_km`: 0,5–50, Standard 5 (wie `default-radius`). Außerhalb → Fehler aus `AlternativeSearch.validate` an das Modell.
  - `check_in`, `check_out` (ISO-Datum): Standard am Ort das Datum der ungefähren Ankunft und der Tag danach; am Ziel-Stop aus der gebuchten Unterkunft (`criteriaFor`), sonst Ankunft und Abfahrt des Stops, sonst eine Nacht. Ohne Ankunft am Stop muss das Modell `check_in` angeben.
  - `max_price`: Gesamtpreis in der Währung der Antwort. Standard: am Ziel-Stop der (anteilige) Preis der gebuchten Unterkunft wie im bestehenden Ablauf, sonst ohne Grenze.
  - `max_results`: 1–5, Standard 3.
- **Vom Backend, nicht vom Modell:** Koordinaten (Ort, gebuchte Unterkunft oder Stop), Gäste und Zimmer (gebuchte Unterkunft, sonst `Trip.travelers`, sonst 2; ein Zimmer je zwei Gäste), Währung (gebuchte Unterkunft, sonst EUR), das gebuchte Hotel wird ausgeschlossen.
- **Antwort:** `problem` (`null`, `NO_OFFERS`, `NO_LOCATION`, `RATE_LIMITED`, `SEARCH_UNAVAILABLE`), `place`, Zeitraum, `nights`, `guests`, `rooms`, `currency`, `max_price` und je Angebot eine Option `stay_<n>` wie die anderen Optionen, mit `stay`: Hotel, Entfernung, Sterne, Bewertung, Verpflegung, Preis, Storno, `replaces_booked_stay`. Ein Ausfall von LiteAPI oder ein verbrauchtes Kontingent beendet den Lauf nicht, das Modell sieht nur das Problem.
- **Vorschlag:**
  - `STOPOVER`: Weg wie die Option des Orts (`keep_route` oder `route_<n>`: `via`, Punkte, Abfahrt, Konflikte), `arrival_at` die ungefähre Ankunft am Ort. Der Weg zum Hotel wird nicht geroutet, `meets_deadline` bleibt leer.
  - `TARGET_STAY`: geplante Route mit den Werten von `keep_route`, dazu das Angebot. Mit gebuchter Unterkunft am Stop `replacesBookingId`.
  - Gespeichert wird das Angebot in den Spalten `stay_*` von `advisor_proposal` (`ProposalStay`), damit es ohne neue Suche gebucht werden kann. Das Backend prüft es beim Buchen erneut (Prebook).
- **`appliesTo`:** wie `find_overnight_areas` (`overnightRelevant` und `stopover-enabled`), zusätzlich muss LiteAPI konfiguriert sein (`StayOptions.available`).
- **Ohne Modell** (`withoutModel`): keine Unterkünfte, eine Suche kostet und braucht eine Abwägung.
- **Buchen:** `TARGET_STAY` mit `replacesBookingId` über den bestehenden Ablauf: `POST /accommodation-bookings/{replacesBookingId}/alternative-reservations` mit `hotelId`, `offerId`, Zeitraum, `guests`, `rooms`, `currency` und `price` als `maxPrice`. Liegt das Hotel in einem anderen Ort, zieht der Stop mit ([accommodation-alternatives.md](accommodation-alternatives.md)). `STOPOVER` und `TARGET_STAY` ohne gebuchte Unterkunft: Reservierung am Stop ohne `bookingId`, `POST /stops/{stayStopId}/stay-reservations`, siehe unten.

#### Übernachtung übernehmen

- **Umgesetzt anders als zuerst entworfen** (Philipp, 2026-11-07): erst den Stop anlegen, dann buchen. Der Entwurf („erst buchen, dann Stop“) brauchte eine Reservierung ohne Stop, und eine gescheiterte Buchung hätte den Trip nicht geändert. Jetzt sieht der Nutzer die neue Planung sofort und bucht danach am neuen Stop, wie jede Unterkunft.
- Ablauf beim Übernehmen (`ProposalDecisions.accept`, nicht durch das Modell):
  1. `trips`: neuer Stop am Hotel (Name = Ort des Angebots), Ankunft = ungefähre Ankunft des Vorschlags in der Zeitzone des Ziels, Abfahrt = Tag des Check-outs 09:00. Er kommt vor den Ziel-Stop der Route, die Route wird geteilt (Start → Hotel, Hotel → Ziel), beide neu geplant, `RouteChanged` → `incidents` bewertet neu. Die alte Route und mit ihr der Lauf werden gelöscht (`ON DELETE CASCADE`), die Entscheidung wird deshalb nicht gespeichert.
  2. Ankunft am Ziel verschiebt sich um die Nächte, nicht vor die Abfahrt am neuen Stop und nicht nach die Abfahrt des Ziels.
  3. Gebuchte Unterkünfte am Ziel, die vor dem Check-out-Tag beginnen, kommen auf die To-do-Liste (`NOTIFY_LATE_ARRIVAL`), siehe [domain.md](domain.md#bookings).
  4. Danach öffnet das Frontend die Buchung am neuen Stop (`/reisen/{trip}/stops/{stayStopId}/unterkunft`), Ablauf wie [accommodation-alternatives.md](accommodation-alternatives.md#ablauf). Bucht der Nutzer nicht, bleibt der Stop ohne Unterkunft, er kann selbst eine eintragen oder den Stop löschen.
- Der Weg zum Ort (`via`, Punkte der Option) wird vorerst nicht übernommen, die neue Route Start → Hotel plant `trips` neu.
- Beim Buchen prüft das Backend das Angebot erneut, der Vorschlag kann veraltet sein.

## Auswählen und Verwerfen

- Der Nutzer wählt einen Vorschlag oder verwirft alle („Keine passt, ich plane selbst um“). Nur die Wahl wird an der Reise gespeichert, der Lauf behält die Entscheidung als Merker.
- Läufe lassen sich jederzeit neu starten, auch nach dem Verwerfen. Ein verworfener Lauf kann trotzdem noch übernommen werden.
- **Übernehmen** (`ProposalDecisions.accept`): Lauf muss fertig sein (`COMPLETED`, `COMPLETED_WITHOUT_MODEL`).
  - `REROUTE`: Punkte des Vorschlags über `RouteChoice` wie `PUT /routes/{routeId}/choice`.
  - `SHIFT_DEPARTURE`: neue Abfahrt über dasselbe `choice`.
  - `KEEP_ROUTE`: ändert nichts.
  - `TARGET_STAY`: ändert den Trip nicht, Antwort nennt `stayStopId` (Ziel-Stop) und `replacesBookingId`. Gebucht wird danach über den PayPal-Ablauf, mit `replacesBookingId` als Alternative, sonst über `stay-reservations`.
  - `STOPOVER`: siehe [Übernachtung übernehmen](#übernachtung-übernehmen).
  - Passt der Vorschlag nicht mehr zur Reise (Wegpunkte weg, Stop verschoben, ungültige Zeiten), `409` `PROPOSAL_NOT_APPLICABLE`, der Nutzer sucht neu.
- **Ersatz gebucht:** Wird eine Unterkunft über `replacesBookingId` ersetzt, kommt die alte auf die To-do-Liste (`REPLACED`), sie wird nie automatisch storniert.

## Frontend

- `TripAlternativesSheet` (Klick auf eine Consequence auf der Karte oder im Banner): Abschnitt „Lösungen“ mit `ProposalsPanel`, darunter eingeklappt „Selbst umplanen“ mit den bisherigen abgeleiteten Wegen (Route, alternative Unterkunft).
- `ProposalsPanel`: letzter Lauf der Route, Knopf „Lösungen suchen“/„Neu suchen“, Fortschritt (Abfrage alle 2,5 s, der Dialog darf geschlossen werden), Karten mit Titel, Kurzinfo, „Empfohlen“ für Rang 1, „Gewählt“ nach dem Übernehmen. Aufklappen zeigt die Wirkung und den Knopf zum Übernehmen. Hinweis bei `stale`.
- **KI unsichtbar:** Texte baut das Frontend aus den Feldern (`proposalText.ts`), `summary` und `reason` des Modells werden nicht gezeigt (Englisch, siehe [Kontext des Modells](#kontext-des-modells)).
- `ProposalsBanner` auf der Reiseseite: offener Lauf (fertig, Vorschläge, nicht entschieden) einer Route mit Incident verlinkt in den Dialog.
- `StayBookingSheet` (`stops/:stopId/unterkunft`): Buchung des Hotels aus dem Vorschlag, Gastdaten, PayPal-Button, Rückkehr der Weiterleitung über `?reservierung=`.
- To-do-Liste auf der Reiseseite (`TodoList`), siehe [domain.md](domain.md#bookings).

## Kontext des Modells

- Steht von Anfang an in der Anfrage, das spart Lese-Tools und Runden: aktuelle Zeit, Route (Start, Ziel, Waypoints, Planankunft, Frist, Puffer), Consequences nach [Rangfolge](consequence-assessment.md#rangfolge), folgende Stops (Name, Ankunft, Abfahrt), geschätzte Position, wenn der Nutzer unterwegs ist.
- **Tools je Lage:** Bei `DELAYED` und `TOO_LATE` zunächst dieselben, mit den Unterkunfts-Tools nur noch bei `TOO_LATE` oder Ziel `STAY_OVERNIGHT`.
- **Sprache:** `summary` und `reason` schreibt das Modell auf Englisch (Lukas, 2026-11-07). Die Sprache steckt damit im gespeicherten Text, andere Sprachen brauchen später eine Einstellung je Nutzer.
- **Fremde Texte:** Beschreibungen aus Verkehrsmeldungen stammen von Dritten. Sie werden als Daten gekennzeichnet. Auch deshalb darf kein Tool die Reise ändern.

## Datenmodell

- Eigene Tabellen, Migration `V44`, abgeleitet und nicht historisiert. `@ManyToOne` nur innerhalb des Moduls, `route_id` als Id mit Fremdschlüssel. Wird die Route gelöscht, löscht die Datenbank die Läufe mit (`ON DELETE CASCADE`).
- `AdvisorRun` (`advisor_run`):
  - `route_id`, `consequence_id` (vom Nutzer gewählt, sonst die schlimmste der Route, `ON DELETE SET NULL`)
  - `trigger` (`AUTO`, `USER`, Spalte `run_trigger`), `goal` (optional)
  - `status`: `PENDING`, `RUNNING`, `COMPLETED`, `COMPLETED_WITHOUT_MODEL`, `FAILED` mit `failure` (`ROUTING_UNAVAILABLE`, `CONSEQUENCE_GONE`, `INTERNAL_ERROR`, `ABANDONED`, `INTERRUPTED`)
  - `auto_key` (nur `AUTO`, eindeutig mit `route_id`; `NULL` kollidiert nicht)
  - `situation`: Hash aus Incidents mit Kategorie `TOO_LATE` oder `DELAYED`, Frist und Planankunft, siehe [Veraltete Vorschläge](#veraltete-vorschläge)
  - `summary`, `failure`, `created_at`, `finished_at`
  - `V47`: `incident_id` (Incident, zu dem der Nutzer gefragt hat, `ON DELETE SET NULL`), `decision` (`ACCEPTED`, `DISMISSED`), `chosen_position`, `decided_at`. `@DynamicUpdate`, damit das Speichern der Entscheidung keine veralteten Spalten zurückschreibt.
- `AdvisorProposal` (`advisor_proposal`): `run`, `position`, `kind`, `reason`, `via`, `departure_at`, `arrival_at`, `distance_meters`, `duration`, `delay`, `shared_ratio`, `meets_deadline`.
- `advisor_proposal_point`: `proposal`, `position`, `latitude`, `longitude`, `road_hint`, `waypoint_id` (`ON DELETE SET NULL`). Reproduziert die Strecke beim Wählen.
- `advisor_proposal_conflict`: `proposal`, `incident_id` (`ON DELETE CASCADE`), `impact`, `delay`.
- `V46`: Angebot von `STOPOVER`/`TARGET_STAY` in `advisor_proposal`, Spalten `stay_hotel_id`, `stay_offer_id`, `stay_hotel_name`, `stay_latitude`, `stay_longitude`, `stay_place`, `stay_distance_meters`, `stay_check_in`, `stay_check_out`, `stay_guests`, `stay_rooms`, `stay_price` (`NUMERIC(12, 2)` in der Währung des Angebots, Ausnahme wie `booking_reservation`, siehe [money.md](money.md)), `stay_currency`, `stay_refundable`, `stay_free_cancellation_until`, `stay_replaces_booking_id` (`ON DELETE SET NULL`).
- Texte als `VARCHAR(n)`, Dauern über `DurationConverter`, siehe [architecture.md](architecture.md#persistence).

### Veraltete Vorschläge

- Beim Lesen vergleicht `advisor` `situation` mit der aktuellen Lage der Route. Weicht sie ab, kommt der Lauf mit `stale: true` zurück, es wird nichts neu berechnet.
- Der Nutzer kann dann einen neuen Lauf starten. Wählt er trotzdem, plant `RoutePlanning` neu und `ConsequenceMatcher` bewertet den neuen Weg, wie bei jeder Wahl.

## API

- `POST /api/v1/routes/{routeId}/alternative-proposals`: Body optional `consequenceId`, `incidentId` (wählt die Consequence des Incidents auf der Route), `goal` (`ARRIVE_ON_TIME`, `MINIMIZE_DELAY`, Standard `ARRIVE_ON_TIME`). Antwort `202` mit `runId`, läuft asynchron, weil mehrere Modellaufrufe nötig sind.
- `GET /api/v1/routes/{routeId}/alternative-proposals`: letzter Lauf der Route, auch der automatische. Ohne Lauf `200` mit `run: null`.
- `GET /api/v1/alternative-proposals/{runId}`: `status`, `stale`, `summary`, `proposals`, `consequenceId`, `incidentId`, `decision`, `chosenRank`, `decidedAt`.
- `GET /api/v1/trips/{tripId}/alternative-proposals`: letzter Lauf je Route des Trips (für den Banner).
- `POST /api/v1/alternative-proposals/{runId}/proposals/{rank}/acceptance`: übernimmt, Antwort `kind`, `plannedRouteIds`, `stayStopId`, `replacesBookingId`, `todosAdded`.
- `POST /api/v1/alternative-proposals/{runId}/dismissal`: verwirft alle, Antwort der Lauf.
- **Wählen:** über `acceptance`, siehe [Auswählen und Verwerfen](#auswählen-und-verwerfen). `PUT /routes/{routeId}/choice` ([recommendations.md](recommendations.md#auswahl-speichern)) bleibt für die Wahl von Hand.
- **Fehler** (`AdvisorExceptionHandler`): fremde oder unbekannte Route bzw. fremder Lauf `404` `TRIP_NOT_FOUND`, keine aktuelle `TOO_LATE`- oder `DELAYED`-Consequence `409` `NO_ACTIVE_CONSEQUENCE`, `consequenceId` gehört nicht zur Route oder Rang unbekannt bzw. Lauf noch nicht fertig `400` `INVALID_PROPOSAL_INPUT`, Vorschlag passt nicht mehr zur Reise `409` `PROPOSAL_NOT_APPLICABLE`.
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
- `advisor → bookings` nur im Adapter `LiteApiStayOptions` (`AlternativeSearch.offers`, `criteriaFor`, `BookingLogic.list`). Später auch für `LateArrivalNotices`.
- **`trips.logic`:** Eigentümer einer Route lesen, `RouteSnapshots.ownerOf(routeId)`. Der automatische Lauf kennt nur die `routeId`, `Suggestions` und `RouteSnapshots` verlangen `appUserId`.
- `TripTools` lassen sich nicht wiederverwenden: Sie lesen den Nutzer aus dem `SecurityContext`, den ein Hintergrundlauf nicht hat. Die Tools von `advisor` rufen dieselbe Logik auf.

## Konfiguration

```properties
nfv.advisor.max-turns=8
nfv.advisor.max-proposals=3
nfv.advisor.run-timeout=15m
nfv.advisor.auto-enabled=false
nfv.advisor.stopover-enabled=true
# Orte für Übernachtungen, Werte sind Standard
nfv.recommendations.stopover.latest-arrival=22:00
nfv.recommendations.stopover.max-off-route-meters=5000
nfv.recommendations.stopover.min-spacing-meters=15000
nfv.recommendations.stopover.default-areas=3
nfv.recommendations.stopover.max-areas=5
```

- `stopover-enabled`: Bietet dem Modell `find_overnight_areas` und `search_accommodations` an (das zweite nur mit LiteAPI-Key). Standard `true` seit `search_accommodations` da ist (2026-11-07). In den Test-Properties `false`, nur die Tests der Unterkunfts-Tools schalten es ein.
- `auto-enabled`: Schaltet nur die Läufe durch `RouteTooLate`, die des Nutzers gehen immer. **Standard vorerst `false` (Lukas, 2026-11-07):** Eingeschaltet gehen Routen und Zeiten ohne Zutun des Nutzers an OpenAI. Einschalten erst nach dem Lauf gegen die echte API und mit dem Hinweis in App und Datenschutzerklärung. Solange gibt es trotz `TOO_LATE` keinen Lauf ohne Anfrage des Nutzers.
- In den Test-Properties steht es ausdrücklich auf `false`, sonst würde jeder Test mit `TOO_LATE` einen Lauf im Hintergrund starten, auch nach einem Wechsel des Standards. Nur `RouteTooLateListenerTest` schaltet es ein.

## Reihenfolge der Umsetzung

1. Doku.
2. `recommendations`: `forConsequence`, nur betroffene Abschnitte, `DEPARTURE_SHIFT`, bereits unterwegs, ohne Modell testbar.
3. `assistant`: `ToolConversation`, gegen eine gemockte API getestet (`ToolConversationTest`). Vor dem ersten Betrieb einmal gegen die echte API prüfen, besonders das Wiederholen des verschlüsselten Reasonings.
4. `advisor`: Migration `V44`, Agent, Tools, API, Schnittstellen `StayOptions` und `LateArrivalNotices`. Erste Läufe gegen die echte API.
5. `incidents`: `RouteTooLate` in `assessRoute`, Listener und `auto_key` in `advisor`, `RouteSnapshots.ownerOf`, Schalter `nfv.advisor.auto-enabled` (umgesetzt, Standard vorerst `false`). Erst damit und eingeschaltet laufen automatische Läufe.

## Tests

- `ConsequenceAssessment`: `RouteTooLate` nur mit aktuellem `TOO_LATE`, nicht bei zurückgerollter Transaktion.
- Listener: gleicher `auto_key` nach Neuplanung startet keinen Lauf, geänderte Incidents oder Frist schon, `FAILED` gibt den Schlüssel frei.
- Agent mit gemockter `ToolConversation` (Tests rufen OpenAI nie auf): unbekannte Kennungen verworfen, Werte kommen aus dem Backend, `max-turns`, ohne Modell `COMPLETED_WITHOUT_MODEL`.
- API: `202`, fremde Route `404`, `DELAYED` startet nur durch den Nutzer, `NO_ACTIVE_CONSEQUENCE`, laufender Lauf wird wiederverwendet, `stale` nach geänderter Lage.
- Umgesetzt in `AlternativeProposalsTest` (Tools, Kontext, Werte aus dem Backend, ohne Modell, abgebrochene Läufe, Neustart, API), `ConsequenceMatchingTest` (`RouteTooLate`), `AutomaticProposalsTest` (`auto_key`, `runForSystem`, gleichzeitige Events, veraltetes Event, ausgeschaltet) und `RouteTooLateListenerTest` (Commit, Rollback).
- `find_overnight_areas`: `OvernightAreaFinderTest` (Lage, Hindernis, Grenze, Auswahl), `StopoversTest` (Wellington: Ort hinter der Sperrung, frühere Sperrung, Verzögerung, Abendgrenze, unterwegs, Umfahrung, Eingaben), `OvernightAreaToolTest` (Kennungen, Fehler an das Modell, Schalter), `OvernightToolOfferedTest` (Lauf: Sperrung ohne Frist und `TOO_LATE` ja, Verzögerung innerhalb der Frist nein).
- `search_accommodations`: `AccommodationSearchToolTest` (Ort und Ziel-Stop, Standardwerte, gebuchte Unterkunft, Umfahrung, Probleme und Fehler an das Modell, Schalter), `LiteApiStayOptionsTest` (harte Prüfung ohne Assistent, gebuchte Unterkunft, Fehler übersetzt), `StayProposalsTest` (Lauf mit `TARGET_STAY`, gespeichert und in der API).
- Auswählen und Verwerfen: `ProposalDecisionsTest` (Wellington: `STOPOVER` legt Stop an und teilt die Route, To-do für die spätere Ankunft; `TARGET_STAY`; `SHIFT_DEPARTURE`; `REROUTE` mit Wegpunkten; Verwerfen und Läufe des Trips; Fehler 404/400/409; Start mit `incidentId`). To-do-Liste in `BookingApiTest` und `AlternativeApiTest`, Frontend-Texte in `proposalText.test.ts` und `todos.test.ts`.

## Offen

- **Benachrichtigung:** Kanal und Schwellen offen ([architecture.md](architecture.md#offene-punkte)). Bis dahin sieht der Nutzer automatische Läufe nur in der App.
- **Geometrie der Vorschläge:** Nicht gespeichert, die Karte braucht aber eine Linie. Speichern oder beim Öffnen aus den Punkten neu routen, beim Frontend zu entscheiden.
- **Mehrere Replicas:** Der Schutz vor doppelten Läufen hält über den Unique-Constraint, das Zurücksetzen beim Start müsste dann auf Läufe der eigenen Instanz oder ein Zeitlimit umgestellt werden.
- **Aufräumen:** wie lange alte Läufe aufgehoben werden.
- **Übernachtung unterwegs:** Standard für `latest_arrival` (feste Uhrzeit, Check-in des Hotels oder Wahl des Nutzers). Werte für `max-off-route-meters` und `min-spacing-meters` an echten Routen prüfen. **Ohne `evaluate_stopover` (Lukas, 2026-11-07, das Tool entfällt):** Ein `STOPOVER` übernimmt vorerst die ungefähre Ankunft des Orts, den Weg dorthin plant `trips` neu (2026-11-07). Offen bleibt die genaue Ankunft am Hotel, der Weg des Vorschlags und die Uhrzeit der Weiterfahrt (fest 09:00).
- **Texte des Modells:** `summary` und `reason` sind Englisch und werden nicht gezeigt. Sollen sie sichtbar werden, braucht es Deutsch bzw. eine Sprache je Nutzer.
- **Gleichzeitige Anfragen:** Zwei `POST` im selben Moment können zwei Läufe starten, der zweite kostet nur Modellaufrufe, der letzte Lauf gewinnt in der Anzeige.
- **Ein Lauf je Route:** Folgen für spätere Routen des Trips stehen nur als Kontext im Modell, siehe [recommendations.md](recommendations.md#offen).- **Pläne aus mehreren Schritten** (Übernachtung + Weiterfahrt + Ziel umbuchen, ein Klick): Analyse und offene Entscheidungen in [incident-plans.md](incident-plans.md).
