# Streckennetz und Routing (`network`)

- **Status:** Schema `V23` (Routenplanung `V24`), Erstimport aus lokaler Datei (`RegionImport`), `Routing` mit Sperren, `SegmentMatching` (Punkt, Linie) für Verkehrsereignisse. Download, Zeitplan, `min-segment-ratio` und `NetworkRefreshed` folgen. Machbarkeit im Spike `spike/graphhopper` geprüft (Ergebnisse unten).
- **Routing:** GraphHopper 11.1 eingebettet als Bibliothek im Backend, kein eigener Dienst. Profil `car` mit Abbiegekosten und Landmarks (LM). Kein CH, weil Sperrungen und Umfahrungen die Gewichtung pro Anfrage ändern.
- **Datenquelle:** Geofabrik-Extrakt (`.osm.pbf`) je Region. Graph und Segmente entstehen aus demselben Import und passen immer zusammen.
- **Erste Region:** Neuseeland (`nz`). Weitere Region = neuer Konfigurationsblock, Code und Schema bleiben gleich.

## Datenmodell

- `ExtractVersion`: Region, Datenstand, Status (`IMPORTING`, `READY`, `FAILED`).
- `RoadSegment`: eine befahrbare Richtung einer GraphHopper-Kante (von Kreuzung zu Kreuzung).
  - Stabile Id über Aktualisierungen, darauf zeigen `route_section` und `incident_segment` per Fremdschlüssel.
  - Fachlicher Schlüssel, eindeutig: `osm_way_id`, Start- und Endkoordinate (1e-7 Grad), Hash der Geometrie. GraphHopper liefert keine OSM-Knoten-Ids an den Kanten, daher Koordinaten.
  - Attribute: `ref`, Name, Straßenklasse, Brücke, Tunnel, `link` (Auf- oder Abfahrt, OSM `highway=*_link`, `V37`), Länge, Geometrie `geometry(LineString, 4326)` mit GIST-Index.
  - `first_seen_version`, `last_seen_version`, `retired_at` (nicht mehr im aktuellen Extrakt).
- `edge_mapping`: `edge_key` (Kante + Richtung) des aktuellen Graphen → `road_segment.id`.
  - n:1: identische Doppelkanten (Weg nutzt dasselbe Knotenpaar mehrfach) teilen ein Segment.
  - Kanten der Länge 0 (GraphHopper teilt an Sperren wie Pollern) haben kein Segment und werden in Routen übersprungen.
  - Gilt nur für eine Graph-Version (Schlüssel `extract_version_id`, `edge_key`): eine Aktualisierung schreibt ihr Mapping neben das alte, nach dem Umschalten wird das alte gelöscht. Keine Entity, nur JDBC.
