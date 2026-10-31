# Domäne: Reise-Incidents

- **Ziel:** Nutzer hinterlegen Reiserouten und werden über Ereignisse informiert, die den Reiseverlauf beeinflussen (z. B. Straßensperrung).
- **Architektur** (Module, Abhängigkeiten, Persistenz, Altbestand): siehe [architecture.md](architecture.md).
- **Schema:** `V20__trips_and_incidents.sql`, Streckennetz ab `V21` (geplant).

## Bounded Contexts

| Modul | Entitäten |
|---|---|
| `com.vacantagent.trips` | `Trip`, `Stop`, `Route`, `RouteSection`, `Waypoint` (geplant) |
| `com.vacantagent.network` (Streckennetz) | `RoadSegment`, `ExtractVersion` (geplant, ersetzt `Road`, `Segment`) |
| `com.vacantagent.bookings` | `Booking` |
| `com.vacantagent.input` | `EventSource`, `Event` mit `TrafficEvent`, `WebhookEvent` |
| `com.vacantagent.incidents` | `Incident`, `Consequence` |

- **Referenzen zwischen Modulen nur per Id** (`appUserId`, `segmentId`, `stopId`, `routeId`, …), mit Fremdschlüssel in der Datenbank. JPA-Beziehungen (`@ManyToOne`) nur innerhalb eines Moduls.
- **Historisiert (Envers):** `Trip`, `Stop`, `Waypoint`, `Booking`, `Incident` (inkl. Segmente, `incident_segment_aud`). Nicht historisiert: Streckennetz, `Route`/`RouteSection` (geplant, wird ersetzt), `Consequence` (abgeleitet), Input (unveränderlich bis auf Verarbeitungsstatus).

## Trips

- `Trip` (gehört einem Nutzer) 1–* `Stop` (geordnet über `position`, optional Koordinaten, Ankunft, Abfahrt).
- `Route`: eine Instanz pro Trip, verbindet genau 2 Stops (`fromStop`, `toStop`, eindeutig je Paar). Fährt ein anderer Trip denselben Weg, hat er eine eigene Route.
- `Route` 1–1..* `RouteSection`, geordnet (`position` ab 0, eindeutig je Route). Jede Section zeigt auf genau ein Segment (`segmentId`) und hat die erwartete Durchfahrt als Zeitraum `expectedPassageStart`–`expectedPassageEnd`.
- `RouteLogic.replaceSections(routeId, sections)` ersetzt alle Sections einer Route und veröffentlicht `RouteChanged`.
- **`Waypoint`** (geplant): Punkt, über den der Nutzer zwischen zwei Stops fahren will, gehört zu einer Route, geordnet, ohne Aufenthalt. Gespeichert wird die Koordinate (Absicht), das gesnappte Segment wird bei jeder Planung neu bestimmt. Historisiert.
- **Planen** (geplant): `RoutePlanning.plan` holt Stops, Waypoints und Vorlieben, ruft `network.Routing` (ohne Incidents, Umfahrungen sind Empfehlungen), berechnet die Durchfahrt aus Abfahrt und Dauer und übergibt `PlannedSection`s an `RouteLogic.replaceSections`.
- **Anteile** (geplant): `RouteSection` und `PlannedSection` bekommen `fromFraction`/`toFraction`, welcher Teil des Segments befahren wird (nur am ersten und letzten Abschnitt nicht 0–1). Verhindert Fehlalarme an langen Segmenten.

## Streckennetz

- Ein `RoadSegment` ist eine befahrbare Richtung einer Straße von Kreuzung zu Kreuzung mit stabiler Id, erzeugt aus OpenStreetMap. Details zu Schlüssel, Import und Aktualisierung in [network.md](network.md).
- Bis zur Umstellung gelten noch `Road` (z. B. `A6`, eindeutige Nummer) 1–* `Segment` (von Anschlussstelle zu Anschlussstelle, je Richtung, Geometrie als GeoJSON-Text).

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
- **Neuplanung:** wenn sich Abfahrt, Stops oder Waypoints ändern und nach `NetworkRefreshed` (geplant), Umfahrung bei `BLOCKED` über `recommendations`.
- **Verzögerung fortschreiben:** Eine Verzögerung auf einem Abschnitt verschiebt die erwartete Durchfahrt aller folgenden Abschnitte; derzeit nicht berücksichtigt.
- **Ende eines Incidents:** abgelaufene Consequences bleiben derzeit stehen; aufräumen oder als erledigt markieren.
- **Impact und Delay genauer:** derzeit nur aus dem Typ des Incidents abgeleitet und gleich für alle Routen.
- **Bookings:** Bezug zu Incidents (z. B. Check-in verpasst durch Verspätung) und Herkunft der Daten (manuell, Import).
- **Benachrichtigung:** wer wann worüber informiert wird, Schwellen (z. B. erst ab 15 min Verzögerung).
- **Trips teilen:** derzeit gehört ein Trip genau einem Nutzer.
