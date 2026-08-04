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

`ANDROID_HOME` must be the SDK **root** — the directory *containing* `platforms/`,
`build-tools/` and `licenses/` — not one of those subdirectories. Pointing it at, say,
`/opt/android-sdk/build-tools/35.0.0` produces a confusing "licences have not been accepted"
error, because Gradle then looks for the licence files in the wrong place.

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # or /opt/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses                        # see SDK licences under Troubleshooting
```

### 3. JDK — handled for you

`gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to a **JetBrains JDK 21**, and the
foojay resolver in `settings.gradle` downloads it automatically. Your system default JDK no
longer matters: a host running Java 26 builds fine, because Gradle does not use it for the
daemon. The first build needs network access to fetch the toolchain.

If you are on an older checkout without that toolchain config, a too-new JDK fails before
compiling anything:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 70
```

Major version 70 is Java 26; 69 is Java 25, 68 is Java 24, 67 is Java 23. Fix by updating, or
by pointing Gradle at a JDK 17/21 without touching your system default:

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
```

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

## Using Android Studio

Studio is the easiest route, because it supplies both awkward prerequisites itself: a bundled
JDK 21 (the JBR) and a user-owned SDK whose licences are accepted through the UI.

1. **Open the repository root** (the directory with `settings.gradle`). Studio writes
   `sdk.dir` into `local.properties`, so `ANDROID_HOME` becomes unnecessary. Both
   `local.properties` and `.idea/` are gitignored.
2. **Gradle JDK.** The repository pins a JetBrains JDK 21 daemon toolchain
   (`gradle/gradle-daemon-jvm.properties`), so this normally needs no attention. If Studio asks,
   pick `jbr-21` under Settings → Build, Execution, Deployment → Build Tools → Gradle →
   *Gradle JDK*.
3. **SDK Manager** → install *Android 15 (API 35)*, and under *SDK Tools* with "Show Package
   Details" enabled, *build-tools 35.0.0*. Prefer a user-owned SDK such as `~/Android/Sdk`
   over a system-wide one, so nothing needs `sudo`.
