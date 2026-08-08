# Routing avoidance

How "Avoid ALPR camera view" works, and why it is built the way it is. The constraints below
are not incidental — they eliminated every more elegant design, so they are worth
understanding before changing this code.

## Constraint 1: the Java router is not the real router

OsmAnd's routing is native C++ (`OsmAnd-core`) by default:

```java
// RouteProvider.calculateRoutingEnvironment
NativeOsmandLibrary lib = settings.SAFE_MODE.get() ? null : NativeOsmandLibrary.getLoadedLibrary();
```

`SAFE_MODE` defaults to false, so the Java `BinaryRoutePlanner` does not run in normal use.
Anything hooked into Java-side cost functions — `GeneralRouter.defineRoutingObstacle`,
`defineSpeedPriority`, `calculateTurnTime` — **has no effect for real users**.

Only two avoidance mechanisms cross the JNI boundary:

- **impassable road ids** — `GeneralRouter.getImpassableRoadIds()` has no Java callers at all;
  it exists for the native router to read.
- **direction points** — `RoutingConfiguration.getNativeDirectionPoints()`.

Direction points are point-precise, which is attractive, but they are dropped unless the tag
already exists in that region's route encoding rules, and applying a penalty to them needs a
rule in `routing.xml`, which lives in a different repository. So: **road ids**.

## Constraint 2: hierarchical routing ignores road ids

OsmAnd's "fast" (HH) routing is the default for car and bicycle profiles. Its point filter
rebuilds a synthetic road object from tags only:

```java
// HHRoutePlanner.filterPointsBasedOnConfiguration
RouteDataObject rdo = new RouteDataObject(regR);
...
rdo.types = tint.toArray();
pnt.rtExclude = !currentCtx.rctx.getRouter().acceptLine(rdo);
```

That `rdo` has **no id**, so `acceptLine`'s `impassableRoads.contains(way.id)` can never match.
Road-id exclusion is invisible to HH routing.

Therefore, when avoidance is on, `calculateRoutingEnvironment` forces standard A*:

```java
if (method.isFastRoutingPossible(params.mode) && !forceStandardRouting) {
    router.setDefaultHHRoutingConfig();
} else {
    router.setHHRoutingConfig(null);
}
```

The cost is slower calculation on long routes. The alternative — silently ignoring the
setting — is worse, so this is forced regardless of the user's routing-method preference, and
the UI says so.

## Constraint 3: exclusion is whole-way

`acceptLine` matches on the `RouteDataObject` id, which identifies an entire OSM way. A camera
at one end of a long way blocks the whole way.

This is not new: OsmAnd's existing "Avoid road" feature has exactly the same granularity. The
relaxation ladder below is the mitigation. It is an accepted limitation, documented in the
README and the UI.

## The algorithm

In `RouteProvider.findVectorMapsRouteAvoidingCameras`:

**1. Baseline route.** Calculated first with no exclusions. It serves two purposes: it is the
yardstick the detour is measured against, and its `RoutingContext` already has the map tiles
loaded that the camera-to-road lookup needs.

**2. Collect cameras.** A bounding box around start, intermediates and end is only the first,
cheap pass — it is what the region index can answer. The set is then narrowed by
`AlprRouteCorridor.select` to cameras within `range + margin + 1.5 km` **of the baseline route
polyline**, ordered by distance to it, and only then capped.

> **Why not just the bounding box.** It used to be the bounding box alone, capped at 4000
> cameras — and that shipped a bug worth remembering. A box around a 200 km trip spans 240 km,
> a downloaded region can hold far more than 4000 cameras (a North Carolina-sized box holds
> 9,209), and cameras arrive sorted by OSM id because that is how `AlprRegionFile` writes them.
> So the cap kept the 4000 *oldest* cameras in the region — a geographically arbitrary set that
> usually excluded every camera along the road being planned. Avoidance silently did nothing,
> and it got worse the more data you downloaded. Ordering by distance to the route means that
> if the cap ever bites, it drops the cameras furthest from where you are driving.

**3. Resolve roads.** For each camera, `AlprRoadExclusionResolver` loads a zoom-17 tile of road
data around it (the same `RoutingContext.loadTileData` call `findRouteSegment` uses) and tests
every polyline segment with `CameraCoverage.coversSegment`. Each watched road is recorded with
its `highway` class, which the relaxation ladder needs.

