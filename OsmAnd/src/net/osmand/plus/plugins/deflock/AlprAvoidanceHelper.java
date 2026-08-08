package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.routing.RouteCalculationParams;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.router.deflock.AlprCameraPoint;
import net.osmand.router.deflock.AlprCoverageIndex;
import net.osmand.router.deflock.AlprRouteCorridor;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Supporting logic for routing around ALPR cameras: works out which cameras could matter for a
 * route, makes sure they are downloaded, and records what the router ended up doing so the UI can
 * report it.
 */
public class AlprAvoidanceHelper {

	private static final Log log = PlatformUtil.getLog(AlprAvoidanceHelper.class);

	/** Smallest margin added around the route's bounding box when collecting cameras. */
	private static final double MIN_CORRIDOR_MARGIN_M = 2000;

	/** Margin as a share of the straight-line trip length, for longer journeys. */
	private static final double CORRIDOR_MARGIN_RATIO = 0.1;

	/**
	 * Never consider more cameras than this in one calculation.
	 *
	 * <p>A backstop, not a filter. Cameras are narrowed to the route corridor first and ordered by
	 * distance to the road, so this should not bind - and when it does it now drops the furthest
	 * away rather than, as it once did, whichever happened to sort last by OSM id.
	 */
	private static final int MAX_CAMERAS = 4000;

	/**
	 * What camera avoidance did to the route the user is looking at.
	 */
	public static class Outcome {
		private final int camerasAvoided;
		private final int camerasStillInView;
		private final int detourSeconds;
		private final boolean budgetExceeded;
		private final AlprCoverageIndex.Coverage coverage;
		private final int camerasConsidered;

		// The fastest route, kept so the cost of avoidance can be shown rather than asserted.
		private int fastestSeconds;
		private int fastestMetres;
		private int fastestCameras;
		private int chosenSeconds;
		private int chosenMetres;
		private List<LatLon> fastestPath;

		public Outcome(int camerasAvoided, int camerasStillInView, int detourSeconds,
		               boolean budgetExceeded, @NonNull AlprCoverageIndex.Coverage coverage,
		               int camerasConsidered) {
			this.camerasAvoided = camerasAvoided;
			this.camerasStillInView = camerasStillInView;
			this.detourSeconds = detourSeconds;
			this.budgetExceeded = budgetExceeded;
			this.coverage = coverage;
			this.camerasConsidered = camerasConsidered;
		}

		/**
		 * Records both routes so the UI can put them side by side.
		 *
		 * @param fastestPath the fastest route's geometry, drawn as a ghost line; null when
		 *                    avoidance changed nothing and there is no second route to show
		 */
		public void setComparison(int fastestSeconds, int fastestMetres, int fastestCameras,
		                          int chosenSeconds, int chosenMetres,
		                          @Nullable List<LatLon> fastestPath) {
			this.fastestSeconds = fastestSeconds;
			this.fastestMetres = fastestMetres;
			this.fastestCameras = fastestCameras;
			this.chosenSeconds = chosenSeconds;
			this.chosenMetres = chosenMetres;
			this.fastestPath = fastestPath;
		}

		/** True when the route taken differs from the fastest one, so a comparison means something. */
		public boolean hasComparison() {
			return fastestPath != null && !fastestPath.isEmpty();
		}

		public int getFastestSeconds() {
			return fastestSeconds;
		}

		public int getFastestMetres() {
			return fastestMetres;
		}

		public int getFastestCameras() {
			return fastestCameras;
		}

		public int getChosenSeconds() {
			return chosenSeconds;
		}

		public int getChosenMetres() {
			return chosenMetres;
		}

		/** The fastest route's geometry, for drawing alongside the one actually chosen. */
		@Nullable
		public List<LatLon> getFastestPath() {
			return fastestPath;
		}

		/**
		 * How much of the route had deliberately downloaded region data behind it.
		 */
		@NonNull
		public AlprCoverageIndex.Coverage getCoverage() {
			return coverage;
		}

		/**
		 * How many cameras were actually available to route against, from any source.
		 *
		 * <p>Coverage alone is not enough to describe what happened. Someone with no downloaded
		 * region but a populated browsing cache has {@code NONE} coverage and yet real cameras
		 * were considered - reporting "no camera data" there would be plainly false.
		 */
		public int getCamerasConsidered() {
			return camerasConsidered;
		}

		/**
		 * True when the route was planned against nothing at all, so "no cameras in view" would
		 * mean "nothing was known" rather than "the way is clear".
		 */
		public boolean isUninformed() {
			return camerasConsidered == 0 && coverage != AlprCoverageIndex.Coverage.FULL;
		}

		/**
		 * True when something was known but the ground was not fully covered.
		 */
		public boolean isPartiallyInformed() {
			return camerasConsidered > 0 && coverage != AlprCoverageIndex.Coverage.FULL;
		}

		public int getCamerasAvoided() {
			return camerasAvoided;
		}

		public int getCamerasStillInView() {
			return camerasStillInView;
		}

		public int getDetourSeconds() {
			return detourSeconds;
		}

		/** True when the full-avoidance route cost more than the user's budget and had to be relaxed. */
		public boolean isBudgetExceeded() {
			return budgetExceeded;
		}
	}

	private AlprAvoidanceHelper() {
	}

	@Nullable
	public static DeFlockPlugin getPlugin() {
		return PluginsHelper.getActivePlugin(DeFlockPlugin.class);
	}

