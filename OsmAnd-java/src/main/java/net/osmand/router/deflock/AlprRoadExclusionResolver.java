package net.osmand.router.deflock;

import net.osmand.binary.RouteDataObject;
import net.osmand.router.RoutingContext;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which roads fall inside the view of a set of ALPR cameras, so the router can be told
 * to avoid them.
 *
 * <p>Exclusion is by road id, which is the one avoidance mechanism that reaches the real router:
 * ids are honoured by {@code GeneralRouter.acceptLine} on the Java A* path and are marshalled to
 * the native C++ router through {@code GeneralRouter.getImpassableRoadIds()}. The trade-off is
 * that ids identify a whole OSM way, exactly as OsmAnd's existing "Avoid road" feature does, so a
 * camera can block more road than it actually watches. {@link #relaxByRoadClass} exists to undo
 * that where it costs too much time.
 */
public class AlprRoadExclusionResolver {

	/**
	 * Zoom of the tile loaded around each camera. Zoom 17 tiles are a few hundred metres across,
	 * comfortably larger than any realistic camera range. This is the same call
	 * {@code RoutePlannerFrontEnd.findRouteSegment} uses to find roads near a point.
	 */
	private static final int TILE_ZOOM_AROUND_CAMERA = 17;

	/**
	 * Road classes ordered from the most expensive to detour around to the least. The relaxation
	 * ladder re-admits them in this order when full avoidance busts the user's detour budget.
	 */
	private static final List<List<String>> RELAXATION_ORDER = Arrays.asList(
			Arrays.asList("motorway", "motorway_link", "trunk", "trunk_link"),
			Arrays.asList("primary", "primary_link"),
			Arrays.asList("secondary", "secondary_link"));

	public static int getRelaxationRounds() {
		return RELAXATION_ORDER.size();
	}

	/**
	 * The roads a set of cameras can see, along with enough context to relax the set later.
	 */
	public static class Result {
		private final Set<Long> excludedRoadIds = new HashSet<>();
		private final Map<Long, String> roadClasses = new HashMap<>();
		private final Set<Long> watchedCameras = new HashSet<>();

		public Set<Long> getExcludedRoadIds() {
			return excludedRoadIds;
		}

		public boolean isEmpty() {
			return excludedRoadIds.isEmpty();
		}

		public int getCameraCount() {
			return watchedCameras.size();
		}

		public String getRoadClass(long roadId) {
			return roadClasses.get(roadId);
		}

		void addWatchedRoad(long roadId, String highway, long cameraId) {
			excludedRoadIds.add(roadId);
			if (highway != null) {
				roadClasses.put(roadId, highway);
			}
			watchedCameras.add(cameraId);
		}
	}

	private AlprRoadExclusionResolver() {
	}

	/**
	 * Finds every road inside the view of any of the given cameras.
	 *
	 * @param ctx     a routing context whose map files cover the area; tiles are loaded through it
	 * @param cameras cameras to consider, already narrowed to the routing corridor
	 */
	public static Result resolve(RoutingContext ctx, List<AlprCameraPoint> cameras,
	                             double rangeM, double coneDeg) throws IOException {
		return resolve(ctx, cameras, rangeM, coneDeg, 0);
	}

	/**
	 * As {@link #resolve(RoutingContext, List, double, double)}, with a keep-away radius applied
	 * around every camera - see
	 * {@link CameraCoverage#covers(AlprCameraPoint, double, double, double, double, double)}.
	 *
	 * @param marginM how close a road may come to a camera in any direction
	 */
	public static Result resolve(RoutingContext ctx, List<AlprCameraPoint> cameras,
	                             double rangeM, double coneDeg, double marginM) throws IOException {
		Result result = new Result();
		if (ctx == null || cameras == null || cameras.isEmpty()) {
			return result;
		}
		for (AlprCameraPoint camera : cameras) {
			int x31 = MapUtils.get31TileNumberX(camera.getLongitude());
			int y31 = MapUtils.get31TileNumberY(camera.getLatitude());
			List<RouteDataObject> roads = new ArrayList<>();
			ctx.loadTileData(x31, y31, TILE_ZOOM_AROUND_CAMERA, roads);
			for (RouteDataObject road : roads) {
				if (road == null || road.getPointsLength() < 2) {
					continue;
				}
				if (result.excludedRoadIds.contains(road.getId())) {
					result.watchedCameras.add(camera.getOsmId());
					continue;
				}
				if (isWatched(camera, road, rangeM, coneDeg, marginM)) {
					result.addWatchedRoad(road.getId(), road.getHighway(), camera.getOsmId());
				}
			}
		}
		return result;
	}

	private static boolean isWatched(AlprCameraPoint camera, RouteDataObject road,
	                                 double rangeM, double coneDeg, double marginM) {
		for (int i = 0; i < road.getPointsLength() - 1; i++) {
			double lat1 = MapUtils.get31LatitudeY(road.getPoint31YTile(i));
			double lon1 = MapUtils.get31LongitudeX(road.getPoint31XTile(i));
			double lat2 = MapUtils.get31LatitudeY(road.getPoint31YTile(i + 1));
			double lon2 = MapUtils.get31LongitudeX(road.getPoint31XTile(i + 1));
			if (CameraCoverage.coversSegment(camera, lat1, lon1, lat2, lon2, rangeM, coneDeg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Drops the exclusions that are most expensive to route around, so a detour can fit inside the
	 * user's time budget. Round 0 re-admits motorways and trunk roads, round 1 adds primary roads,
	 * round 2 adds secondary roads.
	 *
	 * @param round zero-based relaxation round
	 * @return the reduced set of road ids to exclude
	 */
	public static Set<Long> relaxByRoadClass(Result result, int round) {
		Set<Long> relaxed = new HashSet<>(result.getExcludedRoadIds());
		if (result.isEmpty()) {
			return relaxed;
		}
		Set<String> readmitted = new HashSet<>();
		for (int i = 0; i <= round && i < RELAXATION_ORDER.size(); i++) {
			readmitted.addAll(RELAXATION_ORDER.get(i));
		}
		relaxed.removeIf(roadId -> {
			String highway = result.getRoadClass(roadId);
			return highway != null && readmitted.contains(highway);
		});
		return relaxed;
	}
}
