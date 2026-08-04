package net.osmand.router.deflock;

import net.osmand.data.QuadRect;
import net.osmand.router.deflock.AlprCoverageIndex.Coverage;

import org.junit.Assert;
import org.junit.Test;

public class AlprCoverageIndexTest {

	/** QuadRect for lat/lon: left=west, top=north, right=east, bottom=south. */
	private static QuadRect box(double west, double north, double east, double south) {
		return new QuadRect(west, north, east, south);
	}

	@Test
	public void emptyIndexCoversNothing() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		Assert.assertTrue(index.isEmpty());
		Assert.assertEquals(Coverage.NONE, index.coverageOf(box(-87, 40, -86, 39)));
		Assert.assertFalse(index.covers(39.5, -86.5));
	}

	@Test
	public void areaInsideOneRegionIsFullyCovered() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		index.add(box(-88, 41, -84, 37));
		Assert.assertEquals(Coverage.FULL, index.coverageOf(box(-87, 40, -86, 39)));
	}

	@Test
	public void areaOutsideEveryRegionIsUncovered() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		index.add(box(-88, 41, -84, 37));
		Assert.assertEquals(Coverage.NONE, index.coverageOf(box(-70, 45, -69, 44)));
	}

	@Test
	public void areaStraddlingARegionEdgeIsPartial() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		index.add(box(-88, 41, -86, 37));
		// Extends east well past the region's edge at -86.
		Assert.assertEquals(Coverage.PARTIAL, index.coverageOf(box(-87, 40, -84, 39)));
	}

	@Test
	public void adjacentRegionsTogetherCoverAnAreaNeitherCoversAlone() {
		QuadRect area = box(-87, 40, -85, 39);

		AlprCoverageIndex westOnly = new AlprCoverageIndex();
		westOnly.add(box(-88, 41, -86, 37));
		Assert.assertEquals(Coverage.PARTIAL, westOnly.coverageOf(area));

		AlprCoverageIndex both = new AlprCoverageIndex();
		both.add(box(-88, 41, -86, 37));
		both.add(box(-86, 41, -84, 37));
		Assert.assertEquals(Coverage.FULL, both.coverageOf(area));
	}

	@Test
	public void pointCoverageRespectsRegionBounds() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		index.add(box(-88, 41, -84, 37));
		Assert.assertTrue(index.covers(39, -86));
		Assert.assertTrue("boundary counts as covered", index.covers(41, -88));
		Assert.assertFalse(index.covers(42, -86));
		Assert.assertFalse(index.covers(39, -89));
	}

	@Test
	public void handlesRectanglesGivenWithInvertedEdges() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		// Same region expressed with top/bottom and left/right the wrong way round.
		index.add(box(-84, 37, -88, 41));
		Assert.assertEquals(Coverage.FULL, index.coverageOf(box(-86, 39, -87, 40)));
	}

	@Test
	public void clearRemovesAllCoverage() {
		AlprCoverageIndex index = new AlprCoverageIndex();
		index.add(box(-88, 41, -84, 37));
		Assert.assertEquals(1, index.size());
		index.clear();
		Assert.assertTrue(index.isEmpty());
		Assert.assertEquals(Coverage.NONE, index.coverageOf(box(-87, 40, -86, 39)));
	}
}
