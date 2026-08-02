package net.osmand.router.deflock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single automated license plate reader (ALPR) camera, as mapped in OpenStreetMap by the
 * DeFlock project (https://deflock.org). Cameras are nodes tagged
 * {@code man_made=surveillance} + {@code surveillance:type=ALPR}, normally carrying a
 * {@code direction} tag with the bearing the camera faces.
 *
 * <p>This lives in OsmAnd-java (rather than the Android module) so that both the map layer and
 * the routing code can share it, and so the geometry can be unit tested on the JVM.
 */
public class AlprCameraPoint {

	public static final String TAG_SURVEILLANCE_TYPE = "surveillance:type";
	public static final String TAG_DIRECTION = "direction";
	public static final String TAG_CAMERA_DIRECTION = "camera:direction";

	private final long osmId;
	private final double lat;
	private final double lon;
	// Degrees clockwise from north. Null means the facing is unknown, and the camera is then
	// treated as omnidirectional so that we never under-report coverage.
	private final Float direction;
	private final Map<String, String> tags;

	public AlprCameraPoint(long osmId, double lat, double lon, Float direction) {
		this(osmId, lat, lon, direction, null);
	}

	public AlprCameraPoint(long osmId, double lat, double lon, Float direction, Map<String, String> tags) {
		this.osmId = osmId;
		this.lat = lat;
		this.lon = lon;
		this.direction = direction;
		this.tags = tags == null ? Collections.<String, String>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}

	/**
	 * Builds a camera from raw OSM tags, reading the facing from {@code direction} and falling
	 * back to {@code camera:direction}. In a sample of live Overpass data {@code direction} was
	 * present on essentially every ALPR node and {@code camera:direction} on only a few percent.
	 */
	public static AlprCameraPoint fromTags(long osmId, double lat, double lon, Map<String, String> tags) {
		Float dir = null;
		if (tags != null) {
			dir = parseDirection(tags.get(TAG_DIRECTION));
			if (dir == null) {
				dir = parseDirection(tags.get(TAG_CAMERA_DIRECTION));
			}
		}
		return new AlprCameraPoint(osmId, lat, lon, dir, tags);
	}

	/**
	 * Parses an OSM direction value into degrees clockwise from north.
	 * Accepts plain degrees ("240", "2", "137.5"), the 16 compass points ("N", "NNE", "northeast",
	 * "north-east"), and ranges ("45-135", meaning a sector) for which the midpoint is returned.
	 *
	 * @return the bearing normalised to [0, 360), or null if the value cannot be interpreted
	 */
	public static Float parseDirection(String value) {
		if (value == null) {
			return null;
		}
		String v = value.trim();
		if (v.isEmpty()) {
			return null;
		}
		Float degrees = parseDegrees(v);
		if (degrees != null) {
			return degrees;
		}
		Float cardinal = parseCardinal(v);
		if (cardinal != null) {
			return cardinal;
		}
		return parseRange(v);
	}

	private static Float parseDegrees(String v) {
		try {
			return normalize(Float.parseFloat(v));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Float parseCardinal(String v) {
		String key = v.toUpperCase().replace("-", "").replace("_", "").replace(" ", "");
		switch (key) {
			case "N":
			case "NORTH":
				return 0f;
			case "NNE":
			case "NORTHNORTHEAST":
				return 22.5f;
			case "NE":
			case "NORTHEAST":
				return 45f;
			case "ENE":
			case "EASTNORTHEAST":
				return 67.5f;
			case "E":
			case "EAST":
				return 90f;
			case "ESE":
			case "EASTSOUTHEAST":
				return 112.5f;
			case "SE":
			case "SOUTHEAST":
				return 135f;
			case "SSE":
			case "SOUTHSOUTHEAST":
				return 157.5f;
			case "S":
			case "SOUTH":
				return 180f;
			case "SSW":
			case "SOUTHSOUTHWEST":
				return 202.5f;
			case "SW":
			case "SOUTHWEST":
				return 225f;
			case "WSW":
			case "WESTSOUTHWEST":
				return 247.5f;
			case "W":
			case "WEST":
				return 270f;
			case "WNW":
			case "WESTNORTHWEST":
				return 292.5f;
			case "NW":
			case "NORTHWEST":
				return 315f;
			case "NNW":
			case "NORTHNORTHWEST":
				return 337.5f;
			default:
				return null;
		}
	}

	/**
	 * Handles sector values such as "45-135" by returning the middle of the sector. The sector may
	 * wrap through north ("350-10" -> 0).
	 */
	private static Float parseRange(String v) {
		int sep = v.indexOf('-', 1);
		if (sep <= 0 || sep == v.length() - 1) {
			return null;
		}
		Float from = parseDegrees(v.substring(0, sep).trim());
		Float to = parseDegrees(v.substring(sep + 1).trim());
		if (from == null || to == null) {
			return null;
		}
		float sweep = to - from;
		while (sweep < 0) {
			sweep += 360;
		}
		return normalize(from + sweep / 2);
	}

	private static float normalize(float degrees) {
		if (Float.isNaN(degrees) || Float.isInfinite(degrees)) {
			return 0f;
		}
		float d = degrees % 360;
		return d < 0 ? d + 360 : d;
	}

	public long getOsmId() {
		return osmId;
	}

	public double getLatitude() {
		return lat;
	}

	public double getLongitude() {
		return lon;
	}

	/**
	 * @return the facing in degrees clockwise from north, or null when unknown
	 */
	public Float getDirection() {
		return direction;
	}

	public boolean hasDirection() {
		return direction != null;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public String getTag(String key) {
		return tags.get(key);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AlprCameraPoint)) {
			return false;
		}
		return osmId == ((AlprCameraPoint) o).osmId;
	}

	@Override
	public int hashCode() {
		return (int) (osmId ^ (osmId >>> 32));
	}

	@Override
	public String toString() {
		return "ALPR camera " + osmId + " at " + lat + "," + lon
				+ (direction == null ? " (no direction)" : " facing " + direction);
	}
}
