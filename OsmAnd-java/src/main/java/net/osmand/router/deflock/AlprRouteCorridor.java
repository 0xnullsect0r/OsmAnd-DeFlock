package net.osmand.router.deflock;

import net.osmand.data.LatLon;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Narrows a set of cameras to the ones that could matter for a particular route, and checks a
 * finished route against them.
 *
 * <p>Selecting by distance to the route rather than by a bounding box is not an optimisation, it
 * is a correctness fix. A bounding box around a long trip is enormous - a 200 km journey spans a
 * 240 km-wide rectangle - and a downloaded region can hold far more cameras than a single
 * calculation can carry (a North Carolina-sized box holds over 9,000). Something has to give, and
 * the previous code gave up whichever cameras came last in OSM id order, which is to say a
 * geographically arbitrary set. On a long route inside a fully downloaded region that discarded
 * precisely the cameras along the road being planned, and avoidance quietly stopped happening -
 * the feature got worse the more data it was given.
 *
 * <p>Distance to the route is the honest ordering: if a cap has to bite, the cameras nearest the
 * road you are about to drive are the ones worth keeping.
 */
public class AlprRouteCorridor {

	/**
	 * How far off the route a camera can be and still matter, beyond its own reach.
	 *
	 * <p>Avoidance moves the route, so the cameras that count are not only the ones on the
	 * baseline: a camera a few streets away may sit on the road the detour would use. This is the
	 * width of that "somewhere a detour might plausibly go" band.
	 */
	public static final double DETOUR_CORRIDOR_M = 1500;

	private AlprRouteCorridor() {
	}

	/**
	 * @param route    the route to measure against, as an ordered polyline
	 * @param cameras  candidates, typically everything the region index returned for a bounding box
	 * @param radiusM  how far from the route a camera may be
	 * @param limit    most cameras to return; the nearest are kept
	 * @return cameras within the radius, nearest to the route first
	 */
	public static List<AlprCameraPoint> select(List<LatLon> route, List<AlprCameraPoint> cameras,
	                                           double radiusM, int limit) {
		List<AlprCameraPoint> selected = new ArrayList<>();
		if (cameras == null || cameras.isEmpty() || limit <= 0) {
			return selected;
		}
		if (route == null || route.size() < 2) {
			// No route to measure against: keep the input order rather than inventing one, and let
			// the caller's cap apply. This is the degenerate case, not the normal path.
			for (int i = 0; i < Math.min(limit, cameras.size()); i++) {
				selected.add(cameras.get(i));
			}
			return selected;
		}

		List<Scored> scored = new ArrayList<>();
		for (AlprCameraPoint camera : cameras) {
			double distance = distanceToRoute(route, camera.getLatitude(), camera.getLongitude(), radiusM);
			if (distance <= radiusM) {
				scored.add(new Scored(camera, distance));
			}
		}
		Collections.sort(scored, Comparator.comparingDouble(s -> s.distanceM));
		for (int i = 0; i < Math.min(limit, scored.size()); i++) {
			selected.add(scored.get(i).camera);
		}
		return selected;
	}

	/**
	 * Counts the cameras that can see the given route.
	 *
	 * <p>This is how the app checks its own work. Excluding roads from the router is an
	 * instruction, not a result: the exclusion set can be incomplete, and a detour can pass a
	 * camera nobody considered. Reporting "no cameras in view" on the strength of having excluded
	 * something is the kind of claim this tool should never make, so the number the UI shows comes
	 * from measuring the route that was actually produced.
	 *
	 * @return how many of the given cameras watch some part of the route
	 */
	public static int countWatching(List<LatLon> route, List<AlprCameraPoint> cameras,
	                                double rangeM, double coneDeg, double marginM) {
		if (route == null || route.size() < 2 || cameras == null) {
			return 0;
		}
		int watching = 0;
		for (AlprCameraPoint camera : cameras) {
			if (watchesRoute(route, camera, rangeM, coneDeg, marginM)) {
				watching++;
			}
		}
		return watching;
	}

	/**
	 * @return true when the camera can see any part of the route
	 */
	public static boolean watchesRoute(List<LatLon> route, AlprCameraPoint camera,
	                                   double rangeM, double coneDeg, double marginM) {
		if (route == null || route.size() < 2 || camera == null) {
			return false;
		}
		double reachM = rangeM + Math.max(0, marginM);
		for (int i = 0; i < route.size() - 1; i++) {
			LatLon a = route.get(i);
			LatLon b = route.get(i + 1);
			// Skip segments that cannot possibly reach, without the trigonometry.
			if (!withinReach(a, b, camera, reachM)) {
				continue;
			}
			if (CameraCoverage.coversSegment(camera, a.getLatitude(), a.getLongitude(),
					b.getLatitude(), b.getLongitude(), rangeM, coneDeg, marginM)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Distance from a point to the nearest part of the route.
	 *
	 * @param cutoffM stop as soon as something this close is found; the exact distance only
	 *                matters for ordering, and most candidates are nowhere near
	 */
	public static double distanceToRoute(List<LatLon> route, double lat, double lon, double cutoffM) {
		double best = Double.MAX_VALUE;
		if (route == null || route.isEmpty()) {
			return best;
		}
		if (route.size() == 1) {
			return MapUtils.getDistance(route.get(0), lat, lon);
		}
		for (int i = 0; i < route.size() - 1; i++) {
			LatLon a = route.get(i);
			LatLon b = route.get(i + 1);
			double d = MapUtils.getOrthogonalDistance(lat, lon,
					a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
			if (d < best) {
				best = d;
				if (best <= 0) {
					return 0;
				}
			}
		}
		return best;
	}

	/** Cheap bounding check on one segment, used to skip the expensive test. */
	private static boolean withinReach(LatLon a, LatLon b, AlprCameraPoint camera, double reachM) {
		double camLat = camera.getLatitude();
		double camLon = camera.getLongitude();
		double south = Math.min(a.getLatitude(), b.getLatitude());
		double north = Math.max(a.getLatitude(), b.getLatitude());
		double west = Math.min(a.getLongitude(), b.getLongitude());
		double east = Math.max(a.getLongitude(), b.getLongitude());

		double padLat = reachM / 111320d;
		double padLon = reachM / (111320d * Math.max(0.05, Math.cos(Math.toRadians(camLat))));
		return camLat >= south - padLat && camLat <= north + padLat
				&& camLon >= west - padLon && camLon <= east + padLon;
	}

	private static class Scored {
		private final AlprCameraPoint camera;
		private final double distanceM;

		Scored(AlprCameraPoint camera, double distanceM) {
			this.camera = camera;
			this.distanceM = distanceM;
		}
	}
}
