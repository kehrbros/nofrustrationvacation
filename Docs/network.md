# Streckennetz und Routing (`network`)

- **Status:** Schema `V23` (Routenplanung `V24`), Erstimport aus lokaler Datei (`RegionImport`), `Routing` mit Sperren. Download, Zeitplan, `min-segment-ratio` und `NetworkRefreshed` folgen. Machbarkeit im Spike `spike/graphhopper` geprüft (Ergebnisse unten).
- **Routing:** GraphHopper 11.1 eingebettet als Bibliothek im Backend, kein eigener Dienst. Profil `car` mit Abbiegekosten und Landmarks (LM). Kein CH, weil Sperrungen und Umfahrungen die Gewichtung pro Anfrage ändern.
- **Datenquelle:** Geofabrik-Extrakt (`.osm.pbf`) je Region. Graph und Segmente entstehen aus demselben Import und passen immer zusammen.
- **Erste Region:** Neuseeland (`nz`). Weitere Region = neuer Konfigurationsblock, Code und Schema bleiben gleich.

## Datenmodell

- `ExtractVersion`: Region, Datenstand, Status (`IMPORTING`, `READY`, `FAILED`).
- `RoadSegment`: eine befahrbare Richtung einer GraphHopper-Kante (von Kreuzung zu Kreuzung).
  - Stabile Id über Aktualisierungen, darauf zeigen `route_section` und `incident_segment` per Fremdschlüssel.
  - Fachlicher Schlüssel, eindeutig: `osm_way_id`, Start- und Endkoordinate (1e-7 Grad), Hash der Geometrie. GraphHopper liefert keine OSM-Knoten-Ids an den Kanten, daher Koordinaten.
  - Attribute: `ref`, Name, Straßenklasse, Brücke, Tunnel, Länge, Geometrie `geometry(LineString, 4326)` mit GIST-Index.
  - `first_seen_version`, `last_seen_version`, `retired_at` (nicht mehr im aktuellen Extrakt).
- `edge_mapping`: `edge_key` (Kante + Richtung) des aktuellen Graphen → `road_segment.id`.
  - n:1: identische Doppelkanten (Weg nutzt dasselbe Knotenpaar mehrfach) teilen ein Segment.
  - Kanten der Länge 0 (GraphHopper teilt an Sperren wie Pollern) haben kein Segment und werden in Routen übersprungen.
  - Gilt nur für eine Graph-Version (Schlüssel `extract_version_id`, `edge_key`): eine Aktualisierung schreibt ihr Mapping neben das alte, nach dem Umschalten wird das alte gelöscht. Keine Entity, nur JDBC.
- Staging nur für Segmente, als temporäre Tabelle im Import, nicht im Schema.
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
- Start: `RegionImport.loadOrImport` je Region auf einem eigenen virtuellen Thread. Graph der aktuellen Version wird geladen (`data-access=MMAP`: außerhalb des Heaps). Fehlt er oder passt er nicht (andere Encoded Values, Profile), neuer Import. Routing antwortet bis `READY` mit 503.
- Rhythmus: geplant wöchentlich (`refresh-cron`). Im Spike blieben in Wellington-Zentrum nach 6 Monaten 87,8 % der Segmente erhalten, fast alle Abgänge durch neue oder verschobene Kreuzungen. Die Neuplanung nach `NetworkRefreshed` ist daher Pflicht.
- Erster Umsetzungsschritt: nur Erstimport, Aktualisierung folgt.

## Schnittstellen (`network.logic`)

- Andere Module nutzen nur diese Interfaces und Records, nie Entities, Repositories oder GraphHopper-Typen. Geometrie als eigene Records (`GeoPoint`, `GeoLine`, `GeoArea`).
- `Routing.route(List<RoutePoint>, RouteOptions)`: `RoutePoint` mit `STOP`/`VIA` und optionalem Straßenhinweis. Stops nur als erster und letzter Punkt, dazwischen Via-Punkte (`RoutePlanning` plant je Stop-Paar), sonst `IllegalArgumentException`.
  - `RouteOptions(blockedSegmentIds, delayedSegments)`: gesperrte Segmente und Verzögerung je Segment (≥ 0, sonst `IllegalArgumentException`), siehe [recommendations.md](recommendations.md#schnittstellen). Gesperrt schlägt verzögert. Verzögerungen ändern nur die Wahl des Wegs, Dauern im `RouteResult` bleiben ohne Verzögerung (rechnet die Bewertung in `recommendations`). Vorlieben (unbefestigte Straßen meiden, bevorzugte Straßen) folgen mit den Trip-Vorlieben.
- `Routing.alternatives(from, to, RouteOptions, AlternativeOptions)`: schnellste Strecke und Alternativen zwischen zwei Stops über GraphHopper `alternative_route` (mit LM). In 11.1 nur zwei Punkte, ohne `pass_through` und `curbsides`, Umgang damit in [recommendations.md](recommendations.md#generatoren).
  - `AlternativeOptions`: `maxPaths` (≥ 1, inkl. schnellster), `maxWeightFactor` (≥ 1), `maxShareFactor` (0–1).
  - Ergebnis `List<RouteResult>`, mindestens ein Eintrag, erster = schnellste. Weitere Einträge nicht streng nach Dauer sortiert.
  - Kurze Strecken (Wellington-Extrakt: ~3 km Te Aro → Thorndon) liefern oft keine Alternative, nur die schnellste.
  - Ohne Bordsteinseite an den Stops ist die schnellste Alternative ggf. schneller als `route` (Newtown → Thorndon: ~11 %).
  - Ergebnis `RouteResult`: Region, `extractVersionId`, Distanz, Dauer, Geometrie, `RoutedSegment`s (Segment-Id, `fromFraction`, `toFraction`, Distanz, Dauer), `SnappedPoint`s (gesnappter Punkt, Abstand, Position in den Segmenten).
  - Fehler: `NoRouteException` (Punkt außerhalb aller Regionen, über 500 m von einer Straße, keine Verbindung), `RoutingUnavailableException` (noch kein Graph). `RoutePlanning` macht daraus `NO_ROUTE` bzw. `ROUTING_UNAVAILABLE` an der Route, siehe [domain.md](domain.md#trips).
- `SegmentQueries.segments(ids)` → `SegmentInfo`: `ref`, Name, Länge, Mittelpunkt (`ST_LineInterpolatePoint`), Gegenrichtung (`reverseId`: gleicher Weg, Start und Ende vertauscht, `null` bei Einbahnstraßen). Genutzt von `recommendations` (Via-Punkte, „über SH 2“, Wenden am Via-Punkt). Später Geometrie und ausgemustert für API und Benachrichtigungen.
- `RegionImport`: Import oder Aktualisierung einer Region starten, Status abfragen.
- `ExtractSource`: Herkunft des Extrakts.
- `NetworkRefreshed` (Spring-Event): Region, neue `ExtractVersion`, ausgemusterte Segment-Ids.
- Später mit `incidents`: `SegmentMatching` (Punkt, Linie, A→B, Fläche → Segmente mit Score), `RefNormalizer` je Region.

## Routing-Details

- **Stops:** `curbsides` nach `driving-side` der Region (NZ `left`), mit `force_curbside=false`: GraphHopper hält die Seite ein, wo möglich, sonst wird sie ignoriert. Strikt schlägt das Routing auf Einbahnstraßen fehl.
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
  nfv.network.regions.nz.driving-side=LEFT
  nfv.network.regions.nz.ferries=true
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