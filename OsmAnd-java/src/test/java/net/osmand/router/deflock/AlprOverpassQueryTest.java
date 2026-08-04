package net.osmand.router.deflock;

import net.osmand.data.QuadRect;

import org.junit.Assert;
import org.junit.Test;

public class AlprOverpassQueryTest {

	/** QuadRect for lat/lon: left=west, top=north, right=east, bottom=south. */
	private static QuadRect box(double west, double north, double east, double south) {
		return new QuadRect(west, north, east, south);
	}

	/**
	 * The regression this whole class exists for. "out tags" returns no coordinates, so every
	 * camera is fetched and then discarded for having no position, while the request still
	 * succeeds - which looks exactly like an area with no cameras in it.
	 */
	@Test
	public void asksForBodyNotTags() {
		String query = AlprOverpassQuery.forBounds(box(-80.9, 35.35, -80.7, 35.1));
		Assert.assertTrue("must request body: " + query, query.contains("out body;"));
		Assert.assertFalse("out tags returns no coordinates: " + query, query.contains("out tags"));
		Assert.assertFalse("out skel returns no tags: " + query, query.contains("out skel"));
	}

	@Test
	public void emitsBoundsAsSouthWestNorthEast() {
		String query = AlprOverpassQuery.forBounds(box(-80.9, 35.35, -80.7, 35.1));
		Assert.assertTrue(query, query.contains("(35.1,-80.9,35.35,-80.7)"));
	}

	@Test
	public void normalisesInvertedBounds() {
		String upright = AlprOverpassQuery.forBounds(box(-80.9, 35.35, -80.7, 35.1));
		String flipped = AlprOverpassQuery.forBounds(box(-80.7, 35.1, -80.9, 35.35));
		Assert.assertEquals(upright, flipped);
	}

	@Test
	public void selectsAlprCamerasOnly() {
		String query = AlprOverpassQuery.forBounds(box(-1, 1, 1, -1));
		Assert.assertTrue(query, query.contains("node[\"surveillance:type\"=\"ALPR\"]"));
	}

	@Test
	public void requestsJsonAndATimeout() {
		String query = AlprOverpassQuery.forBounds(box(-1, 1, 1, -1));
		Assert.assertTrue(query, query.startsWith("[out:json][timeout:"));
	}
}
