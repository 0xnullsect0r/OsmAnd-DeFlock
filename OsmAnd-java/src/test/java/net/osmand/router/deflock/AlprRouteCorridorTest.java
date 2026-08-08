package net.osmand.router.deflock;

import net.osmand.data.LatLon;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlprRouteCorridorTest {

	/** Roughly 1 km of west-to-east road at a latitude where a degree of longitude is ~90 km. */
	private static List<LatLon> route() {
		return Arrays.asList(
				new LatLon(35.20, -80.84),
				new LatLon(35.20, -80.83),
				new LatLon(35.20, -80.82));
	}

	private static AlprCameraPoint camera(long id, double lat, double lon, Float direction) {
		Map<String, String> tags = new HashMap<>();
		tags.put("man_made", "surveillance");
		tags.put("surveillance:type", "ALPR");
		return new AlprCameraPoint(id, lat, lon, direction, tags);
	}

	/** Metres north of a latitude, near enough for a test. */
	private static double northOf(double lat, double metres) {
		return lat + metres / 111320d;
	}

	// --- selection ---------------------------------------------------------

	/**
	 * The regression this class exists for.
	 *
	 * <p>A downloaded region holds far more cameras than one calculation can carry, so something
	 * has to be dropped. The old code dropped by OSM id order, which meant that on a long route
	 * inside a well-populated region the cameras actually along the road were thrown away and
	 * avoidance silently did nothing. Here the cameras near the route carry the *highest* ids, so
	 * an id-ordered cap keeps none of them.
	 */
	@Test
	public void keepsCamerasNearTheRouteEvenWhenTheirIdsSortLast() {
		List<AlprCameraPoint> cameras = new ArrayList<>();
		// 9000 far-away cameras with low ids, mimicking the rest of a downloaded region.
		for (int i = 0; i < 9000; i++) {
			cameras.add(camera(i, 34.0 + i * 0.0001, -79.0, 90f));
		}
		// The handful that actually matter, created later so their ids sort last.
		for (int i = 0; i < 5; i++) {
			cameras.add(camera(1_000_000 + i, northOf(35.20, 20), -80.835 + i * 0.001, 180f));
		}

		List<AlprCameraPoint> selected = AlprRouteCorridor.select(route(), cameras, 1500, 4000);

		Assert.assertEquals("only the near cameras should survive", 5, selected.size());
		for (AlprCameraPoint camera : selected) {
			Assert.assertTrue("kept a far camera: " + camera.getOsmId(),
					camera.getOsmId() >= 1_000_000);
		}
	}

	@Test
	public void dropsCamerasBeyondTheRadius() {
		List<AlprCameraPoint> cameras = Arrays.asList(
				camera(1, northOf(35.20, 100), -80.83, null),
				camera(2, northOf(35.20, 5000), -80.83, null));
		List<AlprCameraPoint> selected = AlprRouteCorridor.select(route(), cameras, 1500, 100);
		Assert.assertEquals(1, selected.size());
		Assert.assertEquals(1, selected.get(0).getOsmId());
	}

	@Test
	public void ordersByDistanceToTheRoute() {
		List<AlprCameraPoint> cameras = Arrays.asList(
				camera(1, northOf(35.20, 900), -80.83, null),
				camera(2, northOf(35.20, 50), -80.83, null),
				camera(3, northOf(35.20, 400), -80.83, null));
		List<AlprCameraPoint> selected = AlprRouteCorridor.select(route(), cameras, 1500, 100);
		Assert.assertEquals(Arrays.asList(2L, 3L, 1L), Arrays.asList(
				selected.get(0).getOsmId(), selected.get(1).getOsmId(), selected.get(2).getOsmId()));
	}

	@Test
	public void capKeepsTheNearestNotTheFirst() {
		List<AlprCameraPoint> cameras = Arrays.asList(
				camera(1, northOf(35.20, 1200), -80.83, null),
				camera(2, northOf(35.20, 30), -80.83, null));
		List<AlprCameraPoint> selected = AlprRouteCorridor.select(route(), cameras, 1500, 1);
		Assert.assertEquals(1, selected.size());
		Assert.assertEquals(2, selected.get(0).getOsmId());
	}

	@Test
	public void handlesNoRouteAndNoCameras() {
		Assert.assertTrue(AlprRouteCorridor.select(route(), null, 1500, 10).isEmpty());
		Assert.assertTrue(AlprRouteCorridor.select(null, null, 1500, 10).isEmpty());
		// A degenerate route cannot order anything, but must not lose the input either.
		List<AlprCameraPoint> one = Arrays.asList(camera(1, 35.2, -80.83, null));
		Assert.assertEquals(1, AlprRouteCorridor.select(null, one, 1500, 10).size());
	}

	// --- verification ------------------------------------------------------

	@Test
	public void countsACameraWatchingTheRoute() {
		// 20 m north of the road, facing south onto it.
		List<AlprCameraPoint> cameras = Arrays.asList(camera(1, northOf(35.20, 20), -80.83, 180f));
		Assert.assertEquals(1, AlprRouteCorridor.countWatching(route(), cameras, 60, 60, 0));
	}

	@Test
	public void ignoresACameraFacingAwayWhenThereIsNoMargin() {
		// 20 m north of the road but facing north, away from it.
		List<AlprCameraPoint> cameras = Arrays.asList(camera(1, northOf(35.20, 20), -80.83, 0f));
		Assert.assertEquals(0, AlprRouteCorridor.countWatching(route(), cameras, 60, 60, 0));
	}

	/** The keep-away radius is the point of the margin: direction stops excusing proximity. */
	@Test
	public void marginCatchesACameraFacingAway() {
		List<AlprCameraPoint> cameras = Arrays.asList(camera(1, northOf(35.20, 20), -80.83, 0f));
		Assert.assertEquals(1, AlprRouteCorridor.countWatching(route(), cameras, 60, 60, 150));
	}

	@Test
	public void marginDoesNotReachBeyondItself() {
		// 400 m away, facing away: outside both the cone and a 150 m keep-away radius.
		List<AlprCameraPoint> cameras = Arrays.asList(camera(1, northOf(35.20, 400), -80.83, 0f));
		Assert.assertEquals(0, AlprRouteCorridor.countWatching(route(), cameras, 60, 60, 150));
	}

	@Test
	public void countsEachCameraOnce() {
		// Sits near the join between two segments, so a naive count would see it twice.
		List<AlprCameraPoint> cameras = Arrays.asList(camera(1, northOf(35.20, 10), -80.83, 180f));
		Assert.assertEquals(1, AlprRouteCorridor.countWatching(route(), cameras, 60, 60, 0));
	}

	@Test
	public void distanceToRouteIsZeroOnTheLine() {
		Assert.assertEquals(0, AlprRouteCorridor.distanceToRoute(route(), 35.20, -80.83, 1500), 1.0);
	}
}
