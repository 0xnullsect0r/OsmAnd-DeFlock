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

**2. Collect cameras.** `AlprAvoidanceHelper.getCorridorCameras` takes the bounding box of
start, intermediates and end, expanded by `max(2 km, 10% of the straight-line distance)` —
wide enough to contain a detour, not just the direct line. Capped at 4000 cameras. Missing
tiles are downloaded synchronously, because routing off a half-loaded cache would tell the
user their route avoids cameras when it does not.

**3. Resolve roads.** For each camera, `AlprRoadExclusionResolver` loads a zoom-17 tile of road
data around it (the same `RoutingContext.loadTileData` call `findRouteSegment` uses) and tests
every polyline segment with `CameraCoverage.coversSegment`. Each watched road is recorded with
its `highway` class, which the relaxation ladder needs.

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

The outcome — cameras avoided, cameras still in view, actual detour seconds — is stored on the
plugin and shown under the route option.

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
