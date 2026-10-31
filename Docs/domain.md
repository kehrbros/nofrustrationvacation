# Domäne: Reise-Incidents

- **Ziel:** Nutzer hinterlegen Reiserouten und werden über Ereignisse informiert, die den Reiseverlauf beeinflussen (z. B. Straßensperrung).
- **Architektur** (Module, Abhängigkeiten, Persistenz, Altbestand): siehe [architecture.md](architecture.md).
- **Schema:** `V20__trips_and_incidents.sql`, Titelfoto `V21__trip_cover_image.sql`, Reisezeitraum und Offsets `V22__trip_period_and_offsets.sql`, Streckennetz `V23__road_network.sql`, Routenplanung `V24__route_planning.sql`.

## Bounded Contexts

| Modul | Entitäten |
|---|---|
| `com.vacantagent.trips` | `Trip`, `TripCoverImage`, `Stop`, `Route`, `RouteSection`, `Waypoint`  |
| `com.vacantagent.network` (Streckennetz) | `RoadSegment`, `ExtractVersion` |
| `com.vacantagent.bookings` | `Booking` |
| `com.vacantagent.input` | `EventSource`, `Event` mit `TrafficEvent`, `WebhookEvent` |
| `com.vacantagent.incidents` | `Incident`, `Consequence` |

