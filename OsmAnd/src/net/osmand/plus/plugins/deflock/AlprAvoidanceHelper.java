package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.routing.RouteCalculationParams;
import net.osmand.router.deflock.AlprCameraPoint;
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

	/** Never consider more cameras than this in one calculation. */
	private static final int MAX_CAMERAS = 4000;

	/**
	 * What camera avoidance did to the route the user is looking at.
	 */
	public static class Outcome {
		private final int camerasAvoided;
		private final int camerasStillInView;
		private final int detourSeconds;
		private final boolean budgetExceeded;

		public Outcome(int camerasAvoided, int camerasStillInView, int detourSeconds, boolean budgetExceeded) {
			this.camerasAvoided = camerasAvoided;
			this.camerasStillInView = camerasStillInView;
			this.detourSeconds = detourSeconds;
			this.budgetExceeded = budgetExceeded;
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
	 * Collects the cameras in the corridor, downloading any that are missing first. Downloading is
	 * synchronous on purpose: silently routing off a half-loaded cache would tell the user their
	 * route avoids cameras when it does not.
	 */
	@NonNull
	public static List<AlprCameraPoint> getCorridorCameras(@NonNull OsmandApplication app,
	                                                       @NonNull RouteCalculationParams params) {
		DeFlockPlugin plugin = getPlugin();
		if (plugin == null) {
			return new ArrayList<>();
		}
		QuadRect bounds = getCorridorBounds(params);
		AlprCameraRepository repository = plugin.getCameraRepository();
		if (app.getSettings().isInternetConnectionAvailable()) {
			boolean complete = repository.loadCamerasBlocking(bounds);
			if (!complete) {
				log.info("ALPR camera data for this route is incomplete, avoiding what is cached");
			}
		}
		List<AlprCameraPoint> cameras = repository.getCameras(bounds, false);
		if (cameras.size() > MAX_CAMERAS) {
			log.info("Limiting ALPR avoidance to " + MAX_CAMERAS + " of " + cameras.size() + " cameras");
			return cameras.subList(0, MAX_CAMERAS);
		}
		return cameras;
	}
}
