# Architecture

## Why the data does not come from the maps

OsmAnd is offline-first, so the obvious design would read cameras from downloaded `.obf` maps.
That is not possible, and the reason drives the whole data layer.

In `OsmAnd-resources/poi/poi_types.xml`, surveillance is declared:

```xml
<poi_type name="surveillance" tag="man_made" value="surveillance" no_indx="true"/>
```

`no_indx="true"` keeps the type out of the OBF **POI index** entirely. On top of that,
`surveillance:type` is not modelled at all, and the global `direction` additional lacks
`top="true"`, so it is not indexed either. `.obf` files are built on OsmAnd's servers from
that configuration, so a fork cannot change what they contain.

Hence: **fetch from Overpass, store on disk.**

### Why not write into the `.obf` either

Injecting cameras into a downloaded map, or shipping a companion `.obf`, was considered and
closed. Four independent blockers:

1. **No OBF writer exists in this repository.** `BinaryMapIndexWriter` lives in OsmAnd-tools, a
   separate desktop repo that is not vendored here, so nothing on the device can produce one.
2. **Surveillance POIs are excluded by design**, per the `no_indx` declaration above — a
   companion `.obf` would need a forked OsmAnd-resources before it was even searchable.
3. **Downloaded maps are replaced wholesale on update**, so anything injected into them
   disappears silently the next time the region updates.
4. Rewriting a multi-hundred-MB `.obf` on a phone is not practical.

### Two tiers of storage

| | Region files | Tile cache |
|---|---|---|
| Acquired | deliberately, per map region | incidentally, as you pan |
| Expiry | never | 30 days |
| Used for | offline use and routing | online browsing only |
| Keyed by | OsmAnd region download name | zoom-10 tile |

Region files are authoritative. Where one covers the ground, nothing is fetched and nothing
expires — that is what makes the feature usable with no network at all. The tile cache remains
for people who never download a region, and is never consulted where a region file already
answers.

### Linking data to the downloaded map

A region file is named after OsmAnd's own identity for the region, so
`Us_indiana_northamerica.obf` pairs with `deflock/us_indiana_northamerica.deflock`. The chain
is the one `DownloadResources.isOsmandMapRegion` already uses:

```java
String key = WorldRegion.getRegionDownloadName(mapFileName);   // "us_indiana_northamerica"
WorldRegion region = app.getRegions().getRegionDataByDownloadName(key);
QuadRect bbox = region.getBoundingBox();                       // what to ask Overpass for
```

Note that downloaded maps do **not** carry the OBF version suffix: the server publishes
`Us_indiana_northamerica_2.obf.zip`, but `DownloadActivityType.getBasename` cuts at the last
underscore, so the file on disk is `Us_indiana_northamerica.obf`. `AlprRegionKey` strips a
trailing `_<digits>` anyway, so a file copied by hand from the download server still resolves.

### Coverage, and why it is reported

`AlprCoverageIndex` answers FULL / PARTIAL / NONE for an area. This exists because the quiet
failure is the dangerous one: a route across ground with no camera data produces exactly the
same "no cameras in view" as a route that genuinely avoids every camera. The route option row
reports coverage so the two are distinguishable.

## Module split

The feature is deliberately split across two modules:

| Module | Holds | Why |
|---|---|---|
| `OsmAnd-java` (`net.osmand.router.deflock`) | camera model, cone geometry, road resolution, relaxation | pure Java, no Android — unit testable on the JVM, and reachable from router code |
| `OsmAnd` (`net.osmand.plus.plugins.deflock`) | networking, SQLite cache, map layer, plugin, UI | needs Android APIs |

## Data flow

```
Overpass API                                    a shared/imported .deflock file
     │  node["surveillance:type"="ALPR"](bbox)              │
     ├───────────────┐                                      │
     ▼               ▼                                      ▼
OverpassAlprClient   AlprRegionManager.downloadRegion   DeflockImportTask
     │                        │                             │
     ▼                        └──────────┬──────────────────┘
AlprCameraDbHelper                       ▼
  tile cache, 30-day TTL          deflock/<region>.deflock   (AlprRegionFile)
  browsing only                          │  authoritative, never expires
     │                                   ▼
     │                            AlprRegionManager  ──► AlprCoverageIndex
     │                                   │                (FULL/PARTIAL/NONE)
     └───────────┬───────────────────────┘
                 ▼
        AlprCameraRepository       regions first, then tile cache, then fetch
                 │
     ┌───────────┴───────────┐
     ▼                       ▼
AlprCameraLayer        AlprAvoidanceHelper   (offline only - never blocks on network)
(cameras + cones)              │
                               ▼
                      AlprRoadExclusionResolver
                               │  CameraCoverage.coversSegment per road
                               ▼
              RoutingConfiguration.Builder.setTransientExcludedRoads
                               │
                               ▼
                      GeneralRouter.acceptLine  → route + coverage
```

