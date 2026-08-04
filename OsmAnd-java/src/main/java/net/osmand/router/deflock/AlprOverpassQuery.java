package net.osmand.router.deflock;

import net.osmand.data.QuadRect;

/**
 * Builds the Overpass QL used to fetch ALPR cameras.
 *
 * <p>This is a string, and it lives here rather than next to the networking code for one reason:
 * a wrong output mode in it is silent. Overpass answers HTTP 200 either way, and the difference
 * only shows up as cameras quietly failing to appear. Here it can be unit tested.
 */
public class AlprOverpassQuery {

	/**
	 * Overpass output mode.
	 *
	 * <p>Must be {@code body}. {@code out tags} returns ids and tags but <strong>no
	 * coordinates</strong> for nodes, which is not a smaller answer - it is an unusable one, and
	 * it cost this project a release: every camera was fetched and then dropped for having no
	 * position. Measured against the live API over one bounding box:
	 *
	 * <pre>
	 *   out tags;  ->  178 elements, 0 with lat/lon
	 *   out body;  ->  178 elements, 178 with lat/lon
	 * </pre>
	 *
	 * {@code out skel} is the opposite trap: coordinates but no tags, so no direction.
	 */
	public static final String OUT_MODE = "body";

	/** Seconds Overpass is allowed to spend before giving up on one query. */
	public static final int TIMEOUT_S = 90;

	/**
	 * The tag every DeFlock node carries. {@code manufacturer=Flock Safety} is only on some of
	 * them, and {@code man_made=surveillance} is on far more than just plate readers.
	 */
	public static final String SELECTOR = "[\"surveillance:type\"=\"ALPR\"]";

	private AlprOverpassQuery() {
	}

	/**
	 * @param latLonBounds area to search; may be inverted on either axis
	 * @return an Overpass QL query returning every ALPR camera node in the bounds, with position
	 */
	public static String forBounds(QuadRect latLonBounds) {
		// QuadRect for lat/lon holds top = max latitude, bottom = min latitude, but callers pass
		// rectangles from several sources, so normalise rather than trust the convention.
		double south = Math.min(latLonBounds.top, latLonBounds.bottom);
		double north = Math.max(latLonBounds.top, latLonBounds.bottom);
		double west = Math.min(latLonBounds.left, latLonBounds.right);
		double east = Math.max(latLonBounds.left, latLonBounds.right);

		// Overpass takes a bounding box as (south, west, north, east).
		return "[out:json][timeout:" + TIMEOUT_S + "];"
				+ "node" + SELECTOR + "("
				+ south + "," + west + "," + north + "," + east + ");"
				+ "out " + OUT_MODE + ";";
	}
}
