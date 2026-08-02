package net.osmand.router.deflock;

import net.osmand.data.LatLon;
import net.osmand.util.MapUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class CameraCoverageTest {

	// Somewhere in Indianapolis, where DeFlock coverage is dense.
	private static final double CAM_LAT = 39.7684;
	private static final double CAM_LON = -86.1581;

	private static final double RANGE = CameraCoverage.DEFAULT_RANGE_M;
	private static final double CONE = CameraCoverage.DEFAULT_CONE_DEG;

	private static AlprCameraPoint camera(Float direction) {
		return new AlprCameraPoint(1, CAM_LAT, CAM_LON, direction);
	}

	/** A point {@code metres} away from the camera on the given bearing. */
	private static LatLon away(double metres, double bearing) {
		return MapUtils.greatCircleDestinationPoint(CAM_LAT, CAM_LON, metres, bearing);
	}

	private static boolean coversAt(AlprCameraPoint cam, double metres, double bearing) {
		LatLon p = away(metres, bearing);
		return CameraCoverage.covers(cam, p.getLatitude(), p.getLongitude(), RANGE, CONE);
	}

	// --- direction parsing -------------------------------------------------

	@Test
	public void parsesPlainDegrees() {
		Assert.assertEquals(240f, AlprCameraPoint.parseDirection("240"), 0.001);
		Assert.assertEquals(2f, AlprCameraPoint.parseDirection("2"), 0.001);
		Assert.assertEquals(0f, AlprCameraPoint.parseDirection("0"), 0.001);
		Assert.assertEquals(137.5f, AlprCameraPoint.parseDirection("137.5"), 0.001);
		Assert.assertEquals(10f, AlprCameraPoint.parseDirection(" 10 "), 0.001);
	}

	@Test
	public void normalisesOutOfRangeDegrees() {
		Assert.assertEquals(10f, AlprCameraPoint.parseDirection("370"), 0.001);
		Assert.assertEquals(350f, AlprCameraPoint.parseDirection("-10"), 0.001);
		Assert.assertEquals(0f, AlprCameraPoint.parseDirection("360"), 0.001);
	}

	@Test
	public void parsesCardinalPoints() {
		Assert.assertEquals(0f, AlprCameraPoint.parseDirection("N"), 0.001);
		Assert.assertEquals(90f, AlprCameraPoint.parseDirection("E"), 0.001);
		Assert.assertEquals(180f, AlprCameraPoint.parseDirection("south"), 0.001);
		Assert.assertEquals(315f, AlprCameraPoint.parseDirection("NW"), 0.001);
		Assert.assertEquals(22.5f, AlprCameraPoint.parseDirection("NNE"), 0.001);
		Assert.assertEquals(135f, AlprCameraPoint.parseDirection("south-east"), 0.001);
	}

	@Test
	public void parsesSectorRangeAsMidpoint() {
		Assert.assertEquals(90f, AlprCameraPoint.parseDirection("45-135"), 0.001);
		// A sector wrapping through north.
		Assert.assertEquals(0f, AlprCameraPoint.parseDirection("350-10"), 0.001);
	}

	@Test
	public void rejectsUnparseableDirections() {
		Assert.assertNull(AlprCameraPoint.parseDirection(null));
		Assert.assertNull(AlprCameraPoint.parseDirection(""));
		Assert.assertNull(AlprCameraPoint.parseDirection("   "));
		Assert.assertNull(AlprCameraPoint.parseDirection("forward"));
	}

	@Test
	public void prefersDirectionOverCameraDirection() {
		Map<String, String> tags = new HashMap<>();
		tags.put("direction", "240");
		tags.put("camera:direction", "60");
		Assert.assertEquals(240f, AlprCameraPoint.fromTags(1, CAM_LAT, CAM_LON, tags).getDirection(), 0.001);
	}

	@Test
	public void fallsBackToCameraDirection() {
		Map<String, String> tags = new HashMap<>();
		tags.put("camera:direction", "60");
		Assert.assertEquals(60f, AlprCameraPoint.fromTags(1, CAM_LAT, CAM_LON, tags).getDirection(), 0.001);
	}

	@Test
	public void cameraWithoutDirectionTagHasNoDirection() {
		Map<String, String> tags = new HashMap<>();
		tags.put("man_made", "surveillance");
		Assert.assertFalse(AlprCameraPoint.fromTags(1, CAM_LAT, CAM_LON, tags).hasDirection());
	}

	// --- point coverage ----------------------------------------------------

	@Test
	public void coversPointInsideCone() {
		AlprCameraPoint east = camera(90f);
		Assert.assertTrue(coversAt(east, 30, 90));
		// Just inside the +/- 30 degree half angle.
		Assert.assertTrue(coversAt(east, 30, 118));
		Assert.assertTrue(coversAt(east, 30, 62));
	}

	@Test
	public void rejectsPointOutsideCone() {
		AlprCameraPoint east = camera(90f);
		// In range, but behind and beside the camera.
		Assert.assertFalse(coversAt(east, 30, 0));
		Assert.assertFalse(coversAt(east, 30, 270));
		Assert.assertFalse(coversAt(east, 30, 130));
	}

	@Test
	public void rejectsPointOutOfRange() {
		AlprCameraPoint east = camera(90f);
		Assert.assertTrue(coversAt(east, RANGE - 5, 90));
		Assert.assertFalse(coversAt(east, RANGE + 5, 90));
	}

	@Test
	public void coneWrapsAroundNorth() {
		AlprCameraPoint north = camera(0f);
		Assert.assertTrue(coversAt(north, 30, 0));
		Assert.assertTrue(coversAt(north, 30, 350));
		Assert.assertTrue(coversAt(north, 30, 10));
		Assert.assertFalse(coversAt(north, 30, 320));
		Assert.assertFalse(coversAt(north, 30, 40));

		AlprCameraPoint nearNorth = camera(350f);
		Assert.assertTrue(coversAt(nearNorth, 30, 5));
		Assert.assertFalse(coversAt(nearNorth, 30, 30));
	}

	@Test
	public void cameraWithoutDirectionIsOmnidirectional() {
		AlprCameraPoint unknown = camera(null);
		for (int bearing = 0; bearing < 360; bearing += 45) {
			Assert.assertTrue("bearing " + bearing, coversAt(unknown, 30, bearing));
		}
		// Still bounded by range.
		Assert.assertFalse(coversAt(unknown, RANGE + 5, 0));
	}

	@Test
	public void pointAtCameraIsCovered() {
		Assert.assertTrue(CameraCoverage.covers(camera(90f), CAM_LAT, CAM_LON, RANGE, CONE));
	}

	@Test
	public void fullCircleConeCoversEveryBearing() {
		AlprCameraPoint east = camera(90f);
		LatLon behind = away(30, 270);
		Assert.assertTrue(CameraCoverage.covers(east, behind.getLatitude(), behind.getLongitude(), RANGE, 360));
	}

	// --- segment coverage --------------------------------------------------

	@Test
	public void coversSegmentCrossingTheCone() {
		AlprCameraPoint east = camera(90f);
		// A road running north-south, 30 m east of the camera: it cuts straight through the cone.
		LatLon a = MapUtils.greatCircleDestinationPoint(away(30, 90).getLatitude(),
				away(30, 90).getLongitude(), 40, 0);
		LatLon b = MapUtils.greatCircleDestinationPoint(away(30, 90).getLatitude(),
				away(30, 90).getLongitude(), 40, 180);
		Assert.assertTrue(CameraCoverage.coversSegment(east,
				a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), RANGE, CONE));
	}

	@Test
	public void rejectsSegmentBehindTheCamera() {
		AlprCameraPoint east = camera(90f);
		// Same north-south road, but 30 m west of the camera - in range, out of view.
		LatLon centre = away(30, 270);
		LatLon a = MapUtils.greatCircleDestinationPoint(centre.getLatitude(), centre.getLongitude(), 40, 0);
		LatLon b = MapUtils.greatCircleDestinationPoint(centre.getLatitude(), centre.getLongitude(), 40, 180);
		Assert.assertFalse(CameraCoverage.coversSegment(east,
				a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), RANGE, CONE));
	}

	@Test
	public void rejectsSegmentOutOfRange() {
		AlprCameraPoint east = camera(90f);
		LatLon centre = away(RANGE + 100, 90);
		LatLon a = MapUtils.greatCircleDestinationPoint(centre.getLatitude(), centre.getLongitude(), 40, 0);
		LatLon b = MapUtils.greatCircleDestinationPoint(centre.getLatitude(), centre.getLongitude(), 40, 180);
		Assert.assertFalse(CameraCoverage.coversSegment(east,
				a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), RANGE, CONE));
	}

	/**
	 * The closest point of the road to the camera can sit outside the cone while a further-away
	 * part of the same segment is inside it. Sampling has to catch that.
	 */
	@Test
	public void coversSegmentWhoseNearestPointIsOutsideTheCone() {
		AlprCameraPoint east = camera(90f);
		// A long road running east, starting right beside the camera (bearing 0 from it, out of
		// view) and continuing east across the cone.
		LatLon start = away(2, 0);
		LatLon end = MapUtils.greatCircleDestinationPoint(start.getLatitude(), start.getLongitude(), 2000, 90);
		Assert.assertTrue(CameraCoverage.coversSegment(east,
				start.getLatitude(), start.getLongitude(), end.getLatitude(), end.getLongitude(), RANGE, CONE));
	}

	@Test
	public void handlesDegenerateZeroLengthSegment() {
		AlprCameraPoint east = camera(90f);
		LatLon p = away(30, 90);
		Assert.assertTrue(CameraCoverage.coversSegment(east,
				p.getLatitude(), p.getLongitude(), p.getLatitude(), p.getLongitude(), RANGE, CONE));
		LatLon behind = away(30, 270);
		Assert.assertFalse(CameraCoverage.coversSegment(east,
				behind.getLatitude(), behind.getLongitude(), behind.getLatitude(), behind.getLongitude(), RANGE, CONE));
	}

	@Test
	public void bearingToIsClockwiseFromNorth() {
		Assert.assertEquals(0, CameraCoverage.bearingTo(CAM_LAT, CAM_LON,
				away(50, 0).getLatitude(), away(50, 0).getLongitude()), 0.5);
		Assert.assertEquals(90, CameraCoverage.bearingTo(CAM_LAT, CAM_LON,
				away(50, 90).getLatitude(), away(50, 90).getLongitude()), 0.5);
		Assert.assertEquals(270, CameraCoverage.bearingTo(CAM_LAT, CAM_LON,
				away(50, 270).getLatitude(), away(50, 270).getLongitude()), 0.5);
	}
}