This is one tile load per camera, which is the other reason step 2 matters: a few hundred
cameras near the route instead of thousands scattered across a region.

Roads are tested against the detection cone **plus a keep-away radius**
(`ALPR_AVOIDANCE_MARGIN_M`, 150 m by default). A road is excluded if it enters the cone extended
to `range + margin`, or comes within `margin` of the camera *in any direction*. The circle is
the point: `direction` can be wrong, cameras get re-aimed without anyone editing OSM, and the
road immediately behind a camera is a poor place to rely on a tag. Setting the margin to 0
reproduces the pure detection model exactly.

**4. Avoiding route.** Same calculation with those ids excluded and HH disabled. If
`routingTime - baselineTime <= budget`, done.

**5. Relaxation ladder.** Over budget means giving ground. Exclusions are re-admitted by road
class, fastest first:

| Round | Re-admits |
|---|---|
| 0 | `motorway`, `motorway_link`, `trunk`, `trunk_link` |
| 1 | + `primary`, `primary_link` |
| 2 | + `secondary`, `secondary_link` |

The first route that fits the budget wins. Fast roads are conceded first because they are the
expensive ones to detour around — an unavoidable motorway is usually what blows the budget,
while a residential street almost always has a cheap parallel alternative. Minor roads are
never re-admitted for this reason.

Three rounds bounds the work at five route calculations worst case.

**6. Fall back honestly.** If nothing fits, the user gets the plain fastest route and the UI
reports how many cameras can see it. It never silently returns an unavoided route.

**7. Check the route that came back.** Excluding roads is an instruction to the router, not a
result. `AlprRouteCorridor.countWatching` walks the route actually produced and counts the
cameras that can see it, and *that* is the number the UI reports.

This matters more than it looks. The old code reported "no ALPR cameras in view" whenever it
had excluded something and the router returned a route — it never checked. That is a claim this
tool should never make on trust: the exclusion set can be incomplete, and a detour can be routed
straight past a camera that was never in it. Now the count comes from measuring the road the
user is about to drive.

The outcome — cameras avoided, cameras still in view (verified), detour seconds, and both
routes' time and distance — is stored on the plugin, shown under the route option, and expanded
in the avoidance sheet, where the fastest route is listed beside the one taken. The fastest
route's geometry is kept too, and `AlprCameraLayer` draws it as a dimmed dashed line so it is
visible where the two diverge.

## Passing exclusions to the router

`RoutingConfiguration.Builder` gained a **transient** excluded-road set, separate from the
existing `impassableRoadLocations`:

```java
Set<Long> excludedRoads = new HashSet<>(impassableRoadLocations);
excludedRoads.addAll(transientExcludedRoads);
i.router.setImpassableRoads(excludedRoads);
```

Separate because `Builder` instances are **cached per `ApplicationMode` and shared** — writing
camera exclusions into `impassableRoadLocations` would add them to the user's saved "Avoid
roads" list and leak them into unrelated calculations. `RouteProvider.initOsmAndRoutingConfig`
sets the transient set on every build, including to empty, so nothing carries over.

`TransientExcludedRoadsTest` pins this down: ids reach `getImpassableRoadIds()`, they combine
with but never mutate the user's list, they clear on the next build, and the caller's set is
copied rather than aliased.

## Testing

`AlprRoadExclusionResolverTest` covers the relaxation ladder: each round re-admits the right
classes, relaxation is monotonic, minor roads are never re-admitted, the result is not mutated.

`CameraCoverageTest` covers the geometry: cone boundaries, wraparound through north,
out-of-range rejection, omnidirectional fallback, the nearest-point-outside-the-cone case, and
`direction` parsing.

`resolve()` itself is not unit tested — it needs a `RoutingContext` with real map data. The
geometry it delegates to is tested; the loop around it is not.

## If you change this

- **Do not add cost-based penalties in `GeneralRouter`** and expect them to work. See
  constraint 1.
- **Do not remove the forced standard routing** without solving constraint 2 first.
- **Keep the baseline route first.** Both the detour measurement and the tile loading depend on
  it.
- **Watch the cost.** Each relaxation round is a full route calculation. Adding rounds makes
  the worst case slower.