	/**
	 * @return true when the route for these parameters should avoid camera view
	 */
	public static boolean shouldAvoidCameras(@NonNull RouteCalculationParams params) {
		DeFlockPlugin plugin = getPlugin();
		return plugin != null && plugin.isAvoidanceEnabled(params.mode);
	}

	/**
	 * A calculated route as a plain polyline, for geometry that has no business knowing about
	 * routing types.
	 */
	@NonNull
	public static List<LatLon> pathOf(@Nullable RouteCalculationResult route) {
		List<LatLon> path = new ArrayList<>();
		if (route == null) {
			return path;
		}
		for (Location location : route.getImmutableAllLocations()) {
			if (location != null) {
				path.add(new LatLon(location.getLatitude(), location.getLongitude()));
			}
		}
		return path;
	}

	/**
	 * The area cameras are collected from: everything the route could plausibly pass through,
	 * including a detour around a cluster.
	 */
	@NonNull
	public static QuadRect getCorridorBounds(@NonNull RouteCalculationParams params) {
		List<LatLon> points = new ArrayList<>();
		points.add(new LatLon(params.start.getLatitude(), params.start.getLongitude()));
		if (params.intermediates != null) {
			points.addAll(params.intermediates);
		}
		points.add(params.end);

		double minLat = Double.MAX_VALUE;
		double maxLat = -Double.MAX_VALUE;
		double minLon = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE;
		for (LatLon point : points) {
			minLat = Math.min(minLat, point.getLatitude());
			maxLat = Math.max(maxLat, point.getLatitude());
			minLon = Math.min(minLon, point.getLongitude());
			maxLon = Math.max(maxLon, point.getLongitude());
		}
		double spanM = MapUtils.getDistance(minLat, minLon, maxLat, maxLon);
		double marginM = Math.max(MIN_CORRIDOR_MARGIN_M, spanM * CORRIDOR_MARGIN_RATIO);
		double marginLat = marginM / 111320d;
		double centreLat = (minLat + maxLat) / 2;
		double marginLon = marginM / (111320d * Math.max(0.05, Math.cos(Math.toRadians(centreLat))));

		return new QuadRect(minLon - marginLon, maxLat + marginLat,
				maxLon + marginLon, minLat - marginLat);
	}

	/**
	 * Collects the cameras in the corridor from data already on the device.
	 *
	 * <p>This never touches the network. Route calculation is not a good place to discover that
	 * the phone is offline, and blocking on Overpass mid-calculation made every route depend on a
	 * third-party server being reachable. What the device does not have, it does not avoid - and
	 * {@link #getCorridorCoverage} is how the UI gets to say so.
	 */
	@NonNull
	public static List<AlprCameraPoint> getCorridorCameras(@NonNull RouteCalculationParams params,
	                                                       @Nullable List<LatLon> route) {
		DeFlockPlugin plugin = getPlugin();
		if (plugin == null) {
			return new ArrayList<>();
		}
		QuadRect bounds = getCorridorBounds(params);
		List<AlprCameraPoint> cameras = plugin.getCameraRepository().getCameras(bounds, false);

		// The bounding box is only what the region index can answer cheaply; for a long trip it is
		// enormous and mostly irrelevant. Narrowing to the road itself is what makes the cap - and
		// the per-camera tile loads in the resolver - reasonable.
		double radius = plugin.ALPR_VIEW_RANGE_M.getModeValue(params.mode)
				+ plugin.ALPR_AVOIDANCE_MARGIN_M.getModeValue(params.mode)
				+ AlprRouteCorridor.DETOUR_CORRIDOR_M;
		List<AlprCameraPoint> selected = AlprRouteCorridor.select(route, cameras, radius, MAX_CAMERAS);
		if (selected.size() < cameras.size()) {
			log.info("ALPR avoidance considering " + selected.size() + " of " + cameras.size()
					+ " cameras within " + Math.round(radius) + "m of the route");
		}
		return selected;
	}

	/**
	 * Counts the cameras that can see a route that has already been calculated.
	 *
	 * <p>Excluding roads is an instruction to the router, not a result. This measures what came
	 * back, so "no cameras in view" is something the app has checked rather than something it
	 * assumed - and so a detour that happens to pass a camera nobody considered is still counted.
	 */
	public static int countWatching(@NonNull RouteCalculationParams params,
	                                @Nullable List<LatLon> route,
	                                @NonNull List<AlprCameraPoint> cameras) {
		DeFlockPlugin plugin = getPlugin();
		if (plugin == null || route == null) {
			return 0;
		}
		return AlprRouteCorridor.countWatching(route, cameras,
				plugin.ALPR_VIEW_RANGE_M.getModeValue(params.mode),
				plugin.ALPR_VIEW_CONE_DEG.getModeValue(params.mode),
				plugin.ALPR_AVOIDANCE_MARGIN_M.getModeValue(params.mode));
	}

	/**
	 * How much of the route corridor has deliberately downloaded camera data.
	 *
	 * <p>Without this the most dangerous outcome is the quiet one: a route over ground with no
	 * camera data looks exactly like a route that genuinely avoids every camera.
	 */
	@NonNull
	public static AlprCoverageIndex.Coverage getCorridorCoverage(@NonNull RouteCalculationParams params) {
		DeFlockPlugin plugin = getPlugin();
		if (plugin == null) {
			return AlprCoverageIndex.Coverage.NONE;
		}
		return plugin.getCameraRepository().getOfflineCoverage(getCorridorBounds(params));
	}
}
