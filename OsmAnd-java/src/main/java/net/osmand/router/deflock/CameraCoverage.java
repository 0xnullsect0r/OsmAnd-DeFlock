package net.osmand.router.deflock;

import net.osmand.data.LatLon;
import net.osmand.util.MapUtils;

/**
 * Geometry for deciding whether a location, or a stretch of road, falls inside an ALPR camera's
 * field of view.
 *
 * <p>A camera's view is modelled as a circular sector: everything within {@code rangeM} metres of
 * the camera whose bearing from the camera is within {@code coneDeg / 2} of the camera's facing.
 * OSM does not tag a field of view for these cameras, so both the range and the cone angle are
 * user-adjustable settings with conservative defaults.
 *
 * <p>Shared by the map layer (drawing the cone) and the router (deciding which roads to avoid).
 */
public class CameraCoverage {

	/** Default detection range in metres. */
	public static final double DEFAULT_RANGE_M = 60;

	/** Default full cone angle in degrees (so +/- 30 degrees around the facing). */
	public static final double DEFAULT_CONE_DEG = 60;

	/** Sampling resolution when walking along a road segment, in metres. */
	private static final double SAMPLE_STEP_M = 4;

	/** Upper bound on samples per segment, so pathological geometry cannot stall routing. */
	private static final int MAX_SAMPLES = 64;

	private CameraCoverage() {
	}

	/**
	 * @return true when the given location is inside the camera's view sector
	 */
	public static boolean covers(AlprCameraPoint camera, double lat, double lon,
	                             double rangeM, double coneDeg) {
		if (camera == null) {
			return false;
		}
		double dist = MapUtils.getDistance(camera.getLatitude(), camera.getLongitude(), lat, lon);
		if (dist > rangeM) {
			return false;
		}
		// A camera with no mapped facing could be pointing anywhere, so treat it as omnidirectional
		// rather than silently ignoring it.
		if (!camera.hasDirection() || coneDeg >= 360) {
			return true;
		}
		// Standing at the pole itself: bearing is undefined, and you are certainly in view.
		if (dist < 0.5) {
			return true;
		}
		double bearing = bearingTo(camera.getLatitude(), camera.getLongitude(), lat, lon);
		return Math.abs(MapUtils.degreesDiff(bearing, camera.getDirection())) <= coneDeg / 2;
	}

	/**
	 * @return true when any point of the segment from (lat1, lon1) to (lat2, lon2) is inside the
	 * camera's view sector
	 */
	public static boolean coversSegment(AlprCameraPoint camera,
	                                    double lat1, double lon1, double lat2, double lon2,
	                                    double rangeM, double coneDeg) {
		if (camera == null) {
			return false;
		}
		double camLat = camera.getLatitude();
		double camLon = camera.getLongitude();

		// Cheap reject: if even the closest point of the segment is out of range, so is all of it.
		LatLon projection = MapUtils.getProjection(camLat, camLon, lat1, lon1, lat2, lon2);
		double orthogonal = MapUtils.getDistance(projection, camLat, camLon);
		if (orthogonal > rangeM) {
			return false;
		}
		if (covers(camera, projection.getLatitude(), projection.getLongitude(), rangeM, coneDeg)) {
			return true;
		}
		// The closest point may sit outside the cone while part of the segment is inside it, so
		// walk the in-range stretch. Restricting to that stretch keeps the sample count bounded
		// regardless of how long the road segment is.
		double segLen = MapUtils.getDistance(lat1, lon1, lat2, lon2);
		if (segLen < 0.5) {
			return covers(camera, lat1, lon1, rangeM, coneDeg);
		}
		double halfChord = Math.sqrt(Math.max(0, rangeM * rangeM - orthogonal * orthogonal));
		double tCenter = MapUtils.getProjectionCoeff(camLat, camLon, lat1, lon1, lat2, lon2);
		double tLow = Math.max(0, tCenter - halfChord / segLen);
		double tHigh = Math.min(1, tCenter + halfChord / segLen);
		if (tHigh < tLow) {
			return false;
		}
		double spanM = (tHigh - tLow) * segLen;
		int samples = (int) Math.min(MAX_SAMPLES, Math.max(1, Math.ceil(spanM / SAMPLE_STEP_M)));
		for (int i = 0; i <= samples; i++) {
			double t = tLow + (tHigh - tLow) * i / samples;
			double lat = lat1 + (lat2 - lat1) * t;
			double lon = lon1 + (lon2 - lon1) * t;
			if (covers(camera, lat, lon, rangeM, coneDeg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Initial bearing from one point to another, in degrees clockwise from north.
	 */
	public static double bearingTo(double fromLat, double fromLon, double toLat, double toLon) {
		double lat1 = Math.toRadians(fromLat);
		double lat2 = Math.toRadians(toLat);
		double dLon = Math.toRadians(toLon - fromLon);
		double y = Math.sin(dLon) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
		double bearing = Math.toDegrees(Math.atan2(y, x));
		return bearing < 0 ? bearing + 360 : bearing;
	}
}