## Components

### `AlprCameraPoint` (OsmAnd-java)

The camera: OSM id, position, optional facing in degrees, raw tags.

`parseDirection` handles what actually appears in OSM: plain degrees (`240`, `2`, `137.5`),
values needing normalisation (`370`, `-10`), the 16 compass points (`N`, `NNE`, `northeast`,
`north-east`), and sector ranges (`45-135`, `350-10`) reduced to their midpoint.
`fromTags` prefers `direction` and falls back to `camera:direction` — in live Overpass
sampling `direction` was on essentially every node and `camera:direction` on a few percent.

A camera with no parseable direction keeps `null`, which every consumer treats as
**omnidirectional** rather than ignoring the camera.

### `CameraCoverage` (OsmAnd-java)

The view sector: within `rangeM` of the camera **and** within `coneDeg / 2` of its facing.
Defaults 60 m and 60°.

`coversSegment` answers "does any part of this road segment fall in the sector". It cheaply
rejects on perpendicular distance, then clips the segment to the part within range and samples
it every 4 m, capped at 64 samples. The clipping is what keeps a 5 km road segment from
costing thousands of samples, and the sampling is what catches the case where the *nearest*
point of a road is outside the cone but a further part of it is inside.

### `AlprCameraDbHelper` / `AlprCameraRepository` (Android)

The cache. Downloads are cut into **zoom 10 tiles** (~40 km), each valid for **30 days**;
a tile records when it was fetched, so an area with genuinely no cameras is not re-downloaded
forever.

The repository keeps cameras in per-tile buckets rather than a spatial tree — tiles are large
enough that any realistic query touches a handful of buckets, and bucket keys make refresh and
eviction trivial. It downloads at most 16 tiles per request, serves stale data while a refresh
is in flight, and backs off exponentially (30 s to 15 min) when Overpass returns 429/503/504,
without caching an empty tile just because the server was loaded.

### `AlprRegionFile` / `AlprRegionManager` (Android)

The offline half. `AlprRegionFile` is gzipped JSON with a header (format version, region key,
bounds, count) and cameras sorted by OSM id, so a region's file is byte-identical between runs
and "has anything changed?" is answerable. Writes go to a temp file and are renamed, because a
half-written file after a killed download would otherwise look like valid coverage. Reading
also accepts a plain GeoJSON `FeatureCollection`, which costs almost nothing given
`AlprCameraPoint.fromTags` already exists and lets a DeFlock or Overpass export be imported
directly.

`AlprRegionManager` enumerates downloaded maps through `ResourceManager.getIndexFileNames`,
maps each to a region, and owns download / import / delete. Two details worth knowing:

- **Downloads are split into a grid** of roughly 2° cells and merged, because a single Overpass
  query over a country times out. Cells are merged in memory and written only once all succeed,
  so a failure part-way through leaves no file rather than a partial one.
- **Cameras load lazily** with a small LRU (4 regions). The index of region → bounds is loaded
  up front and is enough to answer coverage without reading any cameras.

### `AlprCameraLayer` (Android)

Registered by the plugin at z-order **3.4** — just below OsmAnd's POI layer. Visible from zoom
13; camera icons from zoom 15.

Cameras are fetched through `OsmandMapLayer.MapLayerData`, the same bbox-driven async loader
`OsmBugsLayer` uses.

Drawing the cone at a true geographic bearing uses the idiom from OsmAnd's AIS layer: rotate
the canvas by `tileBox.getRotate()` into north-up space, take `…NoRot` pixel coordinates, then
rotate by the camera's bearing. The wedge drawable points north unrotated, so no offset is
needed. Cone size is computed from the configured range in metres and the tile box's scale, so
it stays physically accurate as you zoom.

Both render paths are implemented: legacy `Canvas`, and OpenGL via `MapMarkersCollection` with
`setOnMapSurfaceIconDirection`.

Note that both icons are **vector drawables**, so they are rasterised with
`AndroidUtils.drawableToBitmap` — `UiUtilities.decodeResource` is `BitmapFactory` underneath
and returns null for vectors.

