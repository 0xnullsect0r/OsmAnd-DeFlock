# Android Auto and the instrument cluster

How to get this build running on a head unit, what feeds the vehicle's instrument-cluster
navigation page, and what was broken before.

---

## Why OsmAnd never appeared in the car

Nothing was wrong with the manifest. **Android Auto hides sideloaded apps by default** — it only
lists apps installed from the Play Store unless you explicitly allow unknown sources. On top of
that, two things in the app itself would have blocked it anyway:

- Android Auto was gated behind an OsmAnd Pro subscription (a 10-day trial from install, then a
  purchase screen on the car display). That gate is gone — this fork has no purchasing.
- The manifest declared `androidx.car.app.minCarApiLevel` = **1** while the code unconditionally
  builds Car App API **level 7** templates (`MapWithContentTemplate`, verified against
  androidx.car.app 1.7.0). A host below level 7 would connect and then fail at template-build time.
  The manifest now declares 7.

## Enabling it on the phone

Do this first — no code change substitutes for it.

1. Install the APK (see [Which build](#which-build) below).
2. Open Android Auto settings: **Settings → Connected devices → Android Auto**. On older phones it
   is a standalone "Android Auto" app.
3. Scroll to **Version**, tap it about ten times, accept *"Allow development settings?"*.
4. Overflow menu (⋮) → **Developer settings**.
5. Enable **Unknown sources**. Enable **Add new apps to launcher automatically** if present.
6. Force-stop Android Auto, then reconnect to the vehicle.

OsmAnd should now appear in the Android Auto launcher.

## Which build

Use the `androidFull` flavor:

```bash
./gradlew :OsmAnd:assembleAndroidFullOpenglArm64Debug
```

Two reasons. The debug build type makes `NavigationCarAppService.createHostValidator()` return
`ALLOW_ALL_HOSTS_VALIDATOR`, which is what lets the Desktop Head Unit and a sideloaded install
connect at all. And the debug keystore is committed to the repo, so every rebuild is signed
identically and reinstalls over the previous one without uninstalling.

`applicationId` is `net.osmand.plus`, which collides with Play-store OsmAnd+ — uninstall that first
or the install fails on a signature mismatch.

## Testing without the vehicle

Use Google's Desktop Head Unit:

1. Android Studio → SDK Manager → SDK Tools → **Android Auto Desktop Head Unit Emulator**.
2. Phone → Android Auto developer settings → **Start head unit server**.
3. `adb forward tcp:5277 tcp:5277`
4. `$ANDROID_HOME/extras/google/auto/desktop-head-unit`

The DHU exposes an *auto-drive* command. It triggers `NavigationSession.onAutoDriveEnabled()`, which
starts OsmAnd's own route simulation — that exercises the whole 1 Hz trip loop at a desk.

---

## What actually drives the cluster

A vehicle's reconfigurable instrument cluster has a navigation page that renders **turn-by-turn
metadata**, not the app's pixels. Android Auto delivers that metadata through
`NavigationManager.updateTrip(Trip)`. That is how Google Maps and onX Offroad populate the page, and
it is the channel this build feeds.

Full *map* rendering to a cluster is a separate feature (`FEATURE_CLUSTER` +
`SessionInfo.DISPLAY_TYPE_CLUSTER`). It is implemented here — see below — but Android Auto
projection generally does not expose a cluster display to third-party apps; it is primarily an
Android Automotive OS capability. Expect the metadata path to be what lights up your dash.

### Bugs fixed in the trip path

| Problem | Effect in the car |
|---|---|
| A single `IllegalStateException` from `updateTrip` set `carNavigationShouldBeActive = false` permanently | The cluster froze mid-drive and never recovered — typically after the first reroute |
| Trip updates were nested inside `if (navigationScreen != null)` | No car screen meant no trip data at all |
| Trip construction required a map surface for its density | Trip metadata could not be produced without a rendering surface |
| `setRoundaboutExitNumber(turnType.getExitOut())` with an exit of 0 | `Maneuver.Builder.build()` requires ≥ 1 and throws, taking the **whole Trip** down mid-roundabout |
| Distance always used the `_P1` units (one decimal) | Long legs rendered as "230.0 mi" — OsmAndFormatter drops the decimal at ≥ 100 units |
| `false/*routingHelper.isRouteWasFinished()*/` hardcoded | The arrival state was unreachable |
| `updateCarNavigation(...)` commented out in `onResume` | Returning to the car screen did not refresh the trip |

A transient rejection now re-arms via `navigationStarted()` and retries, and only gives up after
five consecutive failures.

### Cluster session

`NavigationCarAppService.onCreateSession(SessionInfo)` branches on the display type and returns a
`ClusterSession` for `DISPLAY_TYPE_CLUSTER`. That session is deliberately minimal, and three rules
keep it from breaking the main display:

- **It never registers as `OsmandApplication.carNavigationSession`.** That setter calls
  `RoutingHelper.onCarNavigationSessionChanged()`, which *stops or pauses navigation* when the value
  goes null — a cluster display disconnecting would otherwise end your drive.
- **It never builds a `SurfaceRenderer`.** `setupOffscreenRenderer()` detaches the map renderer from
  its current owner, which would rip the map off the centre screen.
- **It never takes a `NavigationManager`.** Trip ownership stays with the main session.

The two sessions are coupled by exactly one thing: a `TripSnapshot` the main session publishes on
every update, which the cluster screen renders through a `NavigationTemplate`.

### Why there is no second map surface

A live map on the cluster is not worth it, and would break the centre screen:

- `NativeCoreContext` holds one **static** `MapRendererContext`, replaced wholesale on every call.
- `OsmandMap` holds one `private final OsmandMapTileView` for the entire app — one viewport, one
  zoom, one rotation.
- `SurfaceRenderer.setupOffscreenRenderer()` explicitly detaches the incumbent renderer.
- `MapDisplayPositionManager` is global; two displays would fight over one visible-area ratio.

Making that safe means an N-display refactor through the core rendering path. The cluster page wants
metadata anyway.

---

## Verifying

Logcat while connected:

```bash
adb logcat -s NavigationSession:* SurfaceRenderer:* CarApp:* CarAppService:*
adb logcat | grep -iE "updateTrip|navigationStarted|navigationEnded|displayType|IllegalState"
```

`NavigationCarAppService` logs `Creating car session for displayType=...` on every session. That line
is the definitive answer to whether your vehicle offers a cluster display to a projected app.

In the vehicle, in order:

1. OsmAnd appears in the Android Auto launcher.
2. Start a route — the centre screen shows the map and turn card.
3. Switch the cluster to its navigation page — maneuver arrow, distance and ETA appear. **This is
   the goal.**
4. Background OsmAnd on the head unit — the cluster keeps counting down.
5. Miss a turn to force a reroute — the cluster recovers instead of freezing. This is the specific
   regression the permanent-disable bug caused.
6. Drive a roundabout where the exit number is not mapped — no freeze.
7. A leg over 100 mi shows "230 mi", not "230.0 mi".
8. Reaching the destination shows an arrival state.

## If it still does not appear

Work down this list:

1. Confirm **Unknown sources** is actually on, and that you force-stopped Android Auto afterwards.
2. Remove `androidx.car.app.category.FEATURE_CLUSTER` from the `CarAppService` intent-filter in
   `OsmAnd/AndroidManifest.xml`. It is the newest thing the host has to negotiate, and the first
   thing to eliminate.
3. Lower `androidx.car.app.minCarApiLevel` from 7 to 6, and add `getCarAppApiLevel()` fallbacks for
   the five `MapWithContentTemplate` screens (RoutePreview, MapMagnifier, DestinationReached,
   Confirm, PrivateAccess).
4. Verify it works in the DHU. If it appears there but not in the vehicle, that points at head-unit
   policy rather than the build.
