package net.osmand.router.deflock;

import net.osmand.IndexConstants;

/**
 * Derives the key that ties a file of offline ALPR camera data to a downloaded OsmAnd map.
 *
 * <p>The key is OsmAnd's own region download name, so
 * {@code Us_indiana_northamerica.obf} pairs with {@code us_indiana_northamerica.deflock} and can
 * be resolved to a {@code WorldRegion} (and therefore a bounding box) through
 * {@code OsmandRegions.getRegionDataByDownloadName}.
 */
public class AlprRegionKey {

	private AlprRegionKey() {
	}

	/**
	 * Converts the name of a downloaded map file into its region key.
	 *
	 * <p>Downloaded maps do not carry the OBF version suffix: the server publishes
	 * {@code Us_indiana_northamerica_2.obf.zip}, but
	 * {@code DownloadActivityType.getBasename} cuts at the last underscore, so the file on disk
	 * is {@code Us_indiana_northamerica.obf}. A suffix is still stripped here, because a file
	 * copied by hand from the download server would otherwise fail to resolve to a region.
	 *
	 * @return the region key, or null when the name is not a map file
	 */
	public static String fromMapFileName(String mapFileName) {
		if (mapFileName == null) {
			return null;
		}
		String name = mapFileName.trim();
		if (name.isEmpty()) {
			return null;
		}
		String lower = name.toLowerCase();
		// Only plain and road maps describe a routable region. Wiki, travel, srtm and depth
		// files cover the same ground as a map that is listed separately, so keying off them
		// too would produce duplicates.
		if (lower.endsWith(IndexConstants.BINARY_ROAD_MAP_INDEX_EXT)) {
			lower = lower.substring(0, lower.length() - IndexConstants.BINARY_ROAD_MAP_INDEX_EXT.length());
		} else if (isSecondaryMap(lower)) {
			return null;
		} else if (lower.endsWith(IndexConstants.BINARY_MAP_INDEX_EXT)) {
			lower = lower.substring(0, lower.length() - IndexConstants.BINARY_MAP_INDEX_EXT.length());
		} else {
			return null;
		}
		return stripVersionSuffix(lower);
	}

	private static boolean isSecondaryMap(String lowerName) {
		return lowerName.endsWith(IndexConstants.BINARY_WIKI_MAP_INDEX_EXT)
				|| lowerName.endsWith(IndexConstants.BINARY_TRAVEL_GUIDE_MAP_INDEX_EXT)
				|| lowerName.endsWith(IndexConstants.BINARY_SRTM_MAP_INDEX_EXT)
				|| lowerName.endsWith(IndexConstants.BINARY_SRTM_FEET_MAP_INDEX_EXT)
				|| lowerName.endsWith(IndexConstants.BINARY_DEPTH_MAP_INDEX_EXT);
	}

	/**
	 * Removes a trailing {@code _<digits>} OBF version marker, if present.
	 */
	static String stripVersionSuffix(String name) {
		int underscore = name.lastIndexOf('_');
		if (underscore <= 0 || underscore == name.length() - 1) {
			return name;
		}
		for (int i = underscore + 1; i < name.length(); i++) {
			if (!Character.isDigit(name.charAt(i))) {
				return name;
			}
		}
		return name.substring(0, underscore);
	}

	/**
	 * @return the file name that holds camera data for the given region key
	 */
	public static String toFileName(String regionKey) {
		return regionKey + IndexConstants.DEFLOCK_FILE_EXT;
	}

	/**
	 * @return the region key a camera data file belongs to, or null if it is not one
	 */
	public static String fromDataFileName(String fileName) {
		if (fileName == null || !fileName.toLowerCase().endsWith(IndexConstants.DEFLOCK_FILE_EXT)) {
			return null;
		}
		return fileName.toLowerCase()
				.substring(0, fileName.length() - IndexConstants.DEFLOCK_FILE_EXT.length());
	}
}
