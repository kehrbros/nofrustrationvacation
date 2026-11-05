# Domäne: Reise-Incidents

- **Ziel:** Nutzer hinterlegen Reiserouten und werden über Ereignisse informiert, die den Reiseverlauf beeinflussen (z. B. Straßensperrung).
- **Architektur** (Module, Abhängigkeiten, Persistenz, Altbestand): siehe [architecture.md](architecture.md).
- **Schema:** `V20__trips_and_incidents.sql`, Titelfoto `V21__trip_cover_image.sql`, Reisezeitraum und Offsets `V22__trip_period_and_offsets.sql`, Streckennetz `V23__road_network.sql`, Routenplanung `V24__route_planning.sql`, Planungsstatus `V25__route_plan_status.sql`, Buchungen von Unterkünften `V26__booking_accommodation.sql`, Punkt der Unterkunft für räumliche Abfragen `V28__accommodation_location.sql`, Streckenvorschläge und `waypoint.origin` `V29__recommendations.sql`, Buchung alternativer Unterkünfte `V30__booking_reservation.sql`, Reisende `V31`, Streckenvorschläge nicht mehr gespeichert `V32__drop_suggestion_sets.sql`. Nächste freie Migration: `V33`.

## Bounded Contexts

| Modul | Entitäten |
|---|---|
| `com.nfv.trips` | `Trip`, `TripCoverImage`, `Stop`, `Route`, `RouteSection`, `Waypoint`  |
| `com.nfv.network` (Streckennetz) | `RoadSegment`, `ExtractVersion` |
| `com.nfv.bookings` | `Booking`, `AccommodationDetails`, `BookingReservation` |
| `com.nfv.input` | `EventSource`, `Event` mit `TrafficEvent`, `WebhookEvent` |
| `com.nfv.incidents` | `Incident`, `Consequence` |
| `com.nfv.recommendations` | keine (Vorschläge werden flüchtig berechnet, gespeichert wird nur die gewählte Strecke als `Waypoint`s) |

- **Referenzen zwischen Modulen nur per Id** (`appUserId`, `segmentId`, `stopId`, `routeId`, …), mit Fremdschlüssel in der Datenbank. JPA-Beziehungen (`@ManyToOne`) nur innerhalb eines Moduls.
- **Historisiert (Envers):** `Trip`, `Stop`, `Waypoint`, `Booking`, `AccommodationDetails`, `Incident` (inkl. Segmente, `incident_segment_aud`). Nicht historisiert: `TripCoverImage` (Binärdaten, ersetztes Bild entfällt), Streckennetz, `Route`/`RouteSection` (geplant, wird ersetzt), `Consequence` (abgeleitet), Input (unveränderlich bis auf Verarbeitungsstatus).

## Trips

