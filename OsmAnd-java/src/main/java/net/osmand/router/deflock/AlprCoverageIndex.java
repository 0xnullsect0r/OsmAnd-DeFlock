package net.osmand.router.deflock;

import net.osmand.data.QuadRect;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks which ground has offline ALPR camera data, so the app can say whether a route was
 * planned against complete information.
 *
 * <p>This matters more than it sounds: avoidance that runs over an area with no downloaded
 * cameras will happily report "no cameras in view", which is indistinguishable from a genuinely
 * clean route unless the coverage is reported alongside it.
 */
public class AlprCoverageIndex {

	public enum Coverage {
		/** Every part of the area has camera data. */
		FULL,
		/** Some of the area has camera data; the rest is unknown. */
		PARTIAL,
		/** No camera data for any of the area. */
		NONE
	}

	/**
	 * Samples per axis when testing an area. Coverage is decided by sampling rather than exact
	 * rectangle subtraction: the answer only drives a warning, and this keeps the cost fixed
	 * regardless of how many regions are downloaded.
	 */
	static final int SAMPLES_PER_AXIS = 12;

	private final List<QuadRect> regions = new ArrayList<>();

	public void add(QuadRect latLonBounds) {
		if (latLonBounds != null) {
			regions.add(normalize(latLonBounds));
		}
	}

	public void clear() {
		regions.clear();
	}

	public boolean isEmpty() {
		return regions.isEmpty();
	}

	public int size() {
		return regions.size();
	}

	/**
	 * @return true when the point falls inside any region that has camera data
	 */
	public boolean covers(double lat, double lon) {
		for (QuadRect r : regions) {
			if (lon >= r.left && lon <= r.right && lat >= r.bottom && lat <= r.top) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return how much of the given area has camera data
	 */
	public Coverage coverageOf(QuadRect latLonBounds) {
		if (regions.isEmpty() || latLonBounds == null) {
			return Coverage.NONE;
		}
		QuadRect q = normalize(latLonBounds);
		boolean any = false;
		boolean all = true;
		for (int i = 0; i <= SAMPLES_PER_AXIS; i++) {
			double lon = q.left + (q.right - q.left) * i / SAMPLES_PER_AXIS;
			for (int j = 0; j <= SAMPLES_PER_AXIS; j++) {
				double lat = q.bottom + (q.top - q.bottom) * j / SAMPLES_PER_AXIS;
				if (covers(lat, lon)) {
					any = true;
				} else {
					all = false;
				}
				if (any && !all) {
					return Coverage.PARTIAL;
				}
			}
		}
		return all ? Coverage.FULL : Coverage.NONE;
	}

	/**
	 * QuadRect for lat/lon holds top as the maximum latitude, but callers build them in various
	 * ways, so normalise before comparing.
	 */
	private static QuadRect normalize(QuadRect r) {
		return new QuadRect(Math.min(r.left, r.right), Math.max(r.top, r.bottom),
				Math.max(r.left, r.right), Math.min(r.top, r.bottom));
	}
}
