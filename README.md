# OsmAnd DeFlock

An unofficial fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) that shows automated
license plate reader (ALPR) cameras on the map and can route around what they see.

[![Release APK](https://github.com/0xnullsect0r/OsmAnd-DeFlock/actions/workflows/release.yml/badge.svg)](https://github.com/0xnullsect0r/OsmAnd-DeFlock/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/0xnullsect0r/OsmAnd-DeFlock)](../../releases/latest)
[![Licence: GPLv3](https://img.shields.io/badge/licence-GPLv3-blue.svg)](LICENSE)

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

## Installing

Grab the APK from the [latest release](../../releases/latest), open it on your phone, and allow
the install. There is nothing to compile.

- The APK contains both `arm64-v8a` and `armeabi-v7a`, so it installs on any Android phone.
- It installs as **OsmAnd DeFlock** (application id `net.osmand.dev`), so it sits alongside a
  Play Store OsmAnd instead of replacing it. Your existing maps and settings are untouched.
- It is **debug-signed**, with the key committed in this repository. Android may warn about
  that, and it cannot install over an APK signed with a different key — uninstall the old one
  first if you hit that. There is no release-signing key for this fork.

Every tag push builds and publishes a release through
[`.github/workflows/release.yml`](.github/workflows/release.yml), and the unit tests have to
pass before it goes out.

---

## Using it

1. **Menu → Plugins → ALPR cameras (DeFlock)** → enable. Nothing appears until you do; the
   plugin is off by default.
2. **Settings → Offline camera data** → download cameras for a region you have a map for.
   Cameras appear from zoom 13, icons from zoom 15, and keep working with no connection.
3. To route around them: plan a route → **Route options → Avoid ALPR camera view** → turn it
   on and set the detour you will accept.

Settings live under the plugin: offline data per region, detection range, field-of-view angle,
the detour budget, and a button to clear the browsing cache. Each is **per navigation profile**,
so turning avoidance on for Car does not turn it on for Bicycle.

See [docs/deflock/user-guide.md](docs/deflock/user-guide.md) for what every setting means.

---

## What this sends, and where

Worth being explicit about, given what the app is for.

Camera locations are not in OsmAnd's offline maps — OsmAnd's map builder deliberately excludes
surveillance POIs — so they are fetched from the [Overpass API](https://overpass-api.de).
That means:

- **Panning the map with the plugin on** sends the areas you look at, as bounding boxes, to
  Overpass, and caches the answers for 30 days.
- **Downloading a region** sends that region's bounding boxes. If the stock endpoint is busy,
  a request may be retried against the public mirror `overpass.kumi.systems` — so that operator
  sees those areas too. Setting your own endpoint in settings disables the fallback entirely:
  a server you chose deliberately is then the only one asked.
- **Routing never touches the network.** Avoidance uses only what is already on the device.

If you would rather not stream your map browsing to anyone: download the regions you care
about once, then turn **Show cameras on map** off or stay offline. Everything except acquiring
new data works with no connection at all.

This describes the camera feature only. OsmAnd itself does its own networking for map
downloads and the like, unchanged from upstream.

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
  Avoiding a camera's mapped view is not a guarantee you were not photographed, and this tool
  should not be treated as one.
- **Routing only uses data already downloaded.** It will not fetch mid-calculation, so a region
  you never downloaded is a region with no avoidance. The route option says so rather than
  reporting a clean route.

---

## Verification status

| Check | Result |
|---|---|
| `:OsmAnd-java:test` | 608 tests, 0 failures (62 from this fork) |
| `:OsmAnd:assembleNightlyFreeOpenglArmonlyDebug` | passes — the APK published by each release |
| Cameras drawn on the map, with direction | **confirmed on device** |
| Offline region download | **confirmed on device** |
| Routing avoidance and the detour budget | **confirmed on device** |
| Working offline / in airplane mode | **confirmed on device** |
| Region file and Overpass JSON parsing | **not automatically tested** — both use `org.json`, which needs an Android runtime; the region-key, coverage, query-building and camera-tag logic beneath them is tested on the JVM |

---

## Building

Routing profiles, POI types, rendering styles and icons live in
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources), which is vendored here as
the `resources` submodule — so clone with submodules and there is nothing to set up by hand:

```bash
git clone --recurse-submodules https://github.com/0xnullsect0r/OsmAnd-DeFlock.git

# already cloned without it?
git submodule update --init --depth 1
```

The Gradle daemon runs on a JetBrains JDK 21 that Gradle provisions itself
(`gradle/gradle-daemon-jvm.properties`), so your system JDK does not matter. You do need an
Android SDK with platform 35 + build-tools 35.0.0.

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # SDK root, not a subdirectory of it

./gradlew :OsmAnd-java:test                            # unit tests
./gradlew :OsmAnd:assembleNightlyFreeOpenglArm64Debug  # fastest variant for development
```

Releases build `assembleNightlyFreeOpenglArmonlyDebug` instead, which packs both ARM ABIs into
one APK. If you already keep OsmAnd-resources in a sibling directory — the layout upstream
documents — that still works; the build prefers the submodule and falls back to `../resources`.

Give Gradle enough heap or the build will crawl — the default is too small for this project:

```properties
# ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
```

Full details, including the UTF-8 locale requirement and the CI-only pitfalls, are in
[docs/deflock/building.md](docs/deflock/building.md).

---

## Contributing

**The highest-leverage contribution is not code.** This app can only avoid cameras somebody has
mapped, so adding cameras to OpenStreetMap — through [deflock.org](https://deflock.org) or any
OSM editor — improves it for everyone using it, not just you.

For the app itself: issues and pull requests belong on
[this repository](../../issues), never on OsmAnd or DeFlock upstream. If you are reporting
routing behaviour, the detour budget, profile and rough area matter more than anything else.

[docs/deflock/architecture.md](docs/deflock/architecture.md) lists every file the feature
touches, and [docs/deflock/building.md](docs/deflock/building.md) covers rebasing onto a newer
upstream OsmAnd.

---

## Documentation

| Document | Contents |
|---|---|
| [user-guide.md](docs/deflock/user-guide.md) | Enabling the plugin, what every setting does |
| [architecture.md](docs/deflock/architecture.md) | Components, data flow, every file the feature touches |
| [routing.md](docs/deflock/routing.md) | How avoidance works and why it is built this way |
| [building.md](docs/deflock/building.md) | Prerequisites, build and test commands, releases, pitfalls |

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
