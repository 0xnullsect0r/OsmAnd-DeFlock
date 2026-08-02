# Building and testing

Everything here was verified in a clean Linux container. `AGENTS.md` documents the wider
project layout; this page covers what you actually need to get a build out, including the
pitfalls that cost real time.

## Prerequisites

### 1. The `resources` submodule

Routing profiles (`routing.xml`), POI types, rendering styles and the `mx_*` icon set are not
in this repository — they live in
[OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources), which is vendored here as
the `resources` submodule.

```bash
git clone --recurse-submodules <this repository>

# already cloned without it?
git submodule update --init --depth 1
```

It is a large repository (a few hundred MB, mostly icons), and `.gitmodules` marks it
`shallow = true`, so `--depth 1` is the sensible way to fetch it. The submodule is pinned to a
specific commit, so builds are reproducible.

Forget this step and the build stops immediately with:

```
OsmAnd resources not found in /…/resources or /…/../resources.
Run: git submodule update --init --depth 1
```

**Already have a sibling checkout?** Upstream's documented layout puts OsmAnd-resources in a
directory next to the repository rather than inside it. That still works — the root
`build.gradle` resolves `osmandResourcesDir` by preferring `resources/` and falling back to
`../resources`, so existing OsmAnd working copies build unchanged.

### 2. Android SDK

Platform 35 and build-tools 35.0.0 (see `versions.gradle`).

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### 3. JDK 17 or 21 — **not newer**

This is a range, not a minimum. The Gradle wrapper pins **Gradle 8.9**, which only runs on
Java 8–22, and AGP 8.7.3 is tested against JDK 17 and 21. A newer JDK fails before it compiles
a single line:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 70
```

Major version 70 is Java 26; 69 is Java 25, 68 is Java 24, 67 is Java 23. Any of these means
Gradle's Groovy cannot parse the build scripts.

You do not need to change your system default JDK — point Gradle at a supported one:

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
```

or per invocation:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :OsmAnd-java:test
```

Upgrading the Gradle wrapper to chase a newer JDK is not the fix — the Gradle, AGP and Kotlin
versions are pinned together upstream in `build.gradle` and `versions.gradle`, and moving one
means moving all of them.

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

## Troubleshooting

Every one of these was hit for real while building this fork. Search by the error text.

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version 70` (or 69/68/67) during `semantic analysis` of `_BuildScript_` | JDK too new — Gradle 8.9 runs on Java 8–22 only | Use JDK 17 or 21, see [prerequisite 3](#3-jdk-17-or-21--not-newer) |
| `OsmAnd resources not found in …` | submodule not checked out | `git submodule update --init --depth 1` |
| `SDK location not found` | `ANDROID_HOME` unset, or still set to a placeholder path | Point it at a real SDK with platform 35 |
| `Failed to create MD5 hash for file '…ludwigstra??e.obf.gz' as it does not exist` | non-UTF-8 locale; the file is fine | `export LANG=C.utf8 LC_ALL=C.utf8` |
| Build runs for tens of minutes at high CPU without failing | Gradle heap too small, GC thrashing | Raise `org.gradle.jvmargs`, see [prerequisite 4](#4-gradle-heap--do-not-skip-this) |
| `Plugin [id: 'de.undercouch.download'] was not found` | building with `--offline` before dependencies are cached | Run once online first |

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
