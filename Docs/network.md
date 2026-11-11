# Road network and routing (`network`)

- **Status:** schema `V23` (route planning `V24`), initial import from a local file (`RegionImport`), `Routing` with blocks, `SegmentMatching` (point, line) for traffic events. Download, schedule, `min-segment-ratio` and `NetworkRefreshed` follow. Feasibility checked in the spike `spike/graphhopper` (results below).
- **Routing:** GraphHopper 11.1 embedded as a library in the backend, no separate service. Profile `car` with turn costs and landmarks (LM). No CH, because closures and detours change the weighting per request.
- **Data source:** Geofabrik extract (`.osm.pbf`) per region. Graph and segments come from the same import and always match.
- **First region:** New Zealand (`nz`). Another region = a new configuration block, code and schema stay the same.

## Data model

- `ExtractVersion`: region, data date, status (`IMPORTING`, `READY`, `FAILED`).
- `RoadSegment`: one drivable direction of a GraphHopper edge (from junction to junction).
  - Stable id across updates, `route_section` and `incident_segment` point to it via foreign key.
  - Unique business key: `osm_way_id`, start and end coordinate (1e-7 degrees), hash of the geometry. GraphHopper does not provide OSM node ids at the edges, hence coordinates.
  - Attributes: `ref`, name, road class, bridge, tunnel, `link` (on- or off-ramp, OSM `highway=*_link`, `V37`), length, geometry `geometry(LineString, 4326)` with GIST index.
  - `first_seen_version`, `last_seen_version`, `retired_at` (no longer in the current extract).
- `edge_mapping`: `edge_key` (edge + direction) of the current graph → `road_segment.id`.
  - n:1: identical duplicate edges (a way uses the same node pair several times) share a segment.
  - Edges of length 0 (GraphHopper splits at barriers such as bollards) have no segment and are skipped in routes.
  - Applies to one graph version only (key `extract_version_id`, `edge_key`): an update writes its mapping next to the old one, after switching over the old one is deleted. No entity, JDBC only.