- `Trip` (gehört einem Nutzer) 1–* `Stop` (geordnet über `position`, optional Koordinaten, Ankunft, Abfahrt).
- `Trip` hat einen Reisezeitraum `startAt`–`endAt` (Zeitpunkte als `OffsetDateTime`, beide inklusive, Pflicht beim Anlegen, `endAt` ≥ `startAt`, auch als Check-Constraint). Gespeichert statt aus den Stops berechnet, damit eine Reise schon ohne Stops einen Zeitraum hat. `reschedule(…)` ändert ihn.
- Das Frontend schickt die Zeitpunkte mit dem Offset der Zeitzone des Systems (Reise vom 12. bis 26. Juli: `2027-07-12T00:00:00+02:00` bis `2027-07-26T23:59:59+02:00`). Der Offset bleibt erhalten, siehe Zeiten in [architecture.md](architecture.md#persistence).
- `Trip.travelers`: Zahl der Reisenden (optional, 1–99, Check-Constraint, `V31`), wird beim Anlegen einer Buchung als Gästezahl vorgeschlagen (ohne Wert: Gäste der letzten Buchung der Reise). `changeTravelers(…)` ändert sie.
- `Trip.phaseAt(zeitpunkt)` liefert `UPCOMING`, `CURRENT` oder `PAST` (Vergleich der Zeitpunkte, Offset egal) und steuert die Reiseliste: aktuelle Reise oben, darunter Tabs „Anstehende“ (inkl. weiterer aktueller Reisen) und „Vergangene“, jeweils aufsteigend nach `startAt`.
- Auch Ankunft und Abfahrt eines `Stop` behalten ihren Offset.
- Bestehende Trips ohne Zeitraum erhielten bei der Migration `createdAt` (UTC) als Start und Ende.
- **Stops ändern** (`TripLogic`): einfügen (an einer Position oder am Ende), ändern, verschieben, löschen. Danach werden die Stops neu durchnummeriert und die Routen abgeglichen: Routen zwischen Nachbarn, die Nachbarn bleiben, bleiben bestehen (mit Waypoints und Sections), fehlende werden angelegt, übrige mit ihren Waypoints gelöscht (Waypoints wandern nicht auf die neue Route).
- Ein Trip wird mit Routen, Waypoints und Stops gelöscht, Titelfoto und Consequences löscht die Datenbank mit.
- `Trip` 1–0..1 `TripCoverImage` (Titelfoto, `tripId` eindeutig): Herkunft `GENERATED` (im Backend erzeugt, z. B. per Bildmodell) oder `UPLOADED` (vom Nutzer), `contentType`, Bilddaten (`content`, `BYTEA`, lazy geladen), `createdAt`. Eigene Tabelle, damit Reiselisten die Bilddaten nicht laden; wird mit dem Trip gelöscht (`ON DELETE CASCADE`). `replace(…)` tauscht das Bild, z. B. Upload statt generiertem Bild.
- `Route`: eine Instanz pro Trip, verbindet genau 2 Stops (`fromStop`, `toStop`, eindeutig je Paar). Fährt ein anderer Trip denselben Weg, hat er eine eigene Route.
- `Route` 1–1..* `RouteSection`, geordnet (`position` ab 0, eindeutig je Route). Jede Section zeigt auf genau ein Segment (`segmentId`) und hat die erwartete Durchfahrt als Zeitraum `expectedPassageStart`–`expectedPassageEnd`.
- `RouteLogic.replaceSections(routeId, sections)` ersetzt alle Sections einer Route und veröffentlicht `RouteChanged`.
- **`Waypoint`:** Punkt, über den der Nutzer zwischen zwei Stops fahren will, gehört zu einer Route, geordnet (`position`, eindeutig je Route), ohne Aufenthalt. Koordinate Pflicht, optional Name und Straßenhinweis (z. B. `SH 73`). Gespeichert wird die Koordinate (Absicht), das gesnappte Segment wird bei jeder Planung neu bestimmt. Historisiert, die Referenz auf die Route nicht (Route ist nicht historisiert).
  - `origin`: `USER` (vom Nutzer) oder `SUGGESTION` (beim Wählen eines Streckenvorschlags erzeugt), beim Anlegen gesetzt und danach unverändert, auch wenn der Nutzer den Waypoint ändert. REST und MCP legen immer `USER` an.
- Waypoints einer Route werden als geordnete Liste ersetzt: mit Id geändert, ohne Id angelegt, fehlende gelöscht.
- **Planen:** `RoutePlanning.plan(routeId)` holt Stops und Waypoints, ruft `network.Routing` (ohne Incidents, Umfahrungen sind Empfehlungen), berechnet die Durchfahrt aus der Abfahrt am Start-Stop plus den Dauern der Segmente davor und übergibt `PlannedSection`s an `RouteLogic.replaceSections`. Routing läuft außerhalb der Transaktion.
  - Ergebnis `PlanResult` mit `PlanOutcome`: `PLANNED`, `MISSING_COORDINATES` (ein Stop ohne Koordinaten), `MISSING_DEPARTURE` (Start-Stop ohne Abfahrt, keine Zeit geraten), `NO_ROUTE` (mit Grund), `ROUTING_UNAVAILABLE` (Streckennetz noch nicht geladen, später erneut planen). Außer bei `PLANNED` bleiben die Sections unverändert.
  - Das Ergebnis steht an der Route (`planOutcome`, `planReason`), damit der Nutzer es auch später sieht (z. B. „Abfahrt fehlt“).
  - Die Ankunft am Ziel-Stop bleibt, was der Nutzer eingetragen hat. Abgleich mit der geplanten Ankunft ist offen.
- **Wann geplant wird** (`TripEditing`, für REST und MCP): erst wird die Änderung committet, dann werden die betroffenen Routen geplant. Die Änderung bleibt auch, wenn die Planung scheitert.

  | Änderung | geplante Routen |
  |---|---|
  | Stop einfügen, verschieben, löschen | neu angelegte Routen |
  | Koordinaten eines Stops | Routen zum und vom Stop |
  | Abfahrt eines Stops (anderer Zeitpunkt, nicht nur Offset) | Route vom Stop |
  | Ankunft oder Name eines Stops, Name oder Zeitraum des Trips | keine |
  | Waypoints einer Route | diese Route |
  | erneut planen (z. B. nach `ROUTING_UNAVAILABLE`) | diese Route |

- **Sections verwerfen:** Ändern sich Stops oder Waypoints einer Route, entfernt `RouteLogic.discardSections` deren Sections (mit den Consequences) sofort in der Transaktion der Änderung, `plannedAt` und Planungsstatus werden leer. Scheitert die Planung danach, passen keine Incidents mehr auf den alten Weg. Die Regel „außer bei `PLANNED` bleiben die Sections unverändert“ gilt damit nur für erneutes Planen ohne Änderung.
- **Anteile:** `RouteSection` und `PlannedSection` haben `fromFraction`/`toFraction`, welcher Teil des Segments befahren wird (nur am ersten und letzten Abschnitt nicht 0–1). Verhindert Fehlalarme an langen Segmenten, sobald auch `incident_segment` Anteile hat.

## Streckennetz

- Ein `RoadSegment` ist eine befahrbare Richtung einer Straße von Kreuzung zu Kreuzung mit stabiler Id, erzeugt aus OpenStreetMap. Details zu Schlüssel, Import und Aktualisierung in [network.md](network.md).
## Bookings

- `Stop` 1–* `Booking` (`stopId`): Typ (`ACCOMMODATION`, `TRANSPORT`, `ACTIVITY`, `OTHER`), Titel, Anbieter, Buchungsnummer, Zeitraum.
- Umgesetzt sind bisher nur Unterkünfte (`ACCOMMODATION`). Konzept mit allen Eingabewegen: https://claude.ai/artifact/Nc2uQiZfZdib9xT8SN8nTr
- `Booking` zusätzlich:
  - `status`: `CONFIRMED` (Standard), `CANCELLED` (`cancel()`, noch ohne API).
  - `source`: wie die Buchung in die App kam, `MANUAL`, `DOCUMENT` (Bestätigung eingefügt oder hochgeladen), `SHARE` (an die App geteilt, ggf. ergänzt um eingefügten Text oder eine Datei), `SEARCH` (in der App gesucht und gebucht, bisher alternative Unterkünfte über LiteAPI; später auch Sprach-/Namenssuche) und später `EMAIL` (Weiterleitung).
  - `manageUrl` (Link zur Buchung beim Anbieter, nur http(s)), `price` + `currency` (ISO 4217), `cancellationDeadline` (kostenlos stornierbar bis), `notes`.
  - `change(…)` ersetzt alles, was der Nutzer bearbeiten kann; `source` und `status` bleiben.
- **Zeitzone der Unterkunft:** Check-in (`startsAt`), Check-out (`endsAt`) und Stornofrist sind Ortszeit der Unterkunft, nicht die des Geräts. Das Frontend schickt sie ohne Offset (`LocalDateTime`), das Backend bestimmt die Zone aus den Koordinaten (timeshape, OSM-Zeitzonengrenzen, offline) und speichert `OffsetDateTime` mit dem dort gültigen Offset (Sommerzeit). Ohne Koordinaten gilt die Zone, die das Frontend mitschickt (`fallbackTimeZone`, Zone des Geräts), sonst UTC. Offsets in `_tz`-Spalten wie bei Stops. Angezeigt wird die Uhrzeit, wie sie am Ort gilt.
- `Booking` 1–0..1 `AccommodationDetails` (Tabelle `booking_accommodation`, `booking_id` eindeutig, `@OneToOne` innerhalb des Moduls, wird mit der Buchung gelöscht): Name, Adresse, Land (ISO 3166-1 alpha-2), Koordinaten (nur zusammen, daraus abgeleitet die PostGIS-Spalte `location`), `timeZone` (IANA, Pflicht), Telefon, E-Mail, Website, `placeRef` (Herkunft des Orts, z. B. `osm:node/123`), Hausregeln `checkInFrom`/`checkInUntil`/`checkOutUntil` (`LocalTime`), Gäste, Zimmer (1–99). Eigene Tabelle, damit Transport und Aktivitäten später eigene Details bekommen und `booking` der gemeinsame Kern bleibt.
- **Nicht gespeichert:** die PIN der Buchung (Zugangsgeheimnis, wird nicht gebraucht) und das Originaldokument der Bestätigung (Datenschutz).
- **Preis:** informativ, in der Währung, in der gebucht wurde (`NUMERIC(12, 2)` + Währung), die App rechnet nicht damit. Bewusste Ausnahme von [money.md](money.md) (Basiswährung, `Money`).
- **Eigentum:** über den Stop (`TripLogic.stop(appUserId, stopId)`), fremde oder unbekannte Stops und Buchungen sind 404.
- **Unterkunft nachschlagen** (`AccommodationLookup`, nichts wird gespeichert): Vorschläge zum Namen aus den LiteAPI-Hoteldaten (`GET /data/hotels`, Name enthält die Eingabe, im Umkreis von 30 km um den Stop zuerst, nächste zuerst). Zur gewählten Unterkunft Adresse, Telefon, E-Mail, Website und Hausregeln (`checkInFrom`/`checkInUntil`/`checkOutUntil`) aus `GET /data/hotel`; was dort fehlt (oder ohne LiteAPI-Id, z. B. Ort aus der Karte), sucht der Assistent per OpenAI-Websuche. Er bekommt nur Name, Adresse, Land und Koordinaten der Unterkunft, liefert `null` statt zu raten und nennt Quellen; Werte im falschen Format werden verworfen. Die Antwort sagt je Feld, woher es kommt (`fromLiteApi`, `fromAssistant`), recherchierte Felder muss der Nutzer prüfen. Der Ort wird als `placeRef` `liteapi:<hotelId>` gespeichert.
- **Zeitraum einer Buchung:** fachlich zählt nur das An- und Abreisedatum (Philipp, 2026-11-03), Formular und Details zeigen keine Uhrzeit. Gespeichert wird nach Konvention als `OffsetDateTime` (`startsAt`/`endsAt`) mit einer internen Uhrzeit: `checkInFrom`/`checkOutUntil`, sonst die gelesene oder gespeicherte Zeit, sonst 15:00/10:00 (Ortszeit der Unterkunft). Wann Check-in möglich ist, zeigen die Hausregeln.
- **Bestätigung auslesen** (`AccommodationExtractor`): eingefügter Text und/oder Screenshot/PDF (bis 5 MB) gehen über das Modul `assistant` an OpenAI und kommen als nicht gespeicherter Entwurf zurück (`AccommodationDraft`, Felder plus `uncertainFields`). Die Anweisung verbietet PIN, Zahlungs- und Gästedaten. Werte im falschen Format werden verworfen und als unsicher markiert, Links nur mit `https://`. Erst die Bestätigung des Nutzers speichert.
- **Neuer Stop aus einer Buchung:** Im Formular „Stop hinzufügen“ füllt eine dort erfasste Unterkunft Ort, Ankunft (Check-in) und Abfahrt (Check-out), soweit noch leer, und wird nach dem Stop gespeichert. Die Zeiten des Stops folgen weiter der Systemzeitzone.

- **Alternative Unterkünfte** (Konzept und Ablauf: [accommodation-alternatives.md](accommodation-alternatives.md)): `BookingReservation` (Tabelle `booking_reservation`, nicht historisiert) ist ein Buchungsversuch über LiteAPI am Stop (`stopId`), optional mit der ersetzten Buchung (`replacesBookingId`) und der entstandenen (`bookingId`, eindeutig). Status `PREBOOKED` → `PAYMENT_PENDING` → `AUTHORIZED` → `PROVIDER_BOOKED` → `BOOKED`, oder `FAILED` (nichts gebucht, kein Geld bewegt). Hält Angebot, Nettopreis, Provision, Endpreis, Hotel (`ReservationHotel`) und bis zur Buchung die Gastdaten (danach gelöscht). Die entstandene `Booking` hat `source` `SEARCH`, `placeRef` `liteapi:hotel/<id>`, Anbieter `LiteAPI`.
- **Als storniert markieren:** `BookingLogic.cancel` setzt `status` `CANCELLED`, nur in der App, der Anbieter wird nicht benachrichtigt.

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
- Überschneidung: `passageEnd >= validFrom` und (`validTo` leer oder `passageStart <= validTo`). Als reine Funktion `IncidentOverlap` in `incidents.logic`, auch für die Bewertung von Streckenvorschlägen. Andere Module lesen Incidents über `ActiveIncidents.onSegments` (Records, keine Entities).
- Bestehende Consequences werden aktualisiert (Id bleibt, für spätere Benachrichtigungen), nicht mehr zutreffende gelöscht.
- Tests: `ConsequenceMatchingTest`.

## Streckenvorschläge

- Für eine Route bis zu `n` Vorschläge, wie gefahren werden kann, bei der Planung und wenn ein Incident die Route trifft. Beide aus einem `Scenario` (Punkte, Abfahrt, Sperren/Verzögerungen, Referenzroute) mit derselben Logik, Details in [recommendations.md](recommendations.md).
- Vorschläge werden bei jeder Anfrage neu berechnet und nicht gespeichert. Die gewählte Strecke kommt als Liste von Punkten zurück (`PUT /routes/{id}/choice`), wird als `Waypoint`s gespeichert (neue mit `origin` `SUGGESTION`, bestehende des Nutzers bleiben) und die Route neu geplant, keine Segmentliste. Eine gespeicherte Route gilt als gewählt, es gibt keinen eigenen Merker.
- Keine Prüfung auf parallele Änderungen, die letzte Wahl gewinnt. Später allgemein über Versionierung der Entitäten (`@Version`), bei Routen inklusive der Stops.
- Der geplante Verlauf einer Route kommt aus `RouteLines` (`trips.logic`): Linie aus den Sections über `network.SegmentQueries.line`, Distanz, Dauer und „über …“ aus den Straßenhinweisen der `SUGGESTION`-Waypoints.

## Offen

- **Incidents an Unterkünften** (z. B. „Unterkunft nicht erreichbar“): bewusst ausgelassen, werden später betrachtet. Dann betrifft ein Incident evtl. einen Stop oder eine Booking statt Segmenten.
- **Event → Incident:** Segmente aus Straße und Ortsangabe bestimmen, mehrere Events demselben Incident zuordnen (Aktualisierung, Aufhebung), Typ und Gültigkeit aus dem Payload ableiten.
- **Neuplanung:** nach `NetworkRefreshed` (betroffene Routen neu planen) und Routen mit `ROUTING_UNAVAILABLE` automatisch erneut planen fehlen noch. Umfahrung bei `BLOCKED` über `recommendations`, Entwurf in [recommendations.md](recommendations.md).
- **Verzögerung fortschreiben:** Eine Verzögerung auf einem Abschnitt verschiebt die erwartete Durchfahrt aller folgenden Abschnitte; derzeit nicht berücksichtigt.
- **Ende eines Incidents:** abgelaufene Consequences bleiben derzeit stehen; aufräumen oder als erledigt markieren.
- **Impact und Delay genauer:** derzeit nur aus dem Typ des Incidents abgeleitet und gleich für alle Routen.
- **Bookings:** Bezug zu Incidents (z. B. Check-in verpasst durch Verspätung, Unterkunft nicht erreichbar), Transport und Aktivitäten, Sprach-/Namenssuche mit KI (Stufe 3, die Suche nach Alternativen ist ein erster Teil davon), E-Mail-Weiterleitung (Stufe 4), Stornieren per API, Zeiten eines aus der Buchung angelegten Stops in der Zone der Unterkunft.
- **Benachrichtigung:** wer wann worüber informiert wird, Schwellen (z. B. erst ab 15 min Verzögerung).
- **Trips teilen:** derzeit gehört ein Trip genau einem Nutzer.
- **Stops mit Buchungen löschen:** `fk_booking_stop` hat kein `ON DELETE CASCADE`, Löschen eines Stops oder Trips mit Buchungen scheitert (Buchungen und Löschen von Stops/Trips sind beide per API möglich). Zu entscheiden: Buchungen mitlöschen oder Löschen ablehnen.
- **Zeitraum und Stops:** ob Ankunft/Abfahrt der Stops innerhalb des Reisezeitraums liegen müssen, ist noch nicht geprüft.
