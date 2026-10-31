# Streckennetz und Routing (`network`)

- **Status:** geplant, Machbarkeit im Spike `spike/graphhopper` geprüft (Ergebnisse unten).
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
  - Gilt nur für eine Graph-Version, wird bei jeder Aktualisierung neu geschrieben.

## Import und Aktualisierung

1. Extrakt holen (`ExtractSource`: Geofabrik mit MD5-Prüfung oder lokale Datei).
2. GraphHopper-Import in ein neues Graph-Verzeichnis.
3. Segmente und `edge_mapping` in Staging laden, dann in einer Transaktion: Upsert über den fachlichen Schlüssel, `retired_at` für nicht mehr vorhandene.
4. Auf den neuen Graphen umschalten.
5. `NetworkRefreshed` mit den ausgemusterten Segmenten: `trips` plant betroffene Routen neu (später `incidents`).
6. Ausgemusterte Segmente ohne Referenz löschen.

- Kein Umschalten, wenn ein Schritt fehlschlägt oder deutlich weniger Segmente entstehen (`min-segment-ratio`).
- Start: Graph der aktuellen Version wird geladen (Memory-Mapped). Fehlt er, Import im Hintergrund, Routing antwortet bis `READY` mit 503.
- Rhythmus: geplant wöchentlich (`refresh-cron`). Im Spike blieben in Wellington-Zentrum nach 6 Monaten 87,8 % der Segmente erhalten, fast alle Abgänge durch neue oder verschobene Kreuzungen. Die Neuplanung nach `NetworkRefreshed` ist daher Pflicht.
- Erster Umsetzungsschritt: nur Erstimport, Aktualisierung folgt.

## Schnittstellen (`network.logic`)

- Andere Module nutzen nur diese Interfaces und Records, nie Entities, Repositories oder GraphHopper-Typen. Geometrie als eigene Records (`GeoPoint`, `GeoLine`, `GeoArea`).
- `Routing.route(points, options)`: Stops und Via-Punkte (`RoutePoint` mit `STOP`/`VIA`, optional Straßenhinweis), Optionen (gesperrte oder verlangsamte Segmente, Vorlieben). Ergebnis: Segment-Ids mit Anteilen (`fromFraction`, `toFraction`), Distanz, Dauer, gesnappte Punkte.
- `SegmentQueries`: Details zu Segmenten (`ref`, Name, Geometrie, ausgemustert) für API und Benachrichtigungen.
- `RegionImport`: Import oder Aktualisierung einer Region starten, Status abfragen.
- `ExtractSource`: Herkunft des Extrakts.
- `NetworkRefreshed` (Spring-Event): Region, neue `ExtractVersion`, ausgemusterte Segment-Ids.
- Später mit `incidents`: `SegmentMatching` (Punkt, Linie, A→B, Fläche → Segmente mit Score), `RefNormalizer` je Region.

## Routing-Details

- **Stops:** `curbsides` nach `driving-side` der Region (NZ `left`), mit `force_curbside=false`: GraphHopper hält die Seite ein, wo möglich, sonst wird sie ignoriert. Strikt schlägt das Routing auf Einbahnstraßen fehl.
- **Via-Punkte:** `curbside` `any`, `pass_through=true` (kein Wenden am Via-Punkt), `point_hints` aus dem Straßenhinweis.
- **Sperren:** eigene `WeightingFactory`, die die `edge_key`s gesperrter Segmente (alle Kanten eines Segments, richtungsgenau) unpassierbar macht. Funktioniert mit LM.
- **Anteile:** nur am ersten und letzten Abschnitt einer Route kleiner als 0–1.

## Konfiguration

  ```properties
  vacantagent.network.import-on-start=true
  vacantagent.network.regions.nz.download-url=https://download.geofabrik.de/australia-oceania/new-zealand-latest.osm.pbf
  # Statt download-url, z. B. in Tests
  vacantagent.network.regions.nz.osm-file=
  vacantagent.network.regions.nz.graph-dir=./data/graphs/nz
  vacantagent.network.regions.nz.refresh-cron=0 0 3 * * SUN
  vacantagent.network.regions.nz.min-segment-ratio=0.95
  vacantagent.network.regions.nz.driving-side=LEFT
  vacantagent.network.regions.nz.ferries=true
  ```

- `ferries=true` für NZ: Nord- und Südinsel sind nur per Fähre verbunden.

## Tests

- Testextrakt Wellington-Zentrum (Overpass, Bbox `-41.32,174.75,-41.27,174.80`, Straßen und Abbiegeverbote), enthält Terrace Tunnel und Mount Victoria Tunnel (SH 1). Wird einmal geladen und committet, Tests laden nichts nach.
- Import dauert unter 1 s, 27.613 Kanten.

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