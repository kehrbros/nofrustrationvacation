# Vorschläge durch den Agenten (`advisor`)

- **Status:** Entwurf, beschlossen 2026-11-06 (Lukas), Code noch nicht umgesetzt.
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

- `ConsequenceAssessment.assessRoute` ist die einzige Stelle, die Kategorien schreibt. Sie läuft in der Transaktion der Änderung und veröffentlicht dort `RouteTooLate(routeId, incidentIds, deadline)`, wenn die Route mindestens eine `TOO_LATE`-Consequence hat, deren Durchfahrt nicht vorbei ist (`Consequence.isOver`).
- `advisor` hört mit `@TransactionalEventListener(AFTER_COMMIT)` und rechnet auf einem virtuellen Thread, wie `TrafficEventProcessor.afterPoll`. Wird die Transaktion zurückgerollt, startet kein Lauf.
- **Warum ein Vergleich vorher/nachher nicht reicht:** Eine Neuplanung löscht die Consequences mit den Sections, eine weiterhin verspätete Route sähe neu aus. `NetworkRefreshed` könnte das für viele Routen zugleich auslösen. Der Verkehrsfeed committet zudem je Event, also mehrere Bewertungen kurz nacheinander.
- **Keine doppelten Läufe:** `auto_key` = Hash aus den `TOO_LATE`-Incidents (sortiert) und der Frist, eindeutig je Route. Der Lauf wird zuerst angelegt, ein zweiter mit gleichem Schlüssel scheitert am Unique-Constraint und entfällt. Damit bleibt der Schutz über eine Neuplanung hinweg bestehen.
- Scheitert ein Lauf (`FAILED`), wird `auto_key` geleert, die nächste Bewertung darf neu starten.
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
      boolean appliesTo(AdvisorRun run);
      JsonNode call(AdvisorRun run, JsonNode arguments);
  }
  ```

- **Fest je Lauf:** `appUserId`, `tripId` und `routeId` setzt das Backend. Sie sind nie Parameter des Modells.
- **Kennungen:** Tools liefern Kennungen, die nur im Lauf gelten (`route_option_id`, `shift_id`), die Daten dahinter hält der Lauf im Speicher. `submit_proposals` darf nur Kennungen aus diesem Lauf nennen, unbekannte verwirft das Backend, wie `AlternativeRanking` fremde Hotel-Ids.
- **Keine Tools zum Ändern:** Route übernehmen, Stops ändern, reservieren, zahlen und stornieren bleiben hinter der Bestätigung des Nutzers.

### Erste Stufe

| Tool | Parameter | Liefert | Nutzt |
|---|---|---|---|
| `find_alternative_routes` | `count` (1–5), `depart_at` (optional) | je Option `route_option_id`, `via`, Ankunft, Abstand zur Referenz, Puffer zur Frist, Konflikte, Anteil gemeinsamer Strecke, ohne Geometrie | `Suggestions.forConsequence` |
| `find_departure_shifts` | keine, immer auf der geplanten Route | je Richtung höchstens eine `shift_id`, Abfahrt, Ankunft, Konflikte, `meets_deadline` | `Suggestions.forConsequence` mit `DEPARTURE_SHIFT` |
| `submit_proposals` | `summary`, `proposals[]` mit `kind`, `rank`, `reason` und den Kennungen | beendet den Lauf | `advisor` |

- Waypoints des Nutzers bleiben, alle aktiven Incidents der Route werden gesperrt bzw. verzögert. Das entscheidet das Szenario, nicht das Modell ([recommendations.md](recommendations.md#szenario-recommendationslogic)). Deshalb keine Parameter für Waypoints oder Segmente.
- `find_departure_shifts` entfällt, wenn der Nutzer schon unterwegs ist.
- **`kind`:** `REROUTE`, `SHIFT_DEPARTURE`, `KEEP_ROUTE`. `KEEP_ROUTE` ist kein Generator in `recommendations`: Strecke und Verzögerung aus `SuggestionResult.reference`, bei einer Sperrung Wartezeit und Ankunft aus der Consequence (die Referenz enthält das Warten nicht).
- **Grenze der ersten Stufe:** Gibt es bei einer Sperrung weder Umfahrung noch sinnvolle Abfahrt, kann der Lauf nur `KEEP_ROUTE` mit Verzögerung melden oder keine brauchbare Option. Eine Übernachtung unterwegs kommt erst mit den Unterkunfts-Tools.

### Später: Unterkünfte und Nachricht an die Unterkunft

Noch nicht umgesetzt. Vorerst gibt es nur die Schnittstellen, ohne Implementierung und ohne Tool. Das Modell bekommt diese Tools also nicht angeboten, solange sie fehlen.

```java
/** Unterkünfte, wenn die Route nicht rechtzeitig fahrbar ist. */
public interface StayOptions {
    List<OvernightArea> areasOnRoute(long appUserId, long routeId, OffsetDateTime latestArrival, int maxResults);
    List<StayOffer> search(long appUserId, StaySearch search);

