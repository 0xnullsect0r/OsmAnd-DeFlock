# Building and testing

Everything here was verified in a clean Linux container. `AGENTS.md` documents the wider
project layout; this page covers what you actually need to get a build out, including the
pitfalls that cost real time.

## Prerequisites

### 1. OsmAnd-resources as a sibling directory

**This is the one that catches everyone.** Routing profiles (`routing.xml`), POI types and
rendering styles are not in this repository. Gradle reads them from `../../resources` relative
to the module, i.e. a sibling of the repository root named exactly `resources`:

```
parent/
├── OsmAnd-DeFlock/
└── resources/
```

```bash
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git resources
```

Without it, `:OsmAnd-java:processResources` fails and nothing builds.

### 2. Android SDK

Platform 35 and build-tools 35.0.0 (see `versions.gradle`).

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### 3. JDK 17+

The build targets Java 17. JDK 21 works.

### 4. Gradle heap — do not skip this

The default heap is far too small for this project. Gradle will not fail; it will spend
**tens of minutes thrashing the garbage collector** at high CPU while looking like it is
working. The daemon log gives it away:

```
The Daemon will expire after the build after running out of JVM heap space.
The currently configured max heap space is '512 MiB'
```

```properties
# ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx3g
org.gradle.workers.max=3
```

With this, a clean `assemble` takes minutes rather than tens of minutes.

### 5. A UTF-8 locale

`OsmAnd-resources` contains a test fixture with a non-ASCII filename
(`ludwigstraße.obf.gz`). Under a `POSIX`/`C` locale the JVM cannot read it and the build fails
with a misleading message:

```
Failed to create MD5 hash for file '.../ludwigstra??e.obf.gz' as it does not exist.
```

The file is fine. Set the locale:

```bash
export LANG=C.utf8 LC_ALL=C.utf8
```

## Commands

```bash
# Unit tests (fast, no Android SDK needed for OsmAnd-java itself)
./gradlew :OsmAnd-java:test

# Only this fork's tests
./gradlew :OsmAnd-java:test --tests 'net.osmand.router.deflock.*'

# Debug APK
./gradlew :OsmAnd:assembleNightlyFreeOpenglArm64Debug

# Compile only — much faster than assembling when checking a change
./gradlew :OsmAnd:compileNightlyFreeOpenglArm64DebugJavaWithJavac
```

The APK lands in `OsmAnd/build/outputs/apk/nightlyFreeOpenglArm64/debug/` and is around
219 MB (debug, unshrunk).

Note that `:OsmAnd:compile…` depends on `:OsmAnd-java:test`, so a failing unit test blocks the
Android build.

### Variants

Build variants are `<flavor><renderer><abi><buildType>`, e.g.
`NightlyFreeOpenglArm64Debug`. Flavors: `nightlyFree`, `androidFull`, `gplayFree`,
`gplayFull`, `huawei`. Renderers: `opengl`, `opengldebug`, `legacy`. Use
`./gradlew :OsmAnd:tasks --all` to list them.

`nightlyFree` + `opengl` + `arm64` is the practical default for testing this fork.

### Native core

`OsmAndCore` is fetched as a prebuilt snapshot AAR — you do not need to build C++, and the NDK
is not required for the commands above.

## Tests

Tests for this fork live in `OsmAnd-java/src/test/java/net/osmand/router/deflock/`:

| Class | Covers |
|---|---|
| `CameraCoverageTest` | view sector geometry, bearing wraparound, segment clipping, omnidirectional fallback, OSM `direction` parsing |
| `AlprRoadExclusionResolverTest` | relaxation ladder ordering, monotonicity, immutability |
| `TransientExcludedRoadsTest` | exclusions reach the router, combine with but never mutate the user's avoided roads, clear between builds |

Current status: **577 tests, 0 failures** (31 from this fork).

Two gaps worth knowing about:

- **`OverpassAlprClient` JSON parsing is not automatically tested.** It uses `org.json`, which
  needs an Android runtime or Robolectric; the JVM stub throws. The tag-to-camera conversion
  it delegates to (`AlprCameraPoint.fromTags`) *is* tested.
- **`AlprRoadExclusionResolver.resolve()` is not tested** — it needs a `RoutingContext` with
  real map data. Its geometry is covered; the loop is not.

### Testing against live Overpass

Useful for checking the query and inspecting real tag distributions:

```bash
curl -sS -G 'https://overpass-api.de/api/interpreter' \
  --data-urlencode 'data=[out:json][timeout:60];node["surveillance:type"="ALPR"](39.6,-86.4,39.95,-85.9);out tags;'
```

Indianapolis (the bbox above) has dense coverage and is a good manual test area.

## Manual verification

Not runnable in a headless container — this needs a device or emulator:

1. Enable **ALPR cameras (DeFlock)** in Plugins.
2. Pan to a mapped city; confirm cameras appear at zoom 13 and wedges point per `direction`.
   Cross-check a few against [deflock.org](https://deflock.org).
3. Change **Detection range** and confirm the wedges resize on the map.
4. Plan a route across a camera cluster with avoidance off, then on; confirm the route changes.
5. Move the detour slider from 0 to 30 minutes and confirm the chosen route and the reported
   detour change with it.
6. Turn off networking and confirm a previously visited area still shows cameras.

## Rebasing onto newer OsmAnd

The fork touches few upstream files deliberately (listed in
[architecture.md](architecture.md)). The ones most likely to conflict are `RouteProvider.java`
and `RouteOptionsBottomSheet.java`.

After rebasing, re-check the two assumptions in [routing.md](routing.md) — that native routing
is default, and that HH routing filters points by tags without ids. If either changes upstream,
the avoidance design needs revisiting, and `TransientExcludedRoadsTest` will not catch it
because it exercises the Java path only.
