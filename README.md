# OsmAnd-DeFlock

An unofficial fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) that shows automated
license plate reader (ALPR) cameras on the map and can route around what they see.

> **Not affiliated with OsmAnd or the DeFlock project.** This is an independent fork. Please
> do not report issues with it to either upstream project. Upstream's own README is kept
> here as [README.upstream.md](README.upstream.md).

Camera locations come from OpenStreetMap, where they are crowdsourced by the
[DeFlock](https://deflock.org) project — over 336,000 nodes worldwide as of early 2026,
tagged `man_made=surveillance` + `surveillance:type=ALPR`, nearly all carrying a `direction`.

---

## What it adds

**Cameras on the map, with their facing.** Each camera is drawn with a wedge covering the
area it is assumed to watch. The wedge is sized in real metres, so what you see is exactly
what the router treats as covered. A camera whose `direction` is not mapped is drawn as a
circle rather than pointed in a direction nobody recorded.

**Offline camera data per map region.** Download cameras for a region you already have a map
for, and they are stored beside it — `Us_indiana_northamerica.obf` gets
`deflock/us_indiana_northamerica.deflock`. Downloaded data never expires and routing never
reaches for the network, so the whole feature works in airplane mode. A region file can also be
shared to a device that never goes online.

**"Avoid ALPR camera view" when planning a route**, with a slider for how much extra travel
time you will accept. Set it to 10 minutes and the router will detour around cameras as long
as that costs under 10 minutes; past that it gives ground — motorways first — until the
detour fits. Afterwards the route option reports what actually happened:

```
Avoid ALPR camera view
No ALPR cameras in view • +4 min detour
```

or, when nothing fit the budget:

```
Avoid ALPR camera view
7 ALPR cameras in view
```

or, when the route crossed ground you have no camera data for — which is emphatically **not**
the same as a clean route:

```
Avoid ALPR camera view
No offline camera data for this route
```

Everything is off by default and lives in a plugin you have to enable.

---

## Using it

1. **Menu → Plugins → ALPR cameras (DeFlock)** → enable.
2. **Settings → Offline camera data** → download cameras for a region you have a map for.
   Cameras appear from zoom 13 and keep working with no connection.
3. To route around them: plan a route → **Route options → Avoid ALPR camera view** → turn it
   on and set the detour you will accept.

Settings live under the plugin: offline data per region, detection range, field-of-view angle,
the detour budget, and a button to clear the browsing cache.

See [docs/deflock/user-guide.md](docs/deflock/user-guide.md) for what each setting means.

---

## Known limitations

These are real, and worth reading before relying on this.

- **Avoidance blocks whole OSM ways**, not just the watched stretch — the same granularity as
  OsmAnd's existing "Avoid road" feature, because it uses the same mechanism. The relaxation
  ladder keeps this from producing absurd detours, but a camera can cause more road to be
  avoided than it actually watches.
- **Camera avoidance turns off "fast" (hierarchical) routing** for that calculation, because
  hierarchical routing cannot honour road-id exclusion. Long routes take noticeably longer to
  calculate while avoidance is on.
- **The field of view is a guess.** OpenStreetMap does not record detection range or lens
  angle for these cameras. The defaults (60 m, 60°) are estimates you can change; they are not
  measurements of any real device.
- **Coverage is only as good as OpenStreetMap.** An unmapped camera is an invisible camera.
  Avoiding a camera's mapped view is not a guarantee you were not photographed.
- **Routing only uses data already downloaded.** It will not fetch mid-calculation, so a region
  you never downloaded is a region with no avoidance. The route option says so rather than
  reporting a clean route.
- **Not verified on a device.** The feature compiles and its logic is unit tested, but it has
  not been run on real hardware. See [Verification status](#verification-status).

---

## Building

Routing profiles, POI types, rendering styles and icons live in
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources), which is vendored here as
the `resources` submodule — so clone with submodules and there is nothing to set up by hand:

```bash
git clone --recurse-submodules <this repository>

# already cloned without it?
git submodule update --init --depth 1
```

The Gradle daemon runs on a JetBrains JDK 21 that Gradle provisions itself
(`gradle/gradle-daemon-jvm.properties`), so your system JDK does not matter. You do need an
Android SDK with platform 35 + build-tools 35.0.0.

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # SDK root, not a subdirectory of it

./gradlew :OsmAnd-java:test                  # unit tests
./gradlew :OsmAnd:assembleNightlyFreeOpenglArm64Debug
```

If you already keep OsmAnd-resources in a sibling directory — the layout upstream documents —
that still works; the build prefers the submodule and falls back to `../resources`.

Give Gradle enough heap or the build will crawl — the default is too small for this project:

```properties
# ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
```

Full details, including the UTF-8 locale requirement, are in
[docs/deflock/building.md](docs/deflock/building.md).

---

## Verification status

| Check | Result |
|---|---|
| `:OsmAnd:assembleNightlyFreeOpenglArm64Debug` | passes — debug APK builds |
| `:OsmAnd-java:test` | 593 tests, 0 failures (47 from this fork) |
| Region file and Overpass JSON parsing | **not automatically tested** — both use `org.json`, which needs an Android runtime; the region-key, coverage and camera-tag logic beneath them is tested on the JVM |
| On-device behaviour | **not tested** — no hardware in the development environment |

---

## Documentation

| Document | Contents |
|---|---|
| [user-guide.md](docs/deflock/user-guide.md) | Enabling the plugin, what every setting does |
| [architecture.md](docs/deflock/architecture.md) | Components, data flow, every file the feature touches |
| [routing.md](docs/deflock/routing.md) | How avoidance works and why it is built this way |
| [building.md](docs/deflock/building.md) | Prerequisites, build and test commands, pitfalls |

---

## Fork history

Forked from `osmandapp/OsmAnd` at commit
[`5ab2862`](https://github.com/osmandapp/OsmAnd/commit/5ab286250f204c3755369083742376052cfc24e1)
(2026-08-02), imported as a single snapshot commit.

## Licence and credits

GPLv3, inherited from OsmAnd — see [LICENSE](LICENSE). OsmAnd is developed by
[OsmAnd BV and contributors](AUTHORS.md); ALPR camera data is crowdsourced into OpenStreetMap
by the [DeFlock](https://github.com/FoggedLens/deflock) community and licensed
[ODbL](https://www.openstreetmap.org/copyright). Camera data is fetched from the public
[Overpass API](https://overpass-api.de) — please be considerate of a free, donated service.