    record OvernightArea(String name, double latitude, double longitude, OffsetDateTime arrivalAt,
                         double remainingMeters) {}
    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      @Nullable BigDecimal maxPrice) {}
    record StayOffer(String hotelId, String offerId, String name, int distanceMeters, BigDecimal price,
                     String currency, boolean refundable, @Nullable OffsetDateTime freeCancellationUntil) {}
}

/** Entwurf einer Nachricht an die Unterkunft bei später Ankunft, senden muss der Nutzer. */
public interface LateArrivalNotices {
    Optional<LateArrivalNotice> draft(long appUserId, long stopId, OffsetDateTime expectedArrival);

    record LateArrivalNotice(long bookingId, String accommodationName, @Nullable String email,
                             @Nullable String phone, String message) {}
}
```

- Feldtypen beim Umsetzen gegen `bookings` prüfen. Die Implementierung liegt später als Adapter in `advisor`, damit `bookings` das Modul nicht kennt.
- **Geplante Tools:**
  - `find_overnight_areas(latest_arrival?, route_option_id?, max_results)`: Orte vor dem Hindernis, die rechtzeitig erreichbar sind. Nötig, weil `AlternativeSearch.criteriaFor` um die gebuchte Unterkunft sucht, also genau dort, wo der Nutzer nicht hinkommt.
  - `search_accommodations(area_id | TARGET_STOP, radius_meters, check_in, check_out, max_price?)`: braucht eine Variante von `AlternativeSearch` ohne `bookingId` und ohne `AlternativeRanking`, sonst ruft ein Tool des Modells wieder das Modell auf. Harte Prüfung und `SearchLimiter` bleiben.
  - `evaluate_stopover(area_id, continue_at)`: Weiterfahrt am nächsten Morgen.
  - `draft_late_arrival_notice`: oft die günstigste Lösung, wenn nur der Check-in zu spät ist. Kontaktdaten ergänzt das Backend, sie gehen nicht an das Modell.
- **Neue `kind`:** `STOPOVER`, `NOTIFY_ACCOMMODATION`, kommen mit den Tools (Spalte `VARCHAR`, keine Migration nötig). Ebenso das Ziel `STAY_OVERNIGHT` und die Buchungen im Kontext des Modells.
- Es gilt [accommodation-alternatives.md](accommodation-alternatives.md#rollen-assistant-und-liteapi): Das Modell löst keine Buchung aus.

## Kontext des Modells

- Steht von Anfang an in der Anfrage, das spart Lese-Tools und Runden: aktuelle Zeit, Route (Start, Ziel, Waypoints, Planankunft, Frist, Puffer), Consequences nach [Rangfolge](consequence-assessment.md#rangfolge), folgende Stops (Name, Ankunft, Abfahrt), geschätzte Position, wenn der Nutzer unterwegs ist.
- **Tools je Lage:** Bei `DELAYED` und `TOO_LATE` zunächst dieselben, mit den Unterkunfts-Tools nur noch bei `TOO_LATE` oder Ziel `STAY_OVERNIGHT`.
- **Sprache:** `summary` und `reason` schreibt das Modell auf Englisch (Lukas, 2026-11-07). Die Sprache steckt damit im gespeicherten Text, andere Sprachen brauchen später eine Einstellung je Nutzer.
- **Fremde Texte:** Beschreibungen aus Verkehrsmeldungen stammen von Dritten. Sie werden als Daten gekennzeichnet. Auch deshalb darf kein Tool die Reise ändern.

## Datenmodell

- Eigene Tabellen, Migration `V43`, abgeleitet und nicht historisiert. `@ManyToOne` nur innerhalb des Moduls, `route_id` als Id mit Fremdschlüssel. Wird die Route gelöscht, löscht die Datenbank die Läufe mit (`ON DELETE CASCADE`).
- `AdvisorRun` (`advisor_run`):
  - `route_id`, `consequence_id` (optional, vom Nutzer gewählt, `ON DELETE SET NULL`)
  - `trigger` (`AUTO`, `USER`, Spalte `run_trigger`), `goal` (optional)
  - `status`: `PENDING`, `RUNNING`, `COMPLETED`, `COMPLETED_WITHOUT_MODEL`, `FAILED`
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
- `GET /api/v1/routes/{routeId}/alternative-proposals`: letzter Lauf der Route, auch der automatische. Ohne Lauf `200` mit leerem Ergebnis.
- `GET /api/v1/alternative-proposals/{runId}`: `status`, `stale`, `summary`, `proposals`.
- **Wählen:** `REROUTE` über `PUT /routes/{routeId}/choice` mit den Punkten des Vorschlags, `SHIFT_DEPARTURE` über dasselbe `choice` mit `departureAt` ([recommendations.md](recommendations.md#auswahl-speichern)). `KEEP_ROUTE` ändert nichts.
- **Fehler** (`AdvisorExceptionHandler`): fremde oder unbekannte Route bzw. fremder Lauf `404` `TRIP_NOT_FOUND`, keine aktuelle `TOO_LATE`- oder `DELAYED`-Consequence `409` `NO_ACTIVE_CONSEQUENCE`, `consequenceId` gehört nicht zur Route `400` `INVALID_PROPOSAL_INPUT`.
- Geometrie für die Karte: siehe [Offen](#offen).

## Modell, Kontingent, Ausfall

- **`assistant.logic.ToolConversation`** (umgesetzt, kennt keine Fachmodule): Schleife über die OpenAI Responses API mit Function Calling und `store: false`. Der Aufrufer bringt Anweisung, Eingabe und Tools mit, `submit_proposals` ist das letzte Tool (`finish`). Ein Tool-Aufruf je Runde. `StructuredExtraction` bleibt unverändert.
- **Fehler in Tools:** Ungültige Eingaben meldet das Tool als `ToolInputException`, das Modell bekommt die Meldung und kann es neu versuchen. Jede andere Ausnahme (z. B. `RoutingUnavailableException`) beendet das Gespräch und geht an `advisor`.
- **Kontingent:** Jede Runde ist ein Modellaufruf und zählt gegen das Limit. `AUTO` nutzt `system-requests-per-hour`, `USER` `requests-per-hour` des Nutzers.
- **Grenzen:** `max-turns` je Lauf. In der letzten Runde muss das Modell `submit_proposals` aufrufen (`tool_choice`), es schließt also mit dem bisher Gefundenen ab. Tut es das trotzdem nicht, gilt der Lauf als ohne Modell (siehe unten).
- **Ohne Modell** (kein Key, Limit erreicht, Fehler, kein Abschluss): die besten Optionen von `Suggestions.forConsequence` ohne Begründung, Status `COMPLETED_WITHOUT_MODEL`. Vorschläge dürfen wie bei der Suche nach Unterkünften nicht am Modell scheitern.
- **`RoutingUnavailableException`, sonstige Fehler:** `FAILED`, siehe [Auslöser](#automatisch-nur-too_late).
- **Neustart:** Läufe in `PENDING` oder `RUNNING` werden beim Start `FAILED`, damit sie `auto_key` freigeben.
- **Datenschutz:** An OpenAI gehen Namen und Zeiten von Stops und Waypoints, Straßen, Incident-Texte und die berechneten Optionen, also Ort und Zeit der Reise. Keine Namen oder Kontaktdaten der Reisenden, keine Buchungsnummern. Das `AUTO`-Verhalten braucht einen Hinweis in App und Datenschutzerklärung.

## Abhängigkeiten

- `advisor → recommendations`, `→ incidents`, `→ trips`, `→ assistant`. Kein anderes Modul kennt `advisor`, kein Zyklus. Rückwärts nur über das Event `RouteTooLate` aus `incidents.logic`.
- Vorerst kein `advisor → bookings`, das kommt mit dem Adapter für `StayOptions` und `LateArrivalNotices`.
- **Neu in `trips.logic`:** Eigentümer einer Route lesen, `RouteSnapshots.ownerOf(routeId)`. Der automatische Lauf kennt nur die `routeId`, `Suggestions` und `RouteSnapshots` verlangen `appUserId`.
- `TripTools` lassen sich nicht wiederverwenden: Sie lesen den Nutzer aus dem `SecurityContext`, den ein Hintergrundlauf nicht hat. Die Tools von `advisor` rufen dieselbe Logik auf.

## Konfiguration

```properties
nfv.advisor.auto-enabled=true
nfv.advisor.max-turns=8
nfv.advisor.max-proposals=3
nfv.advisor.run-timeout=15m
```

## Reihenfolge der Umsetzung

1. Doku.
2. `recommendations`: `forConsequence`, nur betroffene Abschnitte, `DEPARTURE_SHIFT`, bereits unterwegs, ohne Modell testbar.
3. `assistant`: `ToolConversation`, gegen eine gemockte API getestet (`ToolConversationTest`). Vor dem ersten Betrieb einmal gegen die echte API prüfen, besonders das Wiederholen des verschlüsselten Reasonings.
4. `advisor`: Migration `V43`, Agent, Tools, API, Schnittstellen `StayOptions` und `LateArrivalNotices`.
5. `incidents`: `RouteTooLate` in `assessRoute`, erst damit laufen automatische Läufe.

## Tests

- `ConsequenceAssessment`: `RouteTooLate` nur mit aktuellem `TOO_LATE`, nicht bei zurückgerollter Transaktion.
- Listener: gleicher `auto_key` nach Neuplanung startet keinen Lauf, geänderte Incidents oder Frist schon, `FAILED` gibt den Schlüssel frei.
- Agent mit gemockter `ToolConversation` (Tests rufen OpenAI nie auf): unbekannte Kennungen verworfen, Werte kommen aus dem Backend, `max-turns`, ohne Modell `COMPLETED_WITHOUT_MODEL`.
- API: `202`, fremde Route `404`, `DELAYED` startet nur durch den Nutzer, `NO_ACTIVE_CONSEQUENCE`, laufender Lauf wird wiederverwendet, `stale` nach geänderter Lage.

## Offen

- **Benachrichtigung:** Kanal und Schwellen offen ([architecture.md](architecture.md#offene-punkte)). Bis dahin sieht der Nutzer automatische Läufe nur in der App.
- **Geometrie der Vorschläge:** Nicht gespeichert, die Karte braucht aber eine Linie. Speichern oder beim Öffnen aus den Punkten neu routen, beim Frontend zu entscheiden.
- **Frontend:** Anzeige der Vorschläge im `IncidentSheet`, Hinweis `stale`, Knopf für den Lauf bei `DELAYED`.
- **Mehrere Replicas:** Der Schutz vor doppelten Läufen hält über den Unique-Constraint, das Zurücksetzen beim Start müsste dann auf Läufe der eigenen Instanz oder ein Zeitlimit umgestellt werden.
- **Aufräumen:** wie lange alte Läufe aufgehoben werden.
- **Ein Lauf je Route:** Folgen für spätere Routen des Trips stehen nur als Kontext im Modell, siehe [recommendations.md](recommendations.md#offen).