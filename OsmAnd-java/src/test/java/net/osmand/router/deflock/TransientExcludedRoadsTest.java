package net.osmand.router.deflock;

import net.osmand.router.RoutingConfiguration;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * ALPR camera avoidance feeds the router through the transient excluded-road set. These tests pin
 * down the two properties the feature depends on: the ids really do reach the router, and they
 * never leak into the next calculation or into the user's saved "Avoid roads" list.
 */
public class TransientExcludedRoadsTest {

	private static final RoutingMemoryLimits LIMITS = new RoutingMemoryLimits(64, 64);

	private static Set<Long> asSet(long... ids) {
		Set<Long> set = new HashSet<>();
		for (long id : ids) {
			set.add(id);
		}
		return set;
	}

	private static Set<Long> impassableOf(RoutingConfiguration config) {
		Set<Long> result = new HashSet<>();
		for (long id : config.router.getImpassableRoadIds()) {
			result.add(id);
		}
		return result;
	}

	private static RoutingConfiguration build(RoutingConfiguration.Builder builder) {
		return builder.build("car", LIMITS, new LinkedHashMap<>());
	}

	@Test
	public void excludedRoadsReachTheRouter() {
		RoutingConfiguration.Builder builder = RoutingConfiguration.parseDefault();
		builder.setTransientExcludedRoads(asSet(11L, 22L, 33L));
		Assert.assertEquals(asSet(11L, 22L, 33L), impassableOf(build(builder)));
	}

	@Test
	public void exclusionsDoNotSurviveIntoTheNextCalculation() {
		RoutingConfiguration.Builder builder = RoutingConfiguration.parseDefault();
		builder.setTransientExcludedRoads(asSet(11L, 22L));
		Assert.assertEquals(2, impassableOf(build(builder)).size());

		// Every route calculation sets the field again, including with nothing to exclude.
		builder.setTransientExcludedRoads(null);
		Assert.assertTrue(impassableOf(build(builder)).isEmpty());

		builder.setTransientExcludedRoads(new HashSet<>());
		Assert.assertTrue(impassableOf(build(builder)).isEmpty());
	}

	@Test
	public void exclusionsCombineWithButDoNotAlterUserAvoidedRoads() {
		RoutingConfiguration.Builder builder = RoutingConfiguration.parseDefault();
		builder.addImpassableRoad(99L);
		builder.setTransientExcludedRoads(asSet(11L));

		Assert.assertEquals(asSet(11L, 99L), impassableOf(build(builder)));
		// The user's own list is untouched by camera avoidance.
		Assert.assertEquals(asSet(99L), builder.getImpassableRoadLocations());

		builder.setTransientExcludedRoads(null);
		Assert.assertEquals(asSet(99L), impassableOf(build(builder)));
	}

	@Test
	public void settingExclusionsCopiesTheCallersSet() {
		RoutingConfiguration.Builder builder = RoutingConfiguration.parseDefault();
		Set<Long> caller = asSet(11L);
		builder.setTransientExcludedRoads(caller);
		caller.add(22L);
		Assert.assertEquals(asSet(11L), impassableOf(build(builder)));
	}
}
