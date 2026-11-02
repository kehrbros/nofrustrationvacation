# Streckenvorschläge (`recommendations`)

- **Status:** Entwurf. Umgesetzt ist die Routing-Grundlage in `network` (`RouteOptions.delayedSegments`, `ScenarioWeighting`, `Routing.alternatives`), das Modul selbst noch nicht. Schema geplant als `V27__recommendations.sql`.
- **Ziel:** Für eine Route bekommt der Nutzer bis zu `n` Vorschläge, wie er fahren kann. Dieselbe Logik liefert Vorschläge, wenn ein Incident die geplante Route trifft (Umfahrung, spätere Abfahrt, Verzögerung hinnehmen).
- **Grundidee:** Ein Vorschlag entsteht immer aus einem **Szenario** (Ausgangslage: Punkte, Abfahrt, Einschränkungen im Netz, Referenzroute). Planung und Incident unterscheiden sich nur darin, wie das Szenario gebaut wird. Erzeugen, Bewerten, Aussortieren, Speichern, API und Frontend sind gemeinsam.
- Ersetzt die Beispieldaten in `frontend/src/trips/routeExamples.ts` und schließt den offenen Punkt „Umfahrung bei `BLOCKED`“ aus [domain.md](domain.md#offen).

## Ablauf

```mermaid
flowchart LR
    p[Planung<br>Nutzer öffnet Route] --> sf
    i[Incident<br>ConsequenceChanged] --> sf
    w[Später: Was-wäre-wenn,<br>gespeicherte Regeln] -.-> sf
    sf[ScenarioFactory] --> s([Scenario])
    s --> e[SuggestionEngine]
    e --> g2[AlternativesGenerator<br>GraphHopper alternative_route]
    e --> g3[KeepRouteGenerator]
    e --> g4[DepartureShiftGenerator]
    g2 & g3 & g4 --> ev[Bewerten<br>Incidents + Zeiten]
    ev --> r[Aussortieren, Ranking,<br>Beschriften]
    r --> set[(SuggestionSet<br>+ RouteOptions)]
    set --> api[API / Frontend / Benachrichtigung]
    api -->|übernehmen| trips[trips: TripEditing<br>Waypoints ersetzen, planen]
```

1. **Szenario bauen** (`ScenarioFactory`, einziger auslöserabhängiger Teil).
2. **Kandidaten erzeugen:** jeder aktive Generator liefert 0..* Kandidaten.
3. **Bewerten:** Durchfahrtszeiten je Segment, aktive Incidents auf dem Kandidaten (gleiche Überschneidungsregel wie `ConsequenceMatcher`), Ankunft inklusive Wartezeit und Verzögerungen.
4. **Aussortieren und Ranking:** zu ähnliche, zu langsame und unfahrbare Kandidaten fallen weg, die besten `n` bleiben.
5. **Beschriften:** Merkmale (`FASTEST`, `AVOIDS_INCIDENT`, …) und Hauptstraße des abweichenden Teils (z. B. „über SH 2“).
6. **Speichern** als `SuggestionSet` mit `RouteOption`s, damit API, Benachrichtigung und „übernehmen“ dieselben Vorschläge meinen.
7. **Übernehmen:** Via-Punkte des Vorschlags werden Waypoints der Route, ggf. neue Abfahrt, über `TripEditing` (committet, dann `RoutePlanning.plan`).

## Szenario (`recommendations.logic`)

- `Scenario` (Record, nicht persistiert):
  - `routeId`, `trigger` (`PLANNING`, `INCIDENT`, später `WHAT_IF`), optional `consequenceId`.
  - `start`: Stop oder geschätzte Position (siehe unten), `end`: Ziel-Stop, `vias`: Waypoints des Nutzers (`RoutePoint` mit `VIA`), leer bei `waypoints` = `IGNORE`.
  - `waypoints`: `IGNORE` (nur Start und Ziel) oder `FOLLOW` (über alle Waypoints, Route in Abschnitte geteilt).
  - `reroutedLegs`: Abschnitte, für die Alternativen gesucht werden (Standard: alle). Übrige Abschnitte bleiben wie in der Referenz.
  - `departure`: Abfahrt am Start.
  - `constraints`: `blockedSegmentIds`, `delayedSegments` (Segment-Id → Verzögerung), `avoidedSegmentIds` (Strafe statt Sperre, z. B. Vorlieben).
  - `reference`: aktuell geplante Sections der Route (leer, wenn noch nicht geplant). Grundlage für `KeepRouteGenerator`, Anteil gemeinsamer Strecke und „schneller/langsamer als bisher“.
  - `generators`: welche Generatoren laufen, `count`: gewünschte Anzahl `n` (Standard 3, höchstens 5).
- **Planung** (`ScenarioFactory.forPlanning(routeId, includeWaypoints, departure?)`):
  - Flag `includeWaypoints` (Standard `false`): Vorschläge nur zwischen Start- und Ziel-Stop, Waypoints des Nutzers werden ignoriert (`waypoints` = `IGNORE`). Eine GraphHopper-Anfrage, freie Vorschläge auch abseits der bisherigen Waypoints. Mit `true` → `FOLLOW`, Abschnitte wie unter [Generatoren](#generatoren).
  - Die Referenz ist trotzdem die geplante Route mit Waypoints, damit der Nutzer vergleichen kann („wie geplant über Arthur's Pass“).
  - Abfahrt wie in `RoutePlanning` (Abfahrt am Start-Stop). Fehlende Koordinaten oder Abfahrt → gleiche Ergebnisse wie `PlanResult` (`MISSING_COORDINATES`, `MISSING_DEPARTURE`), kein Set. Route ohne Sections (Planung gescheitert oder verworfen) → keine Referenz.
  - Keine Einschränkungen im Netz. Bekannte Incidents werden nur bewertet, nicht umfahren: der Nutzer sieht „führt durch Baustelle SH 1“ am Vorschlag.
  - Generatoren: `ALTERNATIVES` (liefert auch die schnellste).
- **Incident** (`ScenarioFactory.forConsequence(consequenceId)`):
  - Immer `waypoints` = `FOLLOW`: die Waypoints des Nutzers bleiben, kein Flag.
  - Wie Planung, dazu: `CLOSURE` → Segmente des Incidents in `blockedSegmentIds`, sonst mit `expectedDelay` in `delayedSegments`. Weitere aktive Incidents auf der Referenzroute ebenso.
  - **Nur betroffene Abschnitte neu:** `reroutedLegs` = die Abschnitte (zwischen Stop und Waypoints), auf denen eine Section mit dem Incident liegt. Nur dort sucht GraphHopper Alternativen, die übrigen Abschnitte bleiben wie geplant. Vorschlag = geplante Abschnitte davor + Umfahrung + geplante Abschnitte danach. Kleinere, verständliche Änderung („Umfahrung zwischen Arthur's Pass und Greymouth“), weniger Anfragen.
  - Findet GraphHopper für den Abschnitt keine Umfahrung (Sperre direkt am Waypoint oder einzige Umfahrung lässt ihn aus): nur `KEEP_ROUTE` und `DEPARTURE_SHIFT`, Hinweis `WAYPOINT_UNREACHABLE` am Set, der Nutzer muss den Waypoint selbst verschieben oder entfernen.
  - Referenz = aktuell geplante Route. Generatoren: `KEEP_ROUTE`, `ALTERNATIVES`, `DEPARTURE_SHIFT`.
  - **Bereits unterwegs** (Abfahrt vorbei, Ankunft nicht): Start ist die geschätzte Position jetzt = Section, deren erwartete Durchfahrt `now` enthält, Punkt bei passendem Anteil. Vorschläge nur ab dort, `DEPARTURE_SHIFT` entfällt. Echte Position (GPS aus der App) später als eigener `start`.
  - Section mit dem Incident schon passiert → kein Set.
- **Später** (`WHAT_IF`): andere Abfahrt ausprobieren, vom Nutzer gesperrte Straße, gespeicherte Regeln aus [network.md](network.md#offen) („Szenarien als gespeicherte Regeln“). Nur neue Factory-Methode, Rest bleibt.

## Generatoren

- Interface `CandidateGenerator { List<Candidate> generate(Scenario, GeneratorContext) }`, je Generator eine Spring-Bean mit `kind()`. `GeneratorContext` gibt Zugriff auf `Routing`.
- `Candidate`: Herkunft (`kind`), `RouteResult`, ggf. geänderte Abfahrt, Via-Punkte, die zum Übernehmen gespeichert werden.
- **`ALTERNATIVES`:** GraphHopper erzeugt die Vorschläge mit `algorithm=alternative_route` (`Routing.alternatives`, siehe [Schnittstellen](#schnittstellen)). Sperren und Verzögerungen des Szenarios wirken über dieselbe Gewichtung wie beim normalen Routing, GraphHopper umfährt den Incident also selbst.
  - Parameter: `alternative_route.max_paths` = `n + 1` (Reserve, falls ein Vorschlag nach der Bewertung wegfällt), `max_weight_factor` 1,4, `max_share_factor` 0,6. GraphHopper sortiert damit schon zu ähnliche und zu lange Strecken aus. Werte in `RecommendationProperties`.
  - Funktioniert mit LM (`LMRoutingAlgorithmFactory` kennt `alternative_route`), kein CH nötig.
  - Der erste Pfad ist die schnellste Strecke, Label `FASTEST`.
  - **Grenzen in GraphHopper 11.1** (`Router.routeAlt`) und wie wir sie umgehen:
    - Nur zwei Punkte: Route ohne Waypoints → eine Anfrage Stop → Stop. `IGNORE` (Standard bei Planung) → ebenfalls eine Anfrage Stop → Stop. `FOLLOW` mit Waypoints → je Abschnitt in `reroutedLegs` (Stop → Via, Via → Via, Via → Stop) eine Anfrage mit Alternativen, übrige Abschnitte aus der Referenz, dann kombinieren: Kombinationen nach Gesamtdauer, die besten `n + 1` weiter in die Bewertung.
    - Kein `pass_through`: Kombinationen, die an einem Via-Punkt wenden (letzte Kante eines Abschnitts = umgekehrte erste Kante des nächsten), fallen weg.
    - Keine `curbsides`: Vorschläge ignorieren die Bordsteinseite an den Stops. Weil sie auch beim Planen nur weich gilt (`force_curbside=false`), ändert sich höchstens das letzte Stück. Nach dem Übernehmen plant `RoutePlanning` mit Bordsteinseite neu.
  - Liefert GraphHopper weniger als `n` Strecken (kurze Routen, wenig Netz), bekommt der Nutzer weniger Vorschläge. Keine eigenen Alternativen daneben.
- **`KEEP_ROUTE`:** Referenzroute unverändert, bewertet mit Verzögerungen bzw. als unfahrbar bei Sperrung. Nur wenn es eine Referenz gibt. Ergebnis „Verzögerung hinnehmen“ oder (gesperrt) nicht im Ranking, aber als Vergleich in der Antwort (`reference`).
- **`DEPARTURE_SHIFT`:** nur bei Incident mit Ende (`validTo`) oder Beginn in der Zukunft:
  - Später fahren: Abfahrt so, dass die betroffene Section frühestens `validTo + Puffer` erreicht wird (Puffer konfigurierbar, Standard 15 min), Strecke = Referenz.
  - Früher fahren: Section vor `validFrom - Puffer` passieren, nur wenn die neue Abfahrt nicht in der Vergangenheit und nicht vor der Ankunft am Start-Stop liegt.
  - Kein Routing nötig, nur neue Durchfahrtszeiten.
- Neue Generatoren (z. B. Vorlieben „keine unbefestigten Straßen“, Fähre meiden) nur als neue Bean und `kind`, Engine und Schema bleiben.

## Bewertung, Aussortieren, Ranking

- **Bewertung** je Kandidat (`CandidateEvaluator`):
  - Durchfahrt je Segment wie in `RoutePlanning.sections` (Abfahrt plus Dauern davor), dazu Verzögerungen aus `delayedSegments` und aus Incidents auf dem Weg, fortgeschrieben auf alle folgenden Segmente (löst für Vorschläge den offenen Punkt „Verzögerung fortschreiben“).
  - Aktive Incidents auf den Segmenten mit Überschneidung → `conflicts` (Incident, Impact, Verzögerung). Gleiche Regel wie `ConsequenceMatcher`, dafür eine reine Funktion `IncidentOverlap` in `incidents.logic`, die beide nutzen.
  - Ergebnis: Ankunft, Distanz, Fahrzeit, Wartezeit, Verzögerung, Anteil gemeinsamer Strecke mit der Referenz, `BLOCKED` ja/nein.
- **Aussortieren:**
  - Kandidat führt über eine Sperrung zur Zeit der Durchfahrt → raus.
  - Ähnlichkeit und Länge prüft GraphHopper (`max_share_factor`, `max_weight_factor`). Selbst geprüft nur, was GraphHopper nicht sehen kann: kombinierte Abschnitte (Anteil gemeinsamer Strecke > 0,6 mit einem besser platzierten Vorschlag → raus) und `KEEP_ROUTE` gegen gleichwertige Alternativen.
  - Alle Schwellen in `RecommendationProperties`.
- **Ranking:** nach Ankunft am Ziel (inkl. Warten und Verzögerung), dann Distanz. Höchstens `n` Vorschläge, höchstens ein `DEPARTURE_SHIFT` je Richtung.
- **Beschriften** (`labels`, mehrere möglich): `FASTEST`, `SHORTEST`, `CURRENT` (gleich der Referenz), `AVOIDS_INCIDENT`, `HAS_INCIDENT`, `LATER_DEPARTURE`, `EARLIER_DEPARTURE`, `FERRY`. Dazu `via`: häufigste `ref` bzw. Straßenname im abweichenden Teil (über `SegmentQueries`), z. B. „über SH 2“. Texte baut das Frontend.

## Datenmodell (`V27__recommendations.sql`)

- Nicht historisiert (abgeleitet, wird ersetzt), wie `Route`/`RouteSection`.
- `SuggestionSet`: `routeId` (FK `route`, `ON DELETE CASCADE`), `waypointMode` (`IGNORE`, `FOLLOW`), `trigger`, optional `consequenceId` (FK `consequence`, `ON DELETE SET NULL`) und `incidentId` (FK `incident`), `requestedCount`, `departureAt` des Szenarios, `basisHash`, `status`, `createdAt`, `expiresAt`.
  - `basisHash`: Hash über Punkte, `waypointMode`, Abfahrt, Einschränkungen, Referenz-Sections und `extractVersionId`. Gleicher Hash → bestehendes Set wiederverwenden statt neu rechnen.
  - `status`: `CURRENT`, `STALE` (Route oder Incident seitdem geändert), `APPLIED`, `EXPIRED`.
  - `expiresAt`: Planung 1 h, Incident bis Durchfahrt der betroffenen Section bzw. `validTo`.
- `SuggestionSet` 1–* `RouteOption` (`@ManyToOne` innerhalb des Moduls): `rank` (eindeutig je Set), `kind`, `labels`, `via`, `departureAt`, `arrivalAt`, `distanceMeters`, `duration`, `waiting`, `delay`, `sharedRatio`, `geometry` (`geometry(LineString, 4326)`, für die Karte), `appliedAt`.
- `RouteOption` 1–* `RouteOptionPoint`: `position`, Koordinate, `roadHint`. Die Via-Punkte, die beim Übernehmen Waypoints werden.
- `RouteOption` 1–* `RouteOptionConflict`: `incidentId` (FK), `impact`, `delay`. Was auf diesem Vorschlag noch passiert.
- Keine Segmentliste je Vorschlag: nach dem Übernehmen plant `RoutePlanning` neu, die Sections entstehen dort.
- `waypoint` bekommt `origin` (`USER`, `SUGGESTION`, `VARCHAR(16)`, Standard `USER`, auch in `waypoint_aud`): Frontend zeigt erzeugte Waypoints anders, „zurück zur schnellsten“ entfernt nur `SUGGESTION`-Waypoints.
- Aufräumen: Job löscht Sets mit `expiresAt` älter als 7 Tage.

## Übernehmen eines Vorschlags

- Gespeichert wird die **Absicht**, nicht die Segmentliste (wie bei Waypoints): Via-Punkte, die den Vorschlag reproduzieren.
- Via-Punkte beim Erzeugen bestimmen (`ViaPointExtractor`): bei `FOLLOW` bleiben die Waypoints des Nutzers, bei `IGNORE` nicht; für jeden zusammenhängenden Abschnitt, der von der schnellsten Strecke abweicht, ein Punkt in der Mitte des längsten Segments dort, mit dessen `ref` als `roadHint`. Prüfen: Routing über diese Punkte ergibt ≥ 90 % gemeinsame Strecke mit dem Kandidaten, sonst weiteren Punkt im längsten noch abweichenden Abschnitt (höchstens 3 je Abschnitt).
- So bleibt die Wahl nach `NetworkRefreshed` und Änderungen der Abfahrt erhalten, und die Sperre selbst muss nicht dauerhaft gespeichert werden.
- `SuggestionApplication.apply(optionId, userId)`:
  1. Set muss `CURRENT` sein und dem Nutzer gehören, sonst `409` mit `SUGGESTION_STALE` bzw. `404`.
  2. Bei `DEPARTURE_SHIFT`: `TripEditing.updateStop(…)` mit neuer Abfahrt am Start-Stop (plant die Route vom Stop).
  3. Sonst `TripEditing.replaceWaypoints(appUserId, tripId, routeId, points)`: Waypoints als geordnete Liste ersetzen (mit `origin` `SUGGESTION`; bei `FOLLOW` sind die Nutzer-Waypoints mit ihrer Id enthalten und bleiben, bei `IGNORE` entfallen sie, das Frontend weist vorher darauf hin). `TripEditing` committet, verwirft die alten Sections (`discardSections`) und plant die Route.
  4. `RoutePlanning.plan` → `RouteChanged` → `ConsequenceMatcher` legt Consequences für den neuen Weg an, die alte ist mit den verworfenen Sections schon weg.
  5. Set `APPLIED`, `RouteOption.appliedAt`. Antwort: `PlanResult` der Route. Scheitert die Planung (`ROUTING_UNAVAILABLE`, `NO_ROUTE`), bleiben die Waypoints trotzdem (Regel von `TripEditing`), die Route zeigt den Status.

## Auslöser und Lebenszyklus

- **Planung:** auf Anfrage (API), synchron. Routing außerhalb der Transaktion wie in `RoutePlanning`. Zeitbudget: eine `alternative_route`-Anfrage je Abschnitt, mit LM wenige ms bis ~100 ms für lange Strecken (am NZ-Graphen messen).
- **Incident:** `incidents` veröffentlicht `ConsequenceChanged` (`consequenceId`, `routeId`, `incidentId`, `CREATED`/`UPDATED`/`REMOVED`).
  - `recommendations` hört mit `@TransactionalEventListener(AFTER_COMMIT)` und rechnet auf einem virtuellen Thread: Routing darf die Transaktion von `ConsequenceMatcher` nicht verlängern.
  - Nur bei `BLOCKED` oder `delay` ≥ `min-delay` (Standard 15 min), passt zu den Schwellen der Benachrichtigung.
  - `REMOVED` → Sets der Consequence `STALE`.
- **`RouteChanged`** → alle `CURRENT`-Sets der Route `STALE` (nicht neu rechnen; Planung rechnet beim nächsten Öffnen, Incident über das folgende `ConsequenceChanged`).
- **`RoutingUnavailableException`:** Planung → `503`, Incident → später erneut (gleicher Mechanismus wie die offene Neuplanung bei `ROUTING_UNAVAILABLE`).
- **Benachrichtigung** (offen): enthält den besten Vorschlag und verlinkt das Set.

## Schnittstellen

- `recommendations.logic` nach außen: `Suggestions.forPlanning(routeId, count, includeWaypoints, departure?)`, `Suggestions.forConsequence(consequenceId, count)`, `Suggestions.get(setId)`, `SuggestionApplication.apply(optionId)`. Ergebnis `SuggestionResult` (Set, Vorschläge, `reference`, ggf. Problem wie `MISSING_DEPARTURE`).
- **`network`** (Erweiterung von `RouteOptions`, ersetzt den Satz „derzeit nur `blockedSegmentIds`“ in [network.md](network.md#schnittstellen-networklogic)):
  - `RouteOptions(blockedSegmentIds, delayedSegments: Map<Long, Duration>)`.
  - `BlockedEdgesWeighting` wird `ScenarioWeighting`: gesperrt → unendlich, Verzögerung → Gewicht + Sekunden (≥ 0). Gewichte steigen nur, die LM-Schätzung bleibt gültig. Gilt für normales Routing und `alternative_route` gleich.
  - Neu `Routing.alternatives(RoutePoint from, RoutePoint to, RouteOptions, AlternativeOptions)`: genau zwei Punkte, `algorithm=alternative_route`, ohne `curbsides` und `pass_through`. Ergebnis `List<RouteResult>`, erster Eintrag = schnellste. `AlternativeOptions`: `maxPaths`, `maxWeightFactor`, `maxShareFactor`.
  - Neu `Routing.evaluate(segmentIds)` nicht nötig: Dauern der Referenz stehen schon in den Sections.
- **`incidents`:** `ConsequenceChanged` (Event), `IncidentOverlap` (reine Funktion, auch von `ConsequenceMatcher` genutzt), `ActiveIncidents.onSegments(segmentIds, from, to)` (Lesen).
- **`trips`:** `TripEditing.replaceWaypoints(…)` und `updateStop(…)` (vorhanden, `NewWaypoint` bekommt `origin`), Lesen von Stops, Waypoints und Sections über eine schmale Klasse `RouteSnapshot` in `trips.logic` statt Repositories.

### Abhängigkeiten

- `recommendations → network`, `→ incidents`, `→ trips`. Keines dieser Module kennt `recommendations`, kein Zyklus. Rückwärts nur über Events (`ConsequenceChanged`, `RouteChanged`).
- Neu gegenüber [architecture.md](architecture.md#abhängigkeiten-zwischen-modulen): `recommendations → trips` (Lesen der Route, Übernehmen).

## API

- `POST /api/v1/routes/{routeId}/suggestions` (`count`, `includeWaypoints` Standard `false`, optional `departureAt` für Was-wäre-wenn ohne Speichern am Stop): Planung, liefert bestehendes `CURRENT`-Set bei gleichem `basisHash`.
- `GET /api/v1/suggestion-sets/{id}`: Set mit Vorschlägen (Geometrie als Koordinatenliste), `reference`, `status`.
- `POST /api/v1/suggestion-sets/{setId}/options/{optionId}/apply`: übernehmen, liefert die neu geplante Route.
- `GET /trips/{id}/incidents` bekommt je Hinweis `suggestionSetId` (falls vorhanden).
- Fremde Route oder Set: `404` `TRIP_NOT_FOUND`, veraltetes Set: `409` `SUGGESTION_STALE`, kein Graph: `503`.
- Später MCP-Tool `suggest_routes` auf derselben Logik.

## Frontend

- Gemeinsame Komponente `RouteOptionList` (Radio-Liste wie heute in `RouteSheet`): Dauer, km, Ankunft, Labels als Chips, Konflikte als Warnzeile, Linien aller Vorschläge auf der Karte (gewählter hervorgehoben).
- `RouteSheet` (Planung): ersetzt `exampleSuggestions`, Button „Route übernehmen“ ruft `apply` auf. Schalter „Zwischenziele berücksichtigen“ (nur sichtbar, wenn die Route Waypoints hat, aus = Standard) setzt `includeWaypoints`. Vorschlag ohne Waypoints übernehmen → Hinweis „Deine Zwischenziele werden entfernt“.
- `IncidentSheet` (Incident): unter „Auswirkung auf deine Reise“ Abschnitt „Was du tun kannst“ mit derselben Liste, `reference` als „Wie geplant (+45 min)“ bzw. „Wie geplant (gesperrt)“.
- Erzeugte Waypoints (`origin` `SUGGESTION`) in der Liste als „aus Vorschlag“, entfernbar.

## Tests

- `SuggestionEngineTest` auf dem Wellington-Extrakt: Planung ohne Flag ignoriert Waypoints, mit Flag folgt sie ihnen; Incident auf Abschnitt 2 von 3 lässt Abschnitte 1 und 3 unverändert; Terrace Tunnel gesperrt → Vorschlag ohne Tunnel, `KEEP_ROUTE` unfahrbar; ohne Sperre → `n` Vorschläge mit `sharedRatio` ≤ 0,6.
- `AlternativesGenerator` mit Via-Punkt: Abschnitte kombiniert, Kombination mit Wenden am Via-Punkt verworfen.
- `DepartureShiftGenerator` ohne Routing (Zeiten), inkl. „bereits unterwegs“.
- `ViaPointExtractor`: übernommener Vorschlag (inkl. Bordsteinseite beim Neuplanen) reproduziert ≥ 90 % der Strecke.
- Lebenszyklus: `RouteChanged` → `STALE`, `apply` auf `STALE` → 409, `apply` entfernt die Consequence.

## Offen

- Mehrere Routen eines Trips: Verzögerung auf Route 1 verschiebt Route 2. Erst Vorschläge je Route, Kette später.
- Vorlieben am Trip (unbefestigt meiden, Fähre meiden) als Einschränkungen im Szenario.
- Vorschläge, die Stops ändern (Übernachtung verschieben, Booking betroffen), gehören zu Incidents an Unterkünften.
- Echte Position während der Fahrt statt geschätzter.
- Verkehrsabhängige Zeiten (Stoßzeiten) fehlen, Zeiten kommen nur aus GraphHopper.