### `DeFlockPlugin` (Android)

Standard `OsmandPlugin`, id `osmand.deflock`, registered with one line in
`PluginsHelper.initPlugins()`. Off by default. Owns the preferences, the layer lifecycle, the
Configure-map toggle, and the last avoidance outcome for the UI to report.

## Files

### New

| Path | Role |
|---|---|
| `OsmAnd-java/src/main/java/net/osmand/router/deflock/AlprCameraPoint.java` | camera model, direction parsing |
| `OsmAnd-java/src/main/java/net/osmand/router/deflock/CameraCoverage.java` | view sector geometry |
| `OsmAnd-java/src/main/java/net/osmand/router/deflock/AlprRoadExclusionResolver.java` | cameras → road ids, relaxation ladder |
| `OsmAnd-java/…/router/deflock/AlprRegionKey.java` | map file name ↔ region key ↔ data file name |
| `OsmAnd-java/…/router/deflock/AlprCoverageIndex.java` | FULL/PARTIAL/NONE coverage for an area |
| `OsmAnd/…/plugins/deflock/AlprRegionFile.java` | region file read/write, GeoJSON import |
| `OsmAnd/…/plugins/deflock/AlprRegionManager.java` | region enumeration, download, import, delete |
| `OsmAnd/…/plugins/deflock/DeFlockRegionsFragment.java` | offline camera data screen |
| `OsmAnd/…/importfiles/tasks/DeflockImportTask.java` | `.deflock` share-sheet import |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/OverpassAlprClient.java` | Overpass query and JSON parsing |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/AlprCameraDbHelper.java` | SQLite tile cache |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/AlprCameraRepository.java` | cache orchestration, downloads, backoff |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/AlprCameraLayer.java` | map layer, cones, tap handling |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/DeFlockPlugin.java` | plugin, preferences |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/DeFlockSettingsFragment.java` | settings screen |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/AlprAvoidanceHelper.java` | route corridor, camera collection, outcome |
| `OsmAnd/src/net/osmand/plus/plugins/deflock/AvoidCamerasBottomSheet.java` | toggle + detour slider |
| `OsmAnd/src/net/osmand/plus/routepreparationmenu/data/parameters/AvoidCamerasRoutingParameter.java` | route options entry |
| `OsmAnd/res/drawable/ic_alpr_camera.xml`, `ic_alpr_view_cone.xml` | icons |
| `OsmAnd/res/layout/alpr_detour_slider.xml`, `res/xml/deflock_settings.xml` | UI |

Tests live in `OsmAnd-java/src/test/java/net/osmand/router/deflock/`.

### Modified upstream files

| Path | Change |
|---|---|
| `OsmAnd-java/…/router/RoutingConfiguration.java` | transient excluded-road set on `Builder` |
| `OsmAnd/…/routing/RouteProvider.java` | multi-pass avoidance, forced standard routing |
| `OsmAnd/…/plugins/PluginsHelper.java` | register the plugin |
| `OsmAnd/…/routepreparationmenu/RouteOptionsBottomSheet.java`, `RoutingOptionsHelper.java` | route option and outcome text |
| `OsmAnd/…/settings/fragments/SettingsScreenType.java` | settings screen entry |
| `OsmAnd/src/net/osmand/data/PointDescription.java` | `POINT_TYPE_ALPR_CAMERA` |
| `OsmAnd-api/…/OsmAndCustomizationConstants.java` | layer and route-option ids |
| `OsmAnd/res/values/strings.xml`, `colors.xml` | strings, accent colour |
| `OsmAnd-java/…/IndexConstants.java` | `DEFLOCK_INDEX_DIR`, `DEFLOCK_FILE_EXT` |
| `OsmAnd/…/importfiles/ImportHelper.java` | dispatch `.deflock` to the import task |

The footprint on upstream files is kept small on purpose, so rebasing onto a newer OsmAnd
stays manageable.

## Design notes

**A new plugin, not a change to an existing one.** The feature is self-contained, and users
who do not want it get nothing.

**No changes to OsmAnd-resources.** `routing.xml` and `poi_types.xml` live in a separate
repository. The route option follows the `AvoidRoadsRoutingParameter` pattern — a hand-written
`LocalRoutingParameter` backed by a plugin preference — rather than a `routing.xml` parameter,
and the icons are shipped here rather than added to the shared icon set. That keeps the fork
to one repository.

**English strings only.** Upstream translations come from Weblate; adding machine translations
would fight that process.
