package net.osmand.router.deflock;

import net.osmand.data.QuadRect;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class AlprQueryGridTest {

	/** QuadRect for lat/lon: left=west, top=north, right=east, bottom=south. */
	private static QuadRect box(double west, double north, double east, double south) {
		return new QuadRect(west, north, east, south);
	}

	private static double area(QuadRect r) {
		return Math.abs(r.right - r.left) * Math.abs(r.top - r.bottom);
	}

	@Test
	public void smallRegionIsASingleQuery() {
		// Indiana-sized in one dimension but under the span limit in both.
		List<QuadRect> cells = AlprQueryGrid.split(box(-86.5, 39.9, -85.5, 39.0));
		Assert.assertEquals(1, cells.size());
	}

	@Test
	public void largeRegionIsSplitIntoAGrid() {
		// 8 degrees by 6: expect 4 columns and 3 rows at a 2 degree span.
		List<QuadRect> cells = AlprQueryGrid.split(box(-90, 44, -82, 38));
		Assert.assertEquals(12, cells.size());
	}

	@Test
	public void cellsTileTheBoundsWithoutGapsOrOverlap() {
		QuadRect bounds = box(-90, 44, -82, 38);
		List<QuadRect> cells = AlprQueryGrid.split(bounds);

		double total = 0;
		double minWest = Double.MAX_VALUE, maxEast = -Double.MAX_VALUE;
		double minSouth = Double.MAX_VALUE, maxNorth = -Double.MAX_VALUE;
		for (QuadRect cell : cells) {
			total += area(cell);
			minWest = Math.min(minWest, Math.min(cell.left, cell.right));
			maxEast = Math.max(maxEast, Math.max(cell.left, cell.right));
			minSouth = Math.min(minSouth, Math.min(cell.top, cell.bottom));
			maxNorth = Math.max(maxNorth, Math.max(cell.top, cell.bottom));
		}
		// Areas summing to the whole means no gaps; combined with the union matching the
		// original extent, it also means no overlap.
		Assert.assertEquals(area(bounds), total, 1e-9);
		Assert.assertEquals(-90, minWest, 1e-9);
		Assert.assertEquals(-82, maxEast, 1e-9);
		Assert.assertEquals(38, minSouth, 1e-9);
		Assert.assertEquals(44, maxNorth, 1e-9);
	}

	@Test
	public void everyCellIsWithinTheSpanLimit() {
		List<QuadRect> cells = AlprQueryGrid.split(box(-100, 50, -80, 30));
		for (QuadRect cell : cells) {
			Assert.assertTrue("cell too wide", Math.abs(cell.right - cell.left) <= AlprQueryGrid.MAX_QUERY_SPAN_DEG + 1e-9);
			Assert.assertTrue("cell too tall", Math.abs(cell.top - cell.bottom) <= AlprQueryGrid.MAX_QUERY_SPAN_DEG + 1e-9);
		}
	}

	@Test
	public void cellCapBindsOnAWholeWorldBox() {
		List<QuadRect> cells = AlprQueryGrid.split(box(-180, 85, 180, -85));
		Assert.assertTrue("cap not applied: " + cells.size(),
				cells.size() <= AlprQueryGrid.MAX_QUERY_CELLS);
		Assert.assertFalse(cells.isEmpty());
	}

	@Test
	public void cappingStillTilesTheWholeBounds() {
		QuadRect bounds = box(-180, 85, 180, -85);
		List<QuadRect> cells = AlprQueryGrid.split(bounds);
		double total = 0;
		for (QuadRect cell : cells) {
			total += area(cell);
		}
		// Cells get larger than the span limit when capped, but must still cover everything.
		Assert.assertEquals(area(bounds), total, 1e-6);
	}

	@Test
	public void longThinRegionDoesNotCollapseToOneCell() {
		// Very wide, very short. Halving the wider axis first should keep it split.
		List<QuadRect> cells = AlprQueryGrid.split(box(-180, 1, 180, -1), 2.0, 8);
		Assert.assertTrue("expected several cells, got " + cells.size(), cells.size() > 1);
		Assert.assertTrue(cells.size() <= 8);
	}

	@Test
	public void handlesInvertedRectangles() {
		List<QuadRect> normal = AlprQueryGrid.split(box(-90, 44, -82, 38));
		List<QuadRect> inverted = AlprQueryGrid.split(box(-82, 38, -90, 44));
		Assert.assertEquals(normal.size(), inverted.size());
	}

	@Test
	public void degenerateBoundsProduceOneCell() {
		List<QuadRect> cells = AlprQueryGrid.split(box(-86, 39, -86, 39));
		Assert.assertEquals(1, cells.size());
	}

	@Test
	public void nullBoundsProduceNoCells() {
		Assert.assertTrue(AlprQueryGrid.split(null).isEmpty());
	}
}
