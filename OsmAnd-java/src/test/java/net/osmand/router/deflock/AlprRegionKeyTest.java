package net.osmand.router.deflock;

import org.junit.Assert;
import org.junit.Test;

public class AlprRegionKeyTest {

	@Test
	public void derivesKeyFromDownloadedMapName() {
		// What OsmAnd actually writes to disk: DownloadActivityType.getBasename cuts at the last
		// underscore, so the published Us_indiana_northamerica_2.obf.zip lands without the "_2".
		Assert.assertEquals("us_indiana_northamerica",
				AlprRegionKey.fromMapFileName("Us_indiana_northamerica.obf"));
	}

	@Test
	public void stripsObfVersionSuffixFromHandCopiedFiles() {
		Assert.assertEquals("us_indiana_northamerica",
				AlprRegionKey.fromMapFileName("Us_indiana_northamerica_2.obf"));
	}

	@Test
	public void handlesRoadOnlyMaps() {
		Assert.assertEquals("us_indiana_northamerica",
				AlprRegionKey.fromMapFileName("Us_indiana_northamerica.road.obf"));
	}

	@Test
	public void ignoresSecondaryMapsCoveringTheSameGround() {
		// These would otherwise produce duplicate entries for a region already listed by its map.
		Assert.assertNull(AlprRegionKey.fromMapFileName("Us_indiana_northamerica.wiki.obf"));
		Assert.assertNull(AlprRegionKey.fromMapFileName("Us_indiana_northamerica.srtm.obf"));
		Assert.assertNull(AlprRegionKey.fromMapFileName("Us_indiana_northamerica.srtmf.obf"));
		Assert.assertNull(AlprRegionKey.fromMapFileName("World_seamarks.depth.obf"));
		Assert.assertNull(AlprRegionKey.fromMapFileName("Wikivoyage.travel.obf"));
	}

	@Test
	public void ignoresNonMapFiles() {
		Assert.assertNull(AlprRegionKey.fromMapFileName("notes.gpx"));
		Assert.assertNull(AlprRegionKey.fromMapFileName(""));
		Assert.assertNull(AlprRegionKey.fromMapFileName(null));
	}

	@Test
	public void doesNotMistakeRegionNamePartsForAVersion() {
		// The trailing part is not digits, so nothing should be stripped.
		Assert.assertEquals("us_indiana_northamerica",
				AlprRegionKey.fromMapFileName("Us_indiana_northamerica.obf"));
		Assert.assertEquals("czech_republic_europe",
				AlprRegionKey.fromMapFileName("Czech_republic_europe.obf"));
	}

	@Test
	public void roundTripsDataFileNames() {
		String key = AlprRegionKey.fromMapFileName("Us_indiana_northamerica.obf");
		String file = AlprRegionKey.toFileName(key);
		Assert.assertEquals("us_indiana_northamerica.deflock", file);
		Assert.assertEquals(key, AlprRegionKey.fromDataFileName(file));
	}

	@Test
	public void rejectsForeignDataFileNames() {
		Assert.assertNull(AlprRegionKey.fromDataFileName("Us_indiana_northamerica.obf"));
		Assert.assertNull(AlprRegionKey.fromDataFileName(null));
	}
}