- **Referenzen zwischen Modulen nur per Id** (`appUserId`, `segmentId`, `stopId`, `routeId`, …), mit Fremdschlüssel in der Datenbank. JPA-Beziehungen (`@ManyToOne`) nur innerhalb eines Moduls.
- **Historisiert (Envers):** `Trip`, `Stop`, `Waypoint`, ``Booking`, `Incident` (inkl. Segmente, `incident_segment_aud`). Nicht historisiert: `TripCoverImage` (Binärdaten, ersetztes Bild entfällt), Streckennetz, `Route`/`RouteSection` (geplant, wird ersetzt), `Consequence` (abgeleitet), Input (unveränderlich bis auf Verarbeitungsstatus).

## Trips

- `Trip` (gehört einem Nutzer) 1–* `Stop` (geordnet über `position`, optional Koordinaten, Ankunft, Abfahrt).
- `Trip` hat einen Reisezeitraum `startAt`–`endAt` (Zeitpunkte als `OffsetDateTime`, beide inklusive, Pflicht beim Anlegen, `endAt` ≥ `startAt`, auch als Check-Constraint). Gespeichert statt aus den Stops berechnet, damit eine Reise schon ohne Stops einen Zeitraum hat. `reschedule(…)` ändert ihn.
- Das Frontend schickt die Zeitpunkte mit dem Offset der Zeitzone des Systems (Reise vom 12. bis 26. Juli: `2027-07-12T00:00:00+02:00` bis `2027-07-26T23:59:59+02:00`). Der Offset bleibt erhalten, siehe Zeiten in [architecture.md](architecture.md#persistence).
- `Trip.phaseAt(zeitpunkt)` liefert `UPCOMING`, `CURRENT` oder `PAST` (Vergleich der Zeitpunkte, Offset egal) und steuert die Reiseliste: aktuelle Reise oben, darunter Tabs „Anstehende“ (inkl. weiterer aktueller Reisen) und „Vergangene“, jeweils aufsteigend nach `startAt`.
- Auch Ankunft und Abfahrt eines `Stop` behalten ihren Offset.
- Bestehende Trips ohne Zeitraum erhielten bei der Migration `createdAt` (UTC) als Start und Ende.
- Ein neuer Stop wird ans Ende der Reise gehängt; ab dem zweiten Stop entsteht automatisch eine `Route` vom vorigen Stop (noch ohne Sections, bis ein Routing-Dienst plant).
- `Trip` 1–0..1 `TripCoverImage` (Titelfoto, `tripId` eindeutig): Herkunft `GENERATED` (im Backend erzeugt, z. B. per Bildmodell) oder `UPLOADED` (vom Nutzer), `contentType`, Bilddaten (`content`, `BYTEA`, lazy geladen), `createdAt`. Eigene Tabelle, damit Reiselisten die Bilddaten nicht laden; wird mit dem Trip gelöscht (`ON DELETE CASCADE`). `replace(…)` tauscht das Bild, z. B. Upload statt generiertem Bild.
- `Route`: eine Instanz pro Trip, verbindet genau 2 Stops (`fromStop`, `toStop`, eindeutig je Paar). Fährt ein anderer Trip denselben Weg, hat er eine eigene Route.
- `Route` 1–1..* `RouteSection`, geordnet (`position` ab 0, eindeutig je Route). Jede Section zeigt auf genau ein Segment (`segmentId`) und hat die erwartete Durchfahrt als Zeitraum `expectedPassageStart`–`expectedPassageEnd`.
- `RouteLogic.replaceSections(routeId, sections)` ersetzt alle Sections einer Route und veröffentlicht `RouteChanged`.
- **`Waypoint`:** Punkt, über den der Nutzer zwischen zwei Stops fahren will, gehört zu einer Route, geordnet (`position`, eindeutig je Route), ohne Aufenthalt. Koordinate Pflicht, optional Name und Straßenhinweis (z. B. `SH 73`). Gespeichert wird die Koordinate (Absicht), das gesnappte Segment wird bei jeder Planung neu bestimmt. Historisiert, die Referenz auf die Route nicht (Route ist nicht historisiert).
- **Planen:** `RoutePlanning.plan(routeId)` holt Stops und Waypoints, ruft `network.Routing` (ohne Incidents, Umfahrungen sind Empfehlungen), berechnet die Durchfahrt aus der Abfahrt am Start-Stop plus den Dauern der Segmente davor und übergibt `PlannedSection`s an `RouteLogic.replaceSections`. Routing läuft außerhalb der Transaktion.
  - Ergebnis `PlanResult`: `PLANNED`, `MISSING_COORDINATES` (ein Stop ohne Koordinaten), `MISSING_DEPARTURE` (Start-Stop ohne Abfahrt, keine Zeit geraten), `NO_ROUTE` (mit Grund). Außer bei `PLANNED` bleiben die Sections unverändert.
  - `RoutingUnavailableException` (Streckennetz noch nicht geladen) wird weitergereicht, später erneut versuchen.
  - Die Ankunft am Ziel-Stop bleibt, was der Nutzer eingetragen hat. Abgleich mit der geplanten Ankunft ist offen.
- **Anteile:** `RouteSection` und `PlannedSection` haben `fromFraction`/`toFraction`, welcher Teil des Segments befahren wird (nur am ersten und letzten Abschnitt nicht 0–1). Verhindert Fehlalarme an langen Segmenten, sobald auch `incident_segment` Anteile hat.

## Streckennetz

- Ein `RoadSegment` ist eine befahrbare Richtung einer Straße von Kreuzung zu Kreuzung mit stabiler Id, erzeugt aus OpenStreetMap. Details zu Schlüssel, Import und Aktualisierung in [network.md](network.md).
## Bookings

- `Stop` 1–* `Booking` (`stopId`): Typ (`ACCOMMODATION`, `TRANSPORT`, `ACTIVITY`, `OTHER`), Titel, Anbieter, Buchungsnummer, Zeitraum.

## Input

- `EventSource` (eindeutiger Name, Typ `TRAFFIC_FEED` oder `WEBHOOK`, aktiv ja/nein) 1–* `Event`.
- `Event` (Tabelle `input_event`, eine Tabelle für alle Subtypen, Spalte `kind`): Rohdaten `payload`, `externalId` (eindeutig je Quelle, erkennt doppelte Zustellung), `receivedAt`, `processedAt`.
  - `TrafficEvent` (`TRAFFIC`): aus dem Payload gelesene Straße, Beschreibung, Zeitraum.
  - `WebhookEvent` (`WEBHOOK`): Signatur geprüft ja/nein.
- Ein Event löst 0..1 Incident aus (`incidentId`). Mehrere Events (z. B. Aktualisierungen einer Sperrung) können zum selben Incident führen. Die Verarbeitung Event → Incident fehlt noch.

## Incidents

- `Incident`: Typ (`CLOSURE`, `CONGESTION`, `ROADWORKS`, `ACCIDENT`, `OTHER`), Beschreibung, gültig `validFrom`–`validTo` (`validTo` leer = bis auf Weiteres), erwartete Verzögerung. Verortet an 1..* Segmenten (`incident_segment`).
- `Incident` 1–* `Consequence`. Jede Consequence betrifft genau eine Route (höchstens eine je Incident und Route) und referenziert die erste betroffene `RouteSection`. Dazu `impact` (`CLOSURE` → `BLOCKED`, sonst `DELAYED`) und `delay` (= erwartete Verzögerung des Incidents).
- Consequences sind abgeleitet und werden nie von Nutzern bearbeitet. Werden Route oder Section gelöscht, löscht die Datenbank die Consequence mit (`ON DELETE CASCADE`).

## Zuordnungslogik (`ConsequenceMatcher`)

1. **Incident neu oder geändert** (`IncidentLogic.create`/`change`): Segmente des Incidents → über den Index Segment → RouteSection (`ix_route_section_segment`) die Sections darauf, deren erwartete Durchfahrt die Gültigkeit überschneidet → je Route eine Consequence.
2. **Route neu oder geändert** (`RouteChanged`, synchron in derselben Transaktion): Segmente der Route → aktive Incidents darauf (`validTo` leer oder nicht vorbei) → dieselbe Prüfung auf Überschneidung.
- Überschneidung: `passageEnd >= validFrom` und (`validTo` leer oder `passageStart <= validTo`).
- Bestehende Consequences werden aktualisiert (Id bleibt, für spätere Benachrichtigungen), nicht mehr zutreffende gelöscht.
- Tests: `ConsequenceMatchingTest`.

## Offen

- **Incidents an Unterkünften** (z. B. „Unterkunft nicht erreichbar“): bewusst ausgelassen, werden später betrachtet. Dann betrifft ein Incident evtl. einen Stop oder eine Booking statt Segmenten.
- **Event → Incident:** Segmente aus Straße und Ortsangabe bestimmen, mehrere Events demselben Incident zuordnen (Aktualisierung, Aufhebung), Typ und Gültigkeit aus dem Payload ableiten.
- **Neuplanung:** wer `plan` aufruft, fehlt noch: keine API für Trips, Stops und Waypoints. Später bei Änderungen an Abfahrt, Stops oder Waypoints und nach `NetworkRefreshed`. Umfahrung bei `BLOCKED` über `recommendations`.
- **Verzögerung fortschreiben:** Eine Verzögerung auf einem Abschnitt verschiebt die erwartete Durchfahrt aller folgenden Abschnitte; derzeit nicht berücksichtigt.
- **Ende eines Incidents:** abgelaufene Consequences bleiben derzeit stehen; aufräumen oder als erledigt markieren.
- **Impact und Delay genauer:** derzeit nur aus dem Typ des Incidents abgeleitet und gleich für alle Routen.
- **Bookings:** Bezug zu Incidents (z. B. Check-in verpasst durch Verspätung) und Herkunft der Daten (manuell, Import).
- **Benachrichtigung:** wer wann worüber informiert wird, Schwellen (z. B. erst ab 15 min Verzögerung).
- **Trips teilen:** derzeit gehört ein Trip genau einem Nutzer.
- **Stops einfügen und umsortieren:** derzeit nur Anhängen am Ende (`Stop.moveTo` existiert, API fehlt).
- **Zeitraum und Stops:** ob Ankunft/Abfahrt der Stops innerhalb des Reisezeitraums liegen müssen, ist noch nicht geprüft.
