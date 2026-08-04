package net.osmand.router.deflock;

import net.osmand.data.QuadRect;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a region's bounding box into query-sized cells for downloading camera data.
 *
 * <p>A single Overpass query over a country-sized box times out, so a region is fetched as a
 * grid of smaller queries and merged. This lives in OsmAnd-java, away from the Android
 * networking code, because it is pure geometry and is the part most likely to misbehave on a
 * real region - so it needs to be unit testable.
 */
public class AlprQueryGrid {

	/**
	 * Longest side of a single query, in degrees. Roughly two degrees keeps an individual query
	 * comfortable without making a small region take many round trips.
	 */
	public static final double MAX_QUERY_SPAN_DEG = 2.0;

	/** Guard against a pathological bounding box turning into thousands of requests. */
	public static final int MAX_QUERY_CELLS = 240;

	private AlprQueryGrid() {
	}

	/**
	 * @return cells that exactly tile the given bounds, with no gaps and no overlap
	 */
	public static List<QuadRect> split(QuadRect bounds) {
		return split(bounds, MAX_QUERY_SPAN_DEG, MAX_QUERY_CELLS);
	}

	static List<QuadRect> split(QuadRect bounds, double maxSpanDeg, int maxCells) {
		List<QuadRect> cells = new ArrayList<>();
		if (bounds == null) {
			return cells;
		}
		double west = Math.min(bounds.left, bounds.right);
		double east = Math.max(bounds.left, bounds.right);
		double south = Math.min(bounds.top, bounds.bottom);
		double north = Math.max(bounds.top, bounds.bottom);

		int cols = (int) Math.max(1, Math.ceil((east - west) / maxSpanDeg));
		int rows = (int) Math.max(1, Math.ceil((north - south) / maxSpanDeg));
		// Halve the axis that currently has the most divisions, so the grid stays as square as it
		// can. Always halving one axis would drive a long thin region down to a single row of
		// enormous cells along its long side, which is the shape that times out.
		while ((long) cols * rows > maxCells) {
			if (cols >= rows && cols > 1) {
				cols = cols / 2;
			} else if (rows > 1) {
				rows = rows / 2;
			} else {
				break;
			}
		}
		for (int c = 0; c < cols; c++) {
			double l = west + (east - west) * c / cols;
			double r = west + (east - west) * (c + 1) / cols;
			for (int rIdx = 0; rIdx < rows; rIdx++) {
				double b = south + (north - south) * rIdx / rows;
				double t = south + (north - south) * (rIdx + 1) / rows;
				cells.add(new QuadRect(l, t, r, b));
			}
		}
		return cells;
	}
}