- Staging only for segments, as a temporary table in the import, not in the schema.
- **`place`:** places for overnight stays on the way, see [Places](#places). No entity, JDBC only.
- Footpaths and cycle paths, stairs etc. are not imported (`import.osm.ignored_highways`): roads are split only at junctions for cars, which makes segments more stable. Pedestrian zones (`pedestrian`) stay, some are open to cars.

## Import and update

1. Fetch the extract (`ExtractSource`: Geofabrik with MD5 check or local file).
2. GraphHopper import into a new graph directory.
3. Load segments and `edge_mapping` into staging, then in one transaction: upsert via the business key, `retired_at` for those no longer present.
4. Switch to the new graph.
5. `NetworkRefreshed` with the retired segments: `trips` replans affected routes (later `incidents`).
6. Delete retired segments without references.

- No switch-over if a step fails or significantly fewer segments are created (`min-segment-ratio`).
- Graph directory per version: `graph-dir/<extractVersionId>`, the old one is deleted after switching over. If an import fails, the old graph stays in use (status `FAILED`).
- Startup: `RegionImport.loadOrImport` per region on its own virtual thread. The graph of the current version is loaded (`data-access=MMAP`: outside the heap). If it is missing or does not fit (e.g. profiles), a new import. Routing responds with 503 until `READY`.
  - **Checked 2026-11-05:** a changed list `graph.encoded_values` does not force an import in GraphHopper 11.1, and `road_class_link` is also contained in graphs built without this entry. When loading, `SegmentWriter` does not run, so an existing graph does not fill `road_segment.link`.
  - **After `V37`:** if none of the segments of the region has `link` (`SegmentWriter.hasLinks`), `loadOrImport` does not load the graph but imports once more; the segment ids stay. Limits: a region entirely without on- and off-ramps imports on every start. If this import fails, the region has no graph until the next start (503), because the old one was not loaded. The old graph directory and its `edge_mapping` remain, because no previous graph is known at startup.
- Rhythm: planned weekly (`refresh-cron`). In the spike 87.8 % of the segments in central Wellington survived after 6 months, almost all losses due to new or moved junctions. Replanning after `NetworkRefreshed` is therefore mandatory.
- First implementation step: initial import only, update follows.

## Places

- **Table `place`** (`V45`): `region`, `extract_version_id`, `name`, `kind` (`CITY`, `TOWN`, `VILLAGE`), `geometry(Point, 4326)` with GIST index. Nothing refers to it, no stable key needed.
- **Reading (`PlaceReader`):** separate pass over the extract with `OSMInputFile`, GraphHopper keeps no nodes. OSM nodes with `place=city|town|village` and a name, ways and relations are skipped.
  - **Checked 2026-11-07 on the NZ extract:** 637 places (20 `city`, 186 `town`, 431 `village`), around 13 s. Nodes only is enough: of 45 places entered as a way or relation, only one has no node of the same name (The Sands / Te Tumu).
- **Writing (`PlaceWriter.replace`):** replaces all places of the region in one transaction, so queries never see two versions and need no filter by version.
- **Import:** after the segments, before `version.ready`. An error makes the import fail, graph and places of the old version stay.
- **Startup (`loadOrImport`, after `V45`):** if the region has no places (`hasPlaces`), startup only reads the places additionally, no new import. An error there is logged, the graph stays in use, there are no places until the next import or start. A region entirely without places reads them again on every start.
- **Query:** `Places.near(segmentIds, maxDistanceMeters)` → `PlaceNearRoute`: place, position of the nearest segment in the path (`pathIndex`), location on it (`segmentFraction`, `ST_LineLocatePoint` per segment), distance (`offRouteMeters`).
  - Segment instead of a line over the whole path: `ST_LineLocatePoint` measures on `geometry(4326)` in degrees, on a long line this distorts the fraction. On a segment it does not matter.
  - Every place once, at the nearest segment. If the path passes it twice (there and back), the first passage counts; distances are compared in whole metres for this.
  - `ST_DWithin` twice as in `SegmentMatching`: in degrees for the index, on `geography` for measuring.

## Interfaces (`network.logic`)

- Other modules only use these interfaces and records, never entities, repositories or GraphHopper types. Geometry as own records (`GeoPoint`, `GeoLine`, `GeoArea`).
- `Routing.route(List<RoutePoint>, RouteOptions)`: `RoutePoint` with `STOP`/`VIA` and optional road hint. Stops only as first and last point, via points in between (`RoutePlanning` plans per stop pair), otherwise `IllegalArgumentException`.
  - `RouteOptions(blockedSegmentIds, delayedSegments)`: blocked segments and delay per segment (≥ 0, otherwise `IllegalArgumentException`), see [recommendations.md](recommendations.md#interfaces). Blocked beats delayed. Delays only change the choice of the path, durations in `RouteResult` stay without delay (the assessment in `recommendations` calculates them). Preferences (avoid unpaved roads, preferred roads) follow with the trip preferences.
- `Routing.alternatives(from, to, RouteOptions, AlternativeOptions)`: fastest route and alternatives between two stops via GraphHopper `alternative_route` (with LM). In 11.1 only two points, without `pass_through`, handling of this in [recommendations.md](recommendations.md#generators).
  - `AlternativeOptions`: `maxPaths` (≥ 1, incl. fastest), `maxWeightFactor` (≥ 1), `maxShareFactor` (0–1).
  - Result `List<RouteResult>`, at least one entry, first = fastest. Further entries not strictly sorted by duration.
  - Short routes (Wellington extract: ~3 km Te Aro → Thorndon) often return no alternative, only the fastest.
  - Without curbside at the stops the fastest alternative may be faster than `route` (Newtown → Thorndon: ~11 %).
  - Result `RouteResult`: region, `extractVersionId`, distance, duration, geometry, `RoutedSegment`s (segment id, `fromFraction`, `toFraction`, distance, duration), `SnappedPoint`s (snapped point, distance, position in the segments).
  - Errors: `NoRouteException` (point outside all regions, more than 500 m from a road, no connection), `RoutingUnavailableException` (no graph yet). `RoutePlanning` turns these into `NO_ROUTE` or `ROUTING_UNAVAILABLE` on the route, see [domain.md](domain.md#trips).
- `SegmentQueries.segments(ids)` → `SegmentInfo`: `ref`, name, length, midpoint (`ST_LineInterpolatePoint`), opposite direction (`reverseId`: same way, start and end swapped, `null` for one-way streets). Used by `recommendations` (via points, "via SH 2", turning at the via point). Later geometry and retired for API and notifications.
- `SegmentQueries.line(List<SectionPart>)` → `GeoLine`: line over parts of segments (`ST_LineSubstring` by `fromFraction`/`toFraction`, in order, duplicate points at the transitions removed). Used by `trips.RouteLines` for the map.
- `SegmentQueries.closest(List<SectionPart>, GeoPoint)` → `ClosestPoint`: nearest point on the parts (`ST_ClosestPoint` on `ST_LineSubstring`, distance via `geography`), with the index of the part. Used by `incidents.RoutePlacement` (placing test reports on the route).
- `SegmentQueries.zone(segmentId)` → `ZoneId` of the region in which the segment lies (`Stopovers`: evening limit).
- `Places.near(segmentIds, maxDistanceMeters)`: places along a path, see [Places](#places).
- `Regions.covers(latitude, longitude)`: whether the point lies in the rectangle `bounds` of a region (configuration, independent of whether the graph is loaded). Used by `trips` for stops and waypoints, see [domain.md](domain.md#trips). Rectangle instead of distance to the network, so that stops work even before the first import; sea inside the rectangle is acceptable, the place search returns nothing there.
- `RegionImport`: start import or update of a region, query status.
- `ExtractSource`: origin of the extract.
- `NetworkRefreshed` (Spring event): region, new `ExtractVersion`, retired segment ids.
- `SegmentQueries.anyInRegion(ids, region)`: whether one of the segments lies in the region (quiet period of the traffic sources, via `incidents`).
- `SegmentMatching` (used by `incidents.IncidentReporting`, see [traffic-feed.md](traffic-feed.md#processing-trafficeventprocessor)): result segment ids (no score yet). Tunnels and bridges included, unlike when snapping stops; the road filter keeps roads above or below away. Optional bearing (degrees, 0 = north): only segments whose course start → end deviates by at most 60°.
  - `matchPoint(region, point, radiusMeters, roadRef, bearing)`: segments of the region within the radius (PostGIS `ST_DWithin` on `geography`, prefilter via the GIST index), with `roadRef` only segments with this number.
  - `matchLine(region, line, roadRef, bearing)`: GraphHopper map matching (`graphhopper-map-matching` 11.1, profile `car`, measurement error 20 m) on the graph of the region, points outside the graph are dropped. Matched in the order of the points and reversed (dual carriageways are one one-way street each), plus the opposite direction (`reverseId`). Virtual edges at the ends via `getOriginalEdgeKey`.
  - `linkOnly` (both, additional parameter): only segments with `link`, independent of `roadRef`, because on- and off-ramps usually have no number in OSM.
  - Errors: `RoutingUnavailableException` if the region has no network yet (without rollback of the caller's transaction).
  - Later: A→B, area, score.
- `RefNormalizer.normalize(region, ref)`: make road numbers comparable, several separated by `;`. New Zealand: `SH 1`, `SH1`, `State Highway 1`, `01N`, `01S` → `1`; `006` → `6`; `SH 20A` → `20A`. Otherwise spaces removed (`A 6` → `A6`). One rule for all regions until a region needs its own.

## Routing details

- **Stops:** no `curbsides` (changed 2026-11-07). `alternative_route` does not know them, and with them a proposal could not always be planned again: Wānaka → Christchurch, `alternatives` found paths, `route` with `force_curbside=false` failed with "Connection between locations not found". Between two stops `route` and `alternatives` thus make the same request.
- **Via points:** `curbside` `any`, `pass_through=true` (no turning at the via point), `point_hints` from the road hint.
- **Blocks and delays** (`ScenarioWeighting`): own `WeightingFactory`. `edge_key`s of blocked segments (all edges of a segment, direction-specific) are impassable, delayed ones cost weight + seconds (weight of the custom model weighting ≈ seconds). Several `edge_key`s per segment only exist for identical duplicate edges, a passage uses one of them: each gets the full delay. Weights only increase, works with LM. Applies to `route` and `alternatives` alike.
- **Fractions:** only at the first and last section of a route smaller than 0–1, position of the snapped start or end point on the edge geometry.
- **Snapping:** `snap_preventions` `tunnel`, `bridge`, `ferry` (a stop does not land in the tunnel below), at most 500 m from the requested point.
- **Region:** the first region with a graph whose bounding box contains all points.
- **Mapping in memory:** `edge_key` → segment id as an array per graph, loaded from `edge_mapping` when switching over (NZ estimated 20–30 MB), no database query per route. Only the `edge_key`s of blocked and delayed segments are queried.
- **Switch-over:** read/write lock per region, a running route never calculates on a closed graph.

## Configuration

  ```properties
  nfv.network.import-on-start=${NFV_NETWORK_IMPORT_ON_START:true}
  nfv.network.regions.nz.osm-file=${NFV_DATA_DIR:${user.home}/data}/osm/new-zealand-latest.osm.pbf
  nfv.network.regions.nz.graph-dir=${NFV_DATA_DIR:${user.home}/data}/graphs/nz
  # MMAP: graph outside the heap, RAM_STORE in tests
  nfv.network.regions.nz.data-access=MMAP
  nfv.network.regions.nz.ferries=true
  # Required, local time of the region
  nfv.network.regions.nz.zone=Pacific/Auckland
  # Required, area for stops and waypoints (North, South and Stewart Island; the Chatham Islands lie beyond 180°)
  # Frontend: same values as Photon bbox in frontend/src/map/geocoding.ts
  nfv.network.regions.nz.bounds.min-latitude=-47.6
  nfv.network.regions.nz.bounds.min-longitude=166.0
  nfv.network.regions.nz.bounds.max-latitude=-34.0
  nfv.network.regions.nz.bounds.max-longitude=179.0
  # Planned
  nfv.network.regions.nz.download-url=https://download.geofabrik.de/australia-oceania/new-zealand-latest.osm.pbf
  nfv.network.regions.nz.refresh-cron=0 0 3 * * SUN
  nfv.network.regions.nz.min-segment-ratio=0.95
  ```

- Region `nz` is in `application.properties`, the "Planned" lines not yet.
- **Extracts not in the repository** (no Git LFS: ~400 MB per NZ extract, every update would remain in LFS storage), `*.osm.pbf` is in `.gitignore`.
- **Storage on every host** (developer machine, server) outside the repo under `~/data`:
  - `~/data/osm/new-zealand-latest.osm.pbf`: extract, copied by hand.
  - `~/data/graphs/nz/<extractVersionId>`: GraphHopper graph, created on the first start (a few minutes), afterwards only loaded.
  - Without Docker (`mvn spring-boot:run`, IDE) `${user.home}/data` applies. Docker mounts `~/data/osm` (read-only) and `~/data/graphs` to `/data` and sets `NFV_DATA_DIR=/data`. Another host path via `NFV_HOST_DATA_DIR`; in the swarm stack it is required (on the server `/home/deploy/data`), see [deployment.md](deployment.md).
- Until `ExtractSource` downloads from Geofabrik, fetch or copy the extract by hand:

  ```bash
  mkdir -p ~/data/osm ~/data/graphs
  curl -fL -o ~/data/osm/new-zealand-latest.osm.pbf \
    https://download.geofabrik.de/australia-oceania/new-zealand-latest.osm.pbf
  ```

  - Optional check: `.md5` next to the file at Geofabrik (`…/new-zealand-latest.osm.pbf.md5`), `md5sum -c`.
  - The date of the extract is in the file header (`datareader.data.date`, stored on import as `ExtractVersion.dataDate`), the file name carries no date.
  - New extract: replace the file and new import (follows with the update). An existing graph of the current version is loaded at startup, not re-imported.
- **Without an extract** the app still starts: import `FAILED` (in the log), routing responds with 503. `NFV_NETWORK_IMPORT_ON_START=false` skips the import.
- `ferries=true` for NZ: North and South Island are only connected by ferry. `ferries` and the encoded values belong to the graph, a change needs a new import.

## Tests

- Test extract central Wellington `backend/src/test/resources/osm/wellington.osm.gz`, contains Terrace Tunnel and Mount Victoria Tunnel (SH 1). Loaded once and committed, tests download nothing. Regenerate via Overpass (`overpass-api.de/api/interpreter`, user agent needed):

  ```
  [out:xml][bbox:-41.32,174.75,-41.27,174.80];(way[highway];rel[type=restriction];);(._;>;);out meta;
  ```

- Test region `wellington` in `src/test/resources/config/application.properties`, no import at startup. `RegionImportTest` imports explicitly: around 1.5 s, 13,096 segments.
- Tests that need the network call `loadOrImport` before every test as long as the region is not `READY` in the context: only the first context per test run imports (around 2 s), every further one loads the graph of the current version from `graph-dir`. Only `RegionImportTest` and `RegionImportPlacesTest` explicitly re-import.
- `graph-dir` separate per Surefire fork (`target/graphs/wellington-<fork>`, set in `pom.xml`), parallel JVMs share no graph directory.
- Places: the test extract contains none (the query above only fetches roads). `PlaceReaderTest` reads the hand-written `osm/places.osm`, `RegionImportPlacesTest` imports a copy of Wellington with two added places, `PlacesTest` and `StopoversTest` create places via SQL.
- `SegmentMatchingTest`: NZTA events of the sample on the extract (Terrace Tunnel as a point with `ref`, Urban Motorway as a line beyond the extract, both carriageways), see [traffic-feed.md](traffic-feed.md#tests).

## Spike results (October 2026, Wellington)

| Topic | Result |
|---|---|
| Import | deterministic: the same file yields the same `edge_key`s and keys |
| Keys | unique after omitting the 810 zero edges and with geometry hash, 11 identical duplicate edges share a key |
| Stability | 87.8 % after 6 months, 148 of 2,251 losses due to deleted ways |
| Route | every `edge_key` of a route has a segment |
| Blocks | Terrace Tunnel blocked: 261 s instead of 198 s, LM in 5 ms |
| `pass_through` | via point without: 64 m with turning, with: 669 m without turning |
| Map matching | 62 of 62 edges found again |
| Jackson | Jackson 2 (GraphHopper) and 3 (Spring Boot) side by side, REST stays JSON |

## Open

- Calibrate speeds (own encoded value during import).
- Trips across region boundaries: currently an error, solution via a larger region.
- Scenarios as stored rules → custom model or block list per request.
- `osm_way_id` has 31 bits in GraphHopper (up to approx. 2.1 billion), today's ids are well below that.
