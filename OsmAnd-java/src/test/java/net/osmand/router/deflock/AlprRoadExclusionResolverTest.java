package net.osmand.router.deflock;

import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class AlprRoadExclusionResolverTest {

	private static AlprRoadExclusionResolver.Result sampleResult() {
		AlprRoadExclusionResolver.Result result = new AlprRoadExclusionResolver.Result();
		result.addWatchedRoad(1, "motorway", 100);
		result.addWatchedRoad(2, "trunk", 100);
		result.addWatchedRoad(3, "primary", 101);
		result.addWatchedRoad(4, "secondary", 101);
		result.addWatchedRoad(5, "residential", 102);
		result.addWatchedRoad(6, null, 102);
		return result;
	}

	@Test
	public void collectsRoadsAndCameras() {
		AlprRoadExclusionResolver.Result result = sampleResult();
		Assert.assertEquals(6, result.getExcludedRoadIds().size());
		// Three distinct cameras contributed, even though they watch six roads between them.
		Assert.assertEquals(3, result.getCameraCount());
		Assert.assertFalse(result.isEmpty());
	}

	@Test
	public void firstRelaxationRoundReadmitsMotorwaysAndTrunks() {
		Set<Long> relaxed = AlprRoadExclusionResolver.relaxByRoadClass(sampleResult(), 0);
		Assert.assertFalse(relaxed.contains(1L));
		Assert.assertFalse(relaxed.contains(2L));
		Assert.assertTrue(relaxed.contains(3L));
		Assert.assertTrue(relaxed.contains(4L));
		Assert.assertTrue(relaxed.contains(5L));
	}

	@Test
	public void laterRoundsReadmitProgressivelySlowerRoads() {
		Set<Long> round1 = AlprRoadExclusionResolver.relaxByRoadClass(sampleResult(), 1);
		Assert.assertFalse(round1.contains(3L));
		Assert.assertTrue(round1.contains(4L));

		Set<Long> round2 = AlprRoadExclusionResolver.relaxByRoadClass(sampleResult(), 2);
		Assert.assertFalse(round2.contains(4L));
		// Minor roads are never re-admitted: detouring around them is cheap.
		Assert.assertTrue(round2.contains(5L));
		Assert.assertTrue(round2.contains(6L));
	}

	@Test
	public void relaxationIsMonotonic() {
		int previous = Integer.MAX_VALUE;
		for (int round = 0; round < AlprRoadExclusionResolver.getRelaxationRounds(); round++) {
			int size = AlprRoadExclusionResolver.relaxByRoadClass(sampleResult(), round).size();
			Assert.assertTrue("round " + round + " grew the exclusion set", size <= previous);
			previous = size;
		}
	}

	@Test
	public void relaxationDoesNotMutateTheResult() {
		AlprRoadExclusionResolver.Result result = sampleResult();
		AlprRoadExclusionResolver.relaxByRoadClass(result, 2);
		Assert.assertEquals(6, result.getExcludedRoadIds().size());
	}

	@Test
	public void emptyResultRelaxesToNothing() {
		AlprRoadExclusionResolver.Result empty = new AlprRoadExclusionResolver.Result();
		Assert.assertTrue(empty.isEmpty());
		Assert.assertTrue(AlprRoadExclusionResolver.relaxByRoadClass(empty, 0).isEmpty());
	}
}
