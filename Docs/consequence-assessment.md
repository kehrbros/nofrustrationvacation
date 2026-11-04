# Bewertung von Consequences – Entwurf

- **Status:** Entwurf, noch kein Code. Ergänzt die Zuordnung (`ConsequenceMatcher`, [domain.md](domain.md#zuordnungslogik-consequencematcher)) um eine Bewertung gegen die Stops.
- **Ziel:** Jede Consequence bekommt eine Kategorie, damit der Nutzer sieht, was für seine Reise zählt:
  - `TOO_LATE`: Mit dem Incident kommt der Nutzer nach der Ankunftszeit am Ziel-Stop an (oder kommt gar nicht durch).
  - `DELAYED`: spürbare Verzögerung, die Ankunftszeit wird trotzdem gehalten.
  - `NO_IMPACT`: Incident auf der Route zur Zeit der Durchfahrt, aber ohne nennenswerte Verzögerung (z. B. NZTA `Caution`).
- **Nicht Teil dieses Entwurfs:** Lösungen und Alternativen (Umfahrung, spätere Abfahrt, andere Unterkunft). Die Bewertung beschreibt nur die Lage.

## Referenzzeit

- **Frist = Ankunft des Ziel-Stops** (`Stop.arrivalAt` von `Route.toStop`): der Zeitpunkt, an dem der Nutzer spätestens dort sein muss (Start des Aufenthalts).
- **Planankunft** = `expectedPassageEnd` der letzten Section der Route (Abfahrt am Start-Stop plus Fahrzeit, aus `RoutePlanning`).
- **Puffer** = Frist − Planankunft.
- **Ohne Frist** (Ziel-Stop ohne Ankunft): keine Kategorie `TOO_LATE`, nur `DELAYED` oder `NO_IMPACT`, Hinweis „Ankunftszeit fehlt“.
- **Planankunft schon nach der Frist** (Route ohne Incidents zu spät, offener Punkt „Abgleich mit der geplanten Ankunft“ in [domain.md](domain.md#trips)): `TOO_LATE` nur, wenn ein Incident die Verspätung vergrößert. Die Route trägt den Hinweis „Ankunft nicht erreichbar laut Planung“, die Consequences werden nach ihrer Verzögerung als `DELAYED` oder `NO_IMPACT` bewertet.

## Verzögerung je Consequence

- Die Bewertung läuft je **Route**, nicht je Consequence: Verzögerungen addieren sich, und eine Verzögerung verschiebt die Durchfahrt aller späteren Sections. Damit ist auch der offene Punkt „Verzögerung fortschreiben“ ([domain.md](domain.md#offen)) für die Bewertung gelöst. Die erwarteten Durchfahrten an den Sections bleiben unverändert (Planung).
- Ablauf (`ConsequenceAssessment.assessRoute(routeId)`, `incidents.logic`):
  1. Sections der Route in Reihenfolge, aktive Incidents auf ihren Segmenten (wie `matchRoute`).
  2. `shift` = 0. Je Section mit Incident: verschobene Durchfahrt = erwartete Durchfahrt + `shift`.
  3. Trifft die verschobene Durchfahrt den Incident (`IncidentOverlap`, mit Zeitfenstern), Verzögerung bestimmen (Tabelle unten), `shift` += Verzögerung.
  4. Erwartete Ankunft nach diesem Incident = Planankunft + `shift`.
- Die Kategorie wird **der Consequence** zugeordnet, die die Lage verursacht: die erste, nach der die erwartete Ankunft die Frist überschreitet, ist `TOO_LATE`, ebenso alle danach mit eigener Verzögerung. Davor `DELAYED` oder `NO_IMPACT` nach eigener Verzögerung.

| Incident | Verzögerung |
|---|---|
| Sperrung (`BLOCKED`) mit Zeitfenstern | Warten bis Ende des getroffenen Fensters: `windowEnd` − verschobene Durchfahrt |
| Sperrung ohne Fenster, mit `validTo` | `validTo` − verschobene Durchfahrt |
| Sperrung ohne Ende (bis auf Weiteres) oder Wartezeit über `max-wait` (Standard 12 h) | nicht passierbar → `TOO_LATE` (ohne Frist: `DELAYED`, Verzögerung „unbekannt“) |
| Verzögerung (`DELAYED`) mit `expectedDelay` (Quelle oder Anreicherung) | `expectedDelay` |
| Verzögerung ohne Angabe | Annahme der Quelle (`assumedDelay` am Incident, für NZTA z. B. `Caution` 0, `Delays` 15 min, `Crash` 30 min, siehe [traffic-feed.md](traffic-feed.md#verzögerung-ohne-angabe)), als „geschätzt“ markiert; ohne Annahme 0 |

- **Kategorie aus der eigenen Verzögerung:** unter `no-impact-below` (Standard 5 min) → `NO_IMPACT`, sonst `DELAYED`, bei überschrittener Frist `TOO_LATE`.
- **Nur für Pkw relevant:** `vehicleScope` `HEAVY_ONLY` aus der Anreicherung ([traffic-feed.md](traffic-feed.md#anreicherung-messageenrichment)) → Verzögerung 0, `NO_IMPACT`.
- **Neue Treffer durch Verschiebung:** Eine Verzögerung kann eine spätere Durchfahrt in ein Zeitfenster schieben (z. B. 20:40 geplant, 30 min Stau davor, Tunnel ab 21:00 gesperrt). Die Bewertung findet das, weil sie alle aktiven Incidents der Route prüft. Der `ConsequenceMatcher` legt dafür eine Consequence an (Treffer `BY_SHIFT`), damit sie angezeigt wird.

## Toleranz der Durchfahrt (beschlossen 2026-11-04)

- **Problem:** Die Durchfahrt ist ein berechneter Wert. Wer um 20:55 am Tunnel sein soll, wird bei einer Sperre ab 21:00 sonst nicht gewarnt, obwohl 10 min Verspätung reichen.
- **Regel:** Für die Prüfung auf Überschneidung wird die (verschobene) Durchfahrt nach beiden Seiten erweitert um `max(min-tolerance, tolerance-ratio × Zeit seit Abfahrt)`, Standard `max(15 min, 10 %)`. Beispiel: 2 h nach Abfahrt ±15 min, 4 h nach Abfahrt ±24 min.
- Die Wartezeit wird weiter aus der Durchfahrt ohne Toleranz berechnet. Liegt die Durchfahrt ohne Toleranz vor dem Fenster, ist die Wartezeit die Zeit vom Fensterbeginn bis zum Fensterende (Annahme: der Nutzer kommt knapp zu spät).
- **Treffer nur durch die Toleranz:** `match` `MARGINAL`, Kategorie höchstens `DELAYED`, nie `TOO_LATE`, Hinweis „knapp: Sperrung beginnt 21:00, Durchfahrt geplant 20:55“. Verzögert eine solche Consequence die Ankunft, zählt ihre Wartezeit nicht in `shift` (sonst würde ein unsicherer Treffer spätere Bewertungen verfälschen).
- Gilt in `IncidentOverlap` als eigener Aufruf mit Toleranz; `ConsequenceMatcher` legt Consequences auch für knappe Treffer an. Die Abfragen auf die Hülle (`findPassing`) erweitern ihr Zeitfenster um die größte mögliche Toleranz (`tolerance-max`, Standard 2 h).

## Rangfolge

- Innerhalb eines Trips und einer Route:
  1. `TOO_LATE`, absteigend nach Verspätung an der Frist (nicht passierbar zuerst).
  2. `DELAYED`, absteigend nach eigener Verzögerung.
  3. `NO_IMPACT`, aufsteigend nach Durchfahrt.
  - Bei Gleichstand: frühere Durchfahrt zuerst.
- Je Route eine Zusammenfassung: schlechteste Kategorie, erwartete Ankunft, Puffer bzw. Verspätung.
- Zähler der Reiseliste (`GET /incidents/trip-counts`): nur `TOO_LATE` und `DELAYED`. `NO_IMPACT` erscheint in der Liste, zählt aber nicht.

## Datenmodell (Abweichung von [domain.md](domain.md#incidents), mit diesem Entwurf beschlossen)

- `Consequence` zusätzlich (abgeleitet, nicht historisiert wie bisher):
  - `category` (`TOO_LATE`, `DELAYED`, `NO_IMPACT`)
  - `assessedDelay` (Dauer, leer = nicht passierbar), `delayAssumed` (ja/nein)
- `Incident` zusätzlich `assumedDelay` (Annahme der Quelle ohne Zahl, historisiert)
  - `expectedArrival` (Zeitpunkt, leer ohne Planankunft), `deadline` (Kopie der Frist zum Zeitpunkt der Bewertung)
  - `match` (`DIRECT`, `BY_SHIFT`, `MARGINAL`)
  - `assessedAt`
- `impact` (`BLOCKED`, `DELAYED`) bleibt: beschreibt den Incident an der Stelle, die Kategorie beschreibt die Folge für die Reise.
- Schema in derselben Migration wie die Zeitfenster (`V30`, siehe [traffic-feed.md](traffic-feed.md#schema-v30)).

## Wann bewertet wird

- Immer die ganze Route, synchron in derselben Transaktion wie die Zuordnung:
  - nach `matchIncident` für jede Route mit neuer, geänderter oder entfernter Consequence
  - nach `matchRoute` (`RouteChanged`)
  - **neu:** Ankunft des Ziel-Stops geändert → `trips` veröffentlicht `StopArrivalChanged(stopId, routeIds)`, `incidents` bewertet die Routen zum Stop neu. Heute löst eine Änderung der Ankunft keine Planung aus ([domain.md](domain.md#trips)), daher ein eigenes Event statt `RouteChanged`.
- Zeit vergeht: keine Neubewertung, die Durchfahrten sind geplant. Abgelaufene Incidents fallen wie bisher aus der Anzeige.

## Abhängigkeiten

- `incidents` liest Frist und Planankunft über `trips.logic` (wie `TripIncidents` über `TripLogic`) und die Sections über `RouteSectionRepository` (besteht).
- `trips -.->|StopArrivalChanged| incidents` neu, Richtung wie `RouteChanged`.

## API und Frontend

- `GET /trips/{id}/incidents`: je Hinweis zusätzlich `category`, `assessedDelayMinutes` (leer = nicht passierbar), `delayAssumed`, `expectedArrival`, `deadline`; sortiert nach der [Rangfolge](#rangfolge). Je Route `worstCategory`, `expectedArrival`, `slackMinutes` (negativ = zu spät).
- Frontend: Kategorie als Farbe und Text („Zu spät: 1 Std. 10 Min. nach Ankunft“, „Verzögerung 25 Min., Ankunft 17:40 noch erreichbar“, „Keine Auswirkung“), geschätzte Werte mit ✦.

## Beispiel Terrace Tunnel (`566003`, nordwärts gesperrt So–Do 21:00–04:30)

| Fahrt | Frist am Ziel | Bewertung |
|---|---|---|
| Mi 4.11. Durchfahrt 22:00, Planankunft 23:30 | 23:45 | Warten bis 04:30, 6 h 30 min → Ankunft 06:00 → `TOO_LATE` |
| Mi 4.11. Durchfahrt 22:00, Planankunft 23:30 | Do 5.11. 10:00 | 6 h 30 min, Ankunft 06:00 → `DELAYED` |
| Mi 4.11. Durchfahrt 12:00 | – | kein Treffer, keine Consequence |
| Mi 4.11. Durchfahrt 20:55, 2 h nach Abfahrt | 23:00 | knapp (±15 min), `MARGINAL`, `DELAYED` mit Hinweis |
| Mi 4.11. Durchfahrt 20:40, davor Stau 30 min (`DELAYED`) | 22:00 | Stau `DELAYED`; verschoben 21:10 im Fenster → Tunnel `TOO_LATE` (`BY_SHIFT`) |

## Tests

- Reine Bewertung als Funktion (Sections, Incidents, Planankunft, Frist → Kategorien), Tabelle der Beispiele oben plus: Toleranz wächst mit der Zeit seit Abfahrt, knapper Treffer nie `TOO_LATE`, `Caution` ohne Zahl → `NO_IMPACT`, ohne Frist, Planankunft schon zu spät, Sperrung ohne Ende, Verzögerung ohne Angabe (`delayAssumed`), `HEAVY_ONLY`, zwei Verzögerungen addieren sich über die Frist.
- Integration wie `ConsequenceMatchingTest`: Ankunft am Ziel-Stop ändern → Kategorie wechselt `DELAYED` ↔ `TOO_LATE`.

## Konfiguration

```properties
nfv.incidents.assessment.no-impact-below=5m
nfv.incidents.assessment.max-wait=12h
nfv.incidents.assessment.min-tolerance=15m
nfv.incidents.assessment.tolerance-ratio=0.10
nfv.incidents.assessment.tolerance-max=2h
# Annahmen ohne Zahl kommen von der Quelle, z. B. nfv.input.sources.nz-nzta.assumed-delay.*
```

## Offen

- Toleranz und Annahmen der Quellen an echten Daten kalibrieren.
- Frist aus der Buchung (spätester Check-in `checkInUntil` der Unterkunft) statt oder neben `Stop.arrivalAt`.
- Folgen über den Stop hinaus: Verspätung am Ziel-Stop verschiebt die Abfahrt zur nächsten Route nicht (Abfahrt ist eine Nutzerangabe).
- Benachrichtigung je Kategorie (z. B. sofort bei `TOO_LATE`, gesammelt bei `DELAYED`, nie bei `NO_IMPACT`).