4. **Treat further AGP upgrades as deliberate changes.** This fork already runs the current
   AGP 8.13.2 / Gradle 8.13, so Studio should not nag. If it offers a newer one, remember that
   AGP, Gradle and Kotlin move together, and that the last upgrade needed two build-script
   fixes: dropping `versionCode`/`versionName` from the `OsmAnd-api` library module, and wiring
   the navigation-resource tasks to `copyIcons` (see [AGP upgrades](#agp-upgrades)). Verify with
   a full `assemble` before committing one.
5. **Choose a build variant.** There are dozens. Use `nightlyFreeOpenglArm64Debug` for the
   `OsmAnd` module — or an `...X86...` variant if you are running an x86_64 emulator, since an
   arm64 build will not install on one.

The Gradle heap setting in [prerequisite 4](#4-gradle-heap--do-not-skip-this) still applies;
Studio reads the same `gradle.properties`.

## Troubleshooting

Every one of these was hit for real while building this fork. Search by the error text.

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version 70` (or 69/68/67) during `semantic analysis` of `_BuildScript_` | JDK too new, on a checkout predating the daemon toolchain | `git pull`, or see [prerequisite 3](#3-jdk--handled-for-you) |
| `OsmAnd resources not found in …` | submodule not checked out | `git submodule update --init --depth 1` |
| `SDK location not found` | `ANDROID_HOME` unset, or still set to a placeholder path | Point it at a real SDK with platform 35 |
| `Failed to install the following Android SDK packages as some licences have not been accepted` | licences not accepted — **or** `ANDROID_HOME` points inside the SDK rather than at its root | See [SDK licences](#sdk-licences) below |
| Error mentions a licence path like `…/build-tools/35.0.0/licenses` | `ANDROID_HOME` is set to a component directory | It must be the SDK **root**: `/opt/android-sdk`, not `/opt/android-sdk/build-tools/35.0.0` |
| `Failed to create MD5 hash for file '…ludwigstra??e.obf.gz' as it does not exist` | non-UTF-8 locale; the file is fine | `export LANG=C.utf8 LC_ALL=C.utf8` |
| Build runs for tens of minutes at high CPU without failing | Gradle heap too small, GC thrashing | Raise `org.gradle.jvmargs`, see [prerequisite 4](#4-gradle-heap--do-not-skip-this) |
| `Plugin [id: 'de.undercouch.download'] was not found` | building with `--offline` before dependencies are cached | Run once online first |
| A task reports `UP-TO-DATE` when you wanted it to actually run | Gradle incremental build | `--rerun-tasks`, e.g. `./gradlew :OsmAnd-java:test --rerun-tasks` |
| `uses this output of task ':OsmAnd:copyIcons' without declaring an explicit or implicit dependency` | AGP/Gradle were upgraded past the pinned versions | See [AGP upgrades](#agp-upgrades) below |

### AGP upgrades

`OsmAnd/build.gradle` wires `copyIcons` into a hand-maintained list of AGP tasks
(`mergeXAssets`, `mapXSourceSetPaths`, `mergeXResources`, `generateXResources`). AGP 8.10+ adds
`processXNavigationResources` and `compileXNavigationResources`, which read the same generated
`res` directory but are not in that list. Gradle 8.9 treated the missing dependency as a
warning; Gradle 8.13 makes it a build failure:

```
Task ':OsmAnd:processAndroidFullLegacyArm64DebugNavigationResources' uses this output of
task ':OsmAnd:copyIcons' without declaring an explicit or implicit dependency.
```

This fork wires those tasks up too, at project scope in `OsmAnd/build.gradle`, so the error
should not appear. If you see it anyway you are on a checkout without the fix — `git pull`.

Two notes for anyone reproducing this. The block must be at **project scope**; the same
`tasks.matching { … }.configureEach { dependsOn copyIcons }` inside
`android { applicationVariants.configureEach { … } }` has no effect. And to reproduce the
failure at all, `copyIcons` and the navigation task must be in the **same invocation** —
running the navigation task alone leaves `copyIcons` out of the task graph, so the validation
never fires and the build misleadingly passes:

```bash
./gradlew :OsmAnd:copyIcons \
          :OsmAnd:processAndroidFullLegacyArm64DebugNavigationResources --rerun-tasks
```

### SDK licences

Gradle refuses to build until the SDK licence has been accepted. It checks for hash files in
`$ANDROID_HOME/licenses/` — note **root**, so a wrong `ANDROID_HOME` produces this same error
even on a fully licensed SDK.

```bash
sudo $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

`sdkmanager` is not on `PATH` by default; it lives in `cmdline-tools`. If those are not
installed, writing the file directly is equivalent — the hash is all Gradle looks at, and this
one covers both platform 35 and build-tools 35.0.0:

```bash
mkdir -p "$ANDROID_HOME/licenses"
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$ANDROID_HOME/licenses/android-sdk-license"
```

A system-wide SDK such as `/opt/android-sdk` is usually root-owned, so both of the above need
`sudo`, and Gradle will need `sudo` again any time it wants to add a component. A user-owned
SDK (`~/Android/Sdk`) avoids that.

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

ABIs: `arm64`, `armv7`, `x86`, `armonly` (arm64 + armv7), `fat` (all four).

`nightlyFree` + `opengl` + `arm64` is the practical default for testing this fork. Releases use
`armonly` instead, so one APK installs on any phone — see [Releases](#releases).

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

**This is the untested part of the feature.** The geometry and routing plumbing have unit
tests, but nothing here has been exercised on a real device — no rendering, no touch handling,
no end-to-end routing against real map data. If you have a device or emulator, this pass is
the highest-value thing you can do for this fork.

Install an offline map for the test area first (Menu → Maps & Resources), or routing has
nothing to work with.

1. Enable **ALPR cameras (DeFlock)** in Plugins.
2. Pan to a mapped city; confirm cameras appear at zoom 13 and wedges point per `direction`.
   Cross-check a few against [deflock.org](https://deflock.org).
3. Change **Detection range** and confirm the wedges resize on the map.
4. Plan a route across a camera cluster with avoidance off, then on; confirm the route changes.
5. Move the detour slider from 0 to 30 minutes and confirm the chosen route and the reported
   detour change with it.
6. Turn off networking and confirm a previously visited area still shows cameras.

## Releases

`.github/workflows/release.yml` builds an APK and publishes it as a GitHub release on **any**
tag push:

```bash
git tag -a v0.2.0 -m "What changed"
git push origin v0.2.0
```

The workflow runs `:OsmAnd-java:test` first, so a red test stops the release. It builds
`assembleNightlyFreeOpenglArmonlyDebug` — `arm64-v8a` + `armeabi-v7a` in one file, which
installs on any phone — renames it to `OsmAnd-DeFlock-<tag>-armonly.apk`, writes a `.sha256`
beside it, and attaches both. A tag whose version contains a hyphen (`v0.2.0-beta`) is marked
prerelease, per semver.

The APK is signed with `keystores/debug.keystore`, the debug key committed to this repository.
No secrets are involved, which is why a tag push works with no setup at all. It also means the
build cannot upgrade over an APK signed with a different key. Real release signing would need a
keystore, repository secrets, and a change to the `publishing` signing config in
`OsmAnd/build.gradle`, which still points at a Jenkins path (`/var/lib/jenkins/osmand_key`).

To test a change to the workflow without cutting a tag, run it from the Actions tab
(**Run workflow**). On a manual run it builds exactly the same APK but attaches it to the run as
an artifact instead of publishing a release.

The tag's version reaches the app: the workflow sets `APK_VERSION`, which `OsmAnd/build.gradle`
already reads into `versionName`, so About shows the release version. `versionCode` is
deliberately left alone — deriving it from a run number would let it go backwards if the
workflow were ever recreated, and Android refuses a lower `versionCode` as a downgrade.

### Two things that only break in CI

Both were found by reading, not by a failing build, because neither reproduces on a developer
machine. If you rewrite the workflow, keep them.

**The NDK.** Every variant's `javaCompile` depends on `buildOsmAndCore`, which runs
`OsmAnd/old-ndk-build.sh`. That script exits early — silently, exit 0 — when no NDK is
configured, which is the only path this fork has ever built. GitHub's runner image *does* set
`ANDROID_NDK_ROOT`, and with it set the script tries to build the legacy core from
`../../core-legacy`, which this fork does not vendor. The workflow blanks `ANDROID_NDK`,
`ANDROID_NDK_ROOT` and `ANDROID_NDK_HOME` at job level to restore the working path. Nothing in
the Android DSL uses `externalNativeBuild`, so AGP does not need the NDK; `-x buildOsmAndCore`
is the fallback if blanking ever stops being enough.

**Gradle heap.** `gradle.properties` leaves `org.gradle.jvmargs` commented out, so the daemon
gets Gradle's 512 MB default. This project does not fail on that — it thrashes, for tens of
minutes, looking exactly like a hang. The workflow appends `-Xmx5g` before building, the same
fix as [prerequisite 4](#4-gradle-heap--do-not-skip-this).

The workflow also pins `LANG`/`LC_ALL` to `C.UTF-8` for the same reason a local build needs a
UTF-8 locale: `collectTestResources` hashes `ludwigstraße.obf.gz`.

### Never put `${{ }}` inside a `run:` block

Actions substitutes `${{ ... }}` across the whole `run:` string *before* bash sees any of it, so
a `#` comment is no protection. The first version of this workflow carried a comment mentioning
an empty `${{ }}`, and the file was rejected outright — *"An expression was expected"* — which
means no jobs, no `on:` evaluation, and a tag push that silently produces nothing. A run named
after the file path instead of the workflow's `name:`, finishing in under a second with zero
jobs, is what that looks like in the Actions list.

Every expression now arrives through `env:` instead, which is also what GitHub recommends for
avoiding script injection. If you add one, keep it out of `run:`, and check the file with:

```bash
grep -n '\${{' .github/workflows/release.yml    # every hit should be an env:, with: or if:
```

## Rebasing onto newer OsmAnd

The fork touches few upstream files deliberately (listed in
[architecture.md](architecture.md)). The ones most likely to conflict are `RouteProvider.java`
and `RouteOptionsBottomSheet.java`.

After rebasing, re-check the two assumptions in [routing.md](routing.md) — that native routing
is default, and that HH routing filters points by tags without ids. If either changes upstream,
the avoidance design needs revisiting, and `TransientExcludedRoadsTest` will not catch it
because it exercises the Java path only.