- Staging nur für Segmente, als temporäre Tabelle im Import, nicht im Schema.
- **`place`:** Orte für Übernachtungen unterwegs, siehe [Orte](#orte). Keine Entity, nur JDBC.
- Fuß- und Radwege, Treppen usw. werden nicht importiert (`import.osm.ignored_highways`): Straßen werden nur an Kreuzungen für Autos geteilt, das macht Segmente stabiler. Fußgängerzonen (`pedestrian`) bleiben, manche sind für Autos frei.

## Import und Aktualisierung

1. Extrakt holen (`ExtractSource`: Geofabrik mit MD5-Prüfung oder lokale Datei).
2. GraphHopper-Import in ein neues Graph-Verzeichnis.
3. Segmente und `edge_mapping` in Staging laden, dann in einer Transaktion: Upsert über den fachlichen Schlüssel, `retired_at` für nicht mehr vorhandene.
4. Auf den neuen Graphen umschalten.
5. `NetworkRefreshed` mit den ausgemusterten Segmenten: `trips` plant betroffene Routen neu (später `incidents`).
6. Ausgemusterte Segmente ohne Referenz löschen.

- Kein Umschalten, wenn ein Schritt fehlschlägt oder deutlich weniger Segmente entstehen (`min-segment-ratio`).
- Graph-Verzeichnis je Version: `graph-dir/<extractVersionId>`, das alte wird nach dem Umschalten gelöscht. Schlägt ein Import fehl, bleibt der alte Graph in Gebrauch (Status `FAILED`).
- Start: `RegionImport.loadOrImport` je Region auf einem eigenen virtuellen Thread. Graph der aktuellen Version wird geladen (`data-access=MMAP`: außerhalb des Heaps). Fehlt er oder passt er nicht (z. B. Profile), neuer Import. Routing antwortet bis `READY` mit 503.
  - **Geprüft 2026-11-05:** Eine geänderte Liste `graph.encoded_values` erzwingt in GraphHopper 11.1 keinen Import, und `road_class_link` steckt auch in Graphen, die ohne diesen Eintrag gebaut wurden. Beim Laden läuft `SegmentWriter` nicht, ein vorhandener Graph füllt `road_segment.link` also nicht.
  - **Nach `V37`:** Hat keines der Segmente der Region `link` (`SegmentWriter.hasLinks`), lädt `loadOrImport` den Graphen nicht, sondern importiert einmal neu; die Segment-Ids bleiben. Grenzen: Eine Region ganz ohne Auf- und Abfahrten importiert bei jedem Start. Schlägt dieser Import fehl, hat die Region bis zum nächsten Start keinen Graphen (503), weil der alte nicht geladen war. Das alte Graph-Verzeichnis und sein `edge_mapping` bleiben liegen, weil beim Start kein vorheriger Graph bekannt ist.
- Rhythmus: geplant wöchentlich (`refresh-cron`). Im Spike blieben in Wellington-Zentrum nach 6 Monaten 87,8 % der Segmente erhalten, fast alle Abgänge durch neue oder verschobene Kreuzungen. Die Neuplanung nach `NetworkRefreshed` ist daher Pflicht.
- Erster Umsetzungsschritt: nur Erstimport, Aktualisierung folgt.

## Orte

- **Tabelle `place`** (`V45`): `region`, `extract_version_id`, `name`, `kind` (`CITY`, `TOWN`, `VILLAGE`), `geometry(Point, 4326)` mit GIST-Index. Niemand verweist darauf, kein stabiler Schlüssel nötig.
- **Lesen (`PlaceReader`):** eigener Durchlauf über den Extrakt mit `OSMInputFile`, GraphHopper behält keine Knoten. OSM-Knoten mit `place=city|town|village` und Name, Wege und Relationen werden übersprungen.
  - **Geprüft 2026-11-07 am NZ-Extrakt:** 637 Orte (20 `city`, 186 `town`, 431 `village`), rund 13 s. Nur Knoten reicht: Von 45 Orten, die als Weg oder Relation eingetragen sind, hat nur einer keinen Knoten gleichen Namens (The Sands / Te Tumu).
- **Schreiben (`PlaceWriter.replace`):** ersetzt alle Orte der Region in einer Transaktion, Abfragen sehen also nie zwei Versionen und brauchen keinen Filter nach Version.
- **Import:** nach den Segmenten, vor `version.ready`. Fehler lässt den Import scheitern, Graph und Orte der alten Version bleiben.
- **Start (`loadOrImport`, nach `V45`):** Hat die Region keine Orte (`hasPlaces`), liest der Start nur die Orte nach, kein neuer Import. Ein Fehler dabei wird geloggt, der Graph bleibt in Gebrauch, es gibt keine Orte bis zum nächsten Import oder Start. Eine Region ganz ohne Orte liest sie bei jedem Start erneut.
- **Abfrage:** `Places.near(segmentIds, maxDistanceMeters)` → `PlaceNearRoute`: Ort, Position des nächsten Segments im Weg (`pathIndex`), Lage darauf (`segmentFraction`, `ST_LineLocatePoint` je Segment), Abstand (`offRouteMeters`).
  - Segment statt Linie über den ganzen Weg: `ST_LineLocatePoint` misst auf `geometry(4326)` in Grad, auf einer langen Linie verzerrt das den Anteil. Auf einem Segment fällt das nicht ins Gewicht.
  - Jeder Ort einmal, am nächsten Segment. Fährt der Weg zweimal daran vorbei (hin und zurück), zählt die erste Durchfahrt; Abstände werden dafür in ganzen Metern verglichen.
  - `ST_DWithin` zweimal wie in `SegmentMatching`: in Grad für den Index, auf `geography` zum Messen.

## Schnittstellen (`network.logic`)

- Andere Module nutzen nur diese Interfaces und Records, nie Entities, Repositories oder GraphHopper-Typen. Geometrie als eigene Records (`GeoPoint`, `GeoLine`, `GeoArea`).
- `Routing.route(List<RoutePoint>, RouteOptions)`: `RoutePoint` mit `STOP`/`VIA` und optionalem Straßenhinweis. Stops nur als erster und letzter Punkt, dazwischen Via-Punkte (`RoutePlanning` plant je Stop-Paar), sonst `IllegalArgumentException`.
  - `RouteOptions(blockedSegmentIds, delayedSegments)`: gesperrte Segmente und Verzögerung je Segment (≥ 0, sonst `IllegalArgumentException`), siehe [recommendations.md](recommendations.md#schnittstellen). Gesperrt schlägt verzögert. Verzögerungen ändern nur die Wahl des Wegs, Dauern im `RouteResult` bleiben ohne Verzögerung (rechnet die Bewertung in `recommendations`). Vorlieben (unbefestigte Straßen meiden, bevorzugte Straßen) folgen mit den Trip-Vorlieben.
- `Routing.alternatives(from, to, RouteOptions, AlternativeOptions)`: schnellste Strecke und Alternativen zwischen zwei Stops über GraphHopper `alternative_route` (mit LM). In 11.1 nur zwei Punkte, ohne `pass_through`, Umgang damit in [recommendations.md](recommendations.md#generatoren).
  - `AlternativeOptions`: `maxPaths` (≥ 1, inkl. schnellster), `maxWeightFactor` (≥ 1), `maxShareFactor` (0–1).
  - Ergebnis `List<RouteResult>`, mindestens ein Eintrag, erster = schnellste. Weitere Einträge nicht streng nach Dauer sortiert.
  - Kurze Strecken (Wellington-Extrakt: ~3 km Te Aro → Thorndon) liefern oft keine Alternative, nur die schnellste.
  - Ohne Bordsteinseite an den Stops ist die schnellste Alternative ggf. schneller als `route` (Newtown → Thorndon: ~11 %).
  - Ergebnis `RouteResult`: Region, `extractVersionId`, Distanz, Dauer, Geometrie, `RoutedSegment`s (Segment-Id, `fromFraction`, `toFraction`, Distanz, Dauer), `SnappedPoint`s (gesnappter Punkt, Abstand, Position in den Segmenten).
  - Fehler: `NoRouteException` (Punkt außerhalb aller Regionen, über 500 m von einer Straße, keine Verbindung), `RoutingUnavailableException` (noch kein Graph). `RoutePlanning` macht daraus `NO_ROUTE` bzw. `ROUTING_UNAVAILABLE` an der Route, siehe [domain.md](domain.md#trips).
- `SegmentQueries.segments(ids)` → `SegmentInfo`: `ref`, Name, Länge, Mittelpunkt (`ST_LineInterpolatePoint`), Gegenrichtung (`reverseId`: gleicher Weg, Start und Ende vertauscht, `null` bei Einbahnstraßen). Genutzt von `recommendations` (Via-Punkte, „über SH 2“, Wenden am Via-Punkt). Später Geometrie und ausgemustert für API und Benachrichtigungen.
- `SegmentQueries.line(List<SectionPart>)` → `GeoLine`: Linie über Teilstücke von Segmenten (`ST_LineSubstring` nach `fromFraction`/`toFraction`, in Reihenfolge, doppelte Punkte an den Übergängen entfernt). Genutzt von `trips.RouteLines` für die Karte.
- `SegmentQueries.zone(segmentId)` → `ZoneId` der Region, in der das Segment liegt (`Stopovers`: Abendgrenze).
- `Places.near(segmentIds, maxDistanceMeters)`: Orte entlang eines Wegs, siehe [Orte](#orte).
- `Regions.covers(latitude, longitude)`: liegt der Punkt im Rechteck `bounds` einer Region (Konfiguration, unabhängig davon, ob der Graph geladen ist). Genutzt von `trips` für Stops und Waypoints, siehe [domain.md](domain.md#trips). Rechteck statt Abstand zum Netz, damit Stops auch vor dem ersten Import gehen; Meer im Rechteck ist hinnehmbar, die Ortssuche liefert dort nichts.
- `RegionImport`: Import oder Aktualisierung einer Region starten, Status abfragen.
- `ExtractSource`: Herkunft des Extrakts.
- `NetworkRefreshed` (Spring-Event): Region, neue `ExtractVersion`, ausgemusterte Segment-Ids.
- `SegmentQueries.anyInRegion(ids, region)`: liegt eines der Segmente in der Region (Ruhepause der Verkehrsquellen, über `incidents`).
- `SegmentMatching` (genutzt von `incidents.IncidentReporting`, siehe [traffic-feed.md](traffic-feed.md#verarbeitung-trafficeventprocessor)): Ergebnis Segment-Ids (noch ohne Score). Tunnel und Brücken eingeschlossen, anders als beim Snappen von Stops; der Straßenfilter hält Straßen darüber oder darunter fern. Optional Peilung (Grad, 0 = Nord): nur Segmente, deren Verlauf Start → Ende höchstens 60° abweicht.
  - `matchPoint(region, point, radiusMeters, roadRef, bearing)`: Segmente der Region im Umkreis (PostGIS `ST_DWithin` auf `geography`, Vorfilter über den GIST-Index), mit `roadRef` nur Segmente mit dieser Nummer.
  - `matchLine(region, line, roadRef, bearing)`: GraphHopper Map Matching (`graphhopper-map-matching` 11.1, Profil `car`, Messfehler 20 m) auf dem Graphen der Region, Punkte außerhalb des Graphen fallen weg. In Reihenfolge der Punkte und umgekehrt gematcht (getrennte Fahrbahnen sind je eine Einbahnstraße), dazu die Gegenrichtung (`reverseId`). Virtuelle Kanten an den Enden über `getOriginalEdgeKey`.
  - `linkOnly` (beide, zusätzlicher Parameter): nur Segmente mit `link`, unabhängig von `roadRef`, weil Auf- und Abfahrten in OSM meist keine Nummer haben.
  - Fehler: `RoutingUnavailableException`, wenn die Region noch kein Netz hat (ohne Rollback der Transaktion des Aufrufers).
  - Später: A→B, Fläche, Score.
- `RefNormalizer.normalize(region, ref)`: Straßennummern vergleichbar machen, mehrere per `;` getrennt. Neuseeland: `SH 1`, `SH1`, `State Highway 1`, `01N`, `01S` → `1`; `006` → `6`; `SH 20A` → `20A`. Sonst Leerzeichen weg (`A 6` → `A6`). Eine Regel für alle Regionen, bis eine Region eigene braucht.

## Routing-Details

- **Stops:** keine `curbsides` (geändert 2026-11-07). `alternative_route` kennt sie nicht, und mit ihnen ließ sich ein Vorschlag nicht immer wieder planen: Wānaka → Christchurch fand `alternatives` Wege, `route` mit `force_curbside=false` scheiterte mit „Connection between locations not found“. Zwischen zwei Stops stellen `route` und `alternatives` damit dieselbe Anfrage.
- **Via-Punkte:** `curbside` `any`, `pass_through=true` (kein Wenden am Via-Punkt), `point_hints` aus dem Straßenhinweis.
- **Sperren und Verzögerungen** (`ScenarioWeighting`): eigene `WeightingFactory`. `edge_key`s gesperrter Segmente (alle Kanten eines Segments, richtungsgenau) sind unpassierbar, verzögerte kosten Gewicht + Sekunden (Gewicht der Custom-Model-Gewichtung ≈ Sekunden). Mehrere `edge_key`s je Segment gibt es nur bei identischen Doppelkanten, eine Durchfahrt nutzt eine davon: jede bekommt die volle Verzögerung. Gewichte steigen nur, funktioniert mit LM. Gilt für `route` und `alternatives` gleich.
- **Anteile:** nur am ersten und letzten Abschnitt einer Route kleiner als 0–1, Position des gesnappten Start- bzw. Endpunkts auf der Kantengeometrie.
- **Snappen:** `snap_preventions` `tunnel`, `bridge`, `ferry` (ein Stop landet nicht im Tunnel darunter), höchstens 500 m vom angefragten Punkt.
- **Region:** die erste Region mit Graph, deren Bounding Box alle Punkte enthält.
- **Mapping im Speicher:** `edge_key` → Segment-Id als Array je Graph, beim Umschalten aus `edge_mapping` geladen (NZ geschätzt 20–30 MB), keine Datenbankabfrage je Route. Nur die `edge_key`s gesperrter und verzögerter Segmente kommen per Abfrage.
- **Umschalten:** Lese-/Schreibsperre je Region, eine laufende Route rechnet nie auf einem geschlossenen Graphen.

## Konfiguration

  ```properties
  nfv.network.import-on-start=${NFV_NETWORK_IMPORT_ON_START:true}
  nfv.network.regions.nz.osm-file=${NFV_DATA_DIR:${user.home}/data}/osm/new-zealand-latest.osm.pbf
  nfv.network.regions.nz.graph-dir=${NFV_DATA_DIR:${user.home}/data}/graphs/nz
  # MMAP: Graph außerhalb des Heaps, RAM_STORE in Tests
  nfv.network.regions.nz.data-access=MMAP
  nfv.network.regions.nz.ferries=true
  # Pflicht, Ortszeit der Region
  nfv.network.regions.nz.zone=Pacific/Auckland
  # Pflicht, Gebiet für Stops und Waypoints (Nord-, Süd- und Stewart Island; Chatham-Inseln liegen jenseits von 180°)
  # Frontend: gleiche Werte als Photon-bbox in frontend/src/map/geocoding.ts
  nfv.network.regions.nz.bounds.min-latitude=-47.6
  nfv.network.regions.nz.bounds.min-longitude=166.0
  nfv.network.regions.nz.bounds.max-latitude=-34.0
  nfv.network.regions.nz.bounds.max-longitude=179.0
  # Geplant
  nfv.network.regions.nz.download-url=https://download.geofabrik.de/australia-oceania/new-zealand-latest.osm.pbf
  nfv.network.regions.nz.refresh-cron=0 0 3 * * SUN
  nfv.network.regions.nz.min-segment-ratio=0.95
  ```

- Region `nz` steht in `application.properties`, die Zeilen „Geplant“ noch nicht.
- **Extrakte nicht im Repository** (kein Git LFS: ~400 MB je NZ-Extrakt, jede Aktualisierung bliebe im LFS-Speicher), `*.osm.pbf` ist in `.gitignore`.
- **Ablage auf jedem Host** (Entwicklerrechner, Server) außerhalb des Repos unter `~/data`:
  - `~/data/osm/new-zealand-latest.osm.pbf`: Extrakt, von Hand kopiert.
  - `~/data/graphs/nz/<extractVersionId>`: GraphHopper-Graph, entsteht beim ersten Start (einige Minuten), danach nur geladen.
  - Ohne Docker (`mvn spring-boot:run`, IDE) gilt `${user.home}/data`. Docker bindet `~/data/osm` (nur lesend) und `~/data/graphs` nach `/data` ein und setzt `NFV_DATA_DIR=/data`. Anderer Host-Pfad über `NFV_HOST_DATA_DIR`; im Swarm-Stack ist er Pflicht (auf dem Server `/home/deploy/data`), siehe [deployment.md](deployment.md).
- Bis `ExtractSource` von Geofabrik lädt, Extrakt von Hand holen oder kopieren:

  ```bash
  mkdir -p ~/data/osm ~/data/graphs
  curl -fL -o ~/data/osm/new-zealand-latest.osm.pbf \
    https://download.geofabrik.de/australia-oceania/new-zealand-latest.osm.pbf
  ```

  - Optional prüfen: `.md5` neben der Datei auf Geofabrik (`…/new-zealand-latest.osm.pbf.md5`), `md5sum -c`.
  - Stand des Extrakts steht im Dateikopf (`datareader.data.date`, beim Import als `ExtractVersion.dataDate` gespeichert), der Dateiname trägt kein Datum.
  - Neuer Extrakt: Datei ersetzen und neuer Import (folgt mit der Aktualisierung). Ein vorhandener Graph der aktuellen Version wird beim Start geladen, nicht neu importiert.
- **Ohne Extrakt** startet die App trotzdem: Import `FAILED` (im Log), Routing antwortet mit 503. `NFV_NETWORK_IMPORT_ON_START=false` überspringt den Import.
- `ferries=true` für NZ: Nord- und Südinsel sind nur per Fähre verbunden. `ferries` und die Encoded Values gehören zum Graphen, eine Änderung braucht einen neuen Import.

## Tests

- Testextrakt Wellington-Zentrum `backend/src/test/resources/osm/wellington.osm.gz`, enthält Terrace Tunnel und Mount Victoria Tunnel (SH 1). Wird einmal geladen und committet, Tests laden nichts nach. Neu erzeugen über Overpass (`overpass-api.de/api/interpreter`, User-Agent nötig):

  ```
  [out:xml][bbox:-41.32,174.75,-41.27,174.80];(way[highway];rel[type=restriction];);(._;>;);out meta;
  ```

- Testregion `wellington` in `src/test/resources/config/application.properties`, kein Import beim Start. `RegionImportTest` importiert ausdrücklich: rund 1,5 s, 13.096 Segmente.
- Orte: Der Testextrakt enthält keine (die Abfrage oben holt nur Straßen). `PlaceReaderTest` liest die handgeschriebene `osm/places.osm`, `RegionImportPlacesTest` importiert eine Kopie von Wellington mit zwei ergänzten Orten, `PlacesTest` und `StopoversTest` legen Orte per SQL an.
- `SegmentMatchingTest`: NZTA-Ereignisse der Stichprobe auf dem Extrakt (Terrace Tunnel als Punkt mit `ref`, Urban Motorway als Linie über den Extrakt hinaus, beide Fahrbahnen), siehe [traffic-feed.md](traffic-feed.md#tests).

## Spike-Ergebnisse (Oktober 2026, Wellington)

| Thema | Ergebnis |
|---|---|
| Import | deterministisch: gleiche Datei ergibt gleiche `edge_key`s und Schlüssel |
| Schlüssel | eindeutig nach Weglassen der 810 Null-Kanten und mit Geometrie-Hash, 11 identische Doppelkanten teilen einen Schlüssel |
| Stabilität | 87,8 % nach 6 Monaten, 148 von 2.251 Abgängen durch gelöschte Wege |
| Route | jedes `edge_key` einer Route hat ein Segment |
| Sperren | Terrace Tunnel gesperrt: 261 s statt 198 s, LM in 5 ms |
| `pass_through` | Via-Punkt ohne: 64 m mit Wenden, mit: 669 m ohne Wenden |
| Map Matching | 62 von 62 Kanten wiedergefunden |
| Jackson | Jackson 2 (GraphHopper) und 3 (Spring Boot) nebeneinander, REST bleibt JSON |

## Offen

- Geschwindigkeiten kalibrieren (eigener Encoded Value beim Import).
- Trips über Regionsgrenzen: derzeit Fehler, Lösung über größere Region.
- Szenarien als gespeicherte Regeln → Custom Model bzw. Sperrliste pro Anfrage.
- `osm_way_id` hat in GraphHopper 31 Bit (bis ca. 2,1 Mrd.), heutige Ids liegen deutlich darunter.