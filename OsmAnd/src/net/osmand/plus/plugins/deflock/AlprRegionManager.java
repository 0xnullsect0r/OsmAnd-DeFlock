package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.data.QuadRect;
import net.osmand.map.WorldRegion;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.deflock.AlprCameraPoint;
import net.osmand.router.deflock.AlprCoverageIndex;
import net.osmand.router.deflock.AlprRegionKey;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the offline camera data files, one per downloaded map region.
 *
 * <p>A region file sits in {@code osmand/deflock/} named after the map's download name, so
 * {@code Us_indiana_northamerica.obf} pairs with {@code us_indiana_northamerica.deflock}. That
 * name is OsmAnd's own identity for a region, which is what lets the data be resolved back to a
 * bounding box through {@code OsmandRegions}.
 *
 * <p>Region files never expire. Unlike the browsing cache they are downloaded deliberately, so
 * refreshing them is the user's decision, not a timer's.
 */
public class AlprRegionManager {

	private static final Log log = PlatformUtil.getLog(AlprRegionManager.class);

	/**
	 * Overpass will time out on a whole-country bounding box, so large regions are fetched as a
	 * grid of smaller queries and merged. Roughly two degrees a side keeps individual queries
	 * comfortable without making a small region take many round trips.
	 */
	private static final double MAX_QUERY_SPAN_DEG = 2.0;

	/** Guard against a pathological bounding box turning into thousands of requests. */
	private static final int MAX_QUERY_CELLS = 240;

	/** How many regions' cameras to hold in memory at once. */
	private static final int LOADED_REGION_LIMIT = 4;

	public interface RegionProgressListener {
		void onProgress(int cellsDone, int cellsTotal, int camerasSoFar);

		void onFinished(boolean success, @Nullable String error);
	}

	/** What the app knows about one downloadable-for-cameras region. */
	public static class RegionState {
		private final String regionKey;
		private final WorldRegion region;
		private final File file;
		private final int cameraCount;
		private final long generated;

		RegionState(String regionKey, WorldRegion region, File file, int cameraCount, long generated) {
			this.regionKey = regionKey;
			this.region = region;
			this.file = file;
			this.cameraCount = cameraCount;
			this.generated = generated;
		}

		public String getRegionKey() {
			return regionKey;
		}

		@Nullable
		public WorldRegion getRegion() {
			return region;
		}

		public boolean hasData() {
			return file != null && file.exists();
		}

		@Nullable
		public File getFile() {
			return file;
		}

		public int getCameraCount() {
			return cameraCount;
		}

		public long getGenerated() {
			return generated;
		}

		public long getFileSize() {
			return file != null && file.exists() ? file.length() : 0;
		}
	}

	private final OsmandApplication app;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	// regionKey -> bounds, for coverage answers without loading any cameras.
	private final Map<String, QuadRect> availableRegions = new LinkedHashMap<>();
	// regionKey -> cameras, bounded by LOADED_REGION_LIMIT in access order.
	private final Map<String, List<AlprCameraPoint>> loaded =
			Collections.synchronizedMap(new LinkedHashMap<String, List<AlprCameraPoint>>(8, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<AlprCameraPoint>> eldest) {
					return size() > LOADED_REGION_LIMIT;
				}
			});
	private final AlprCoverageIndex coverage = new AlprCoverageIndex();

	private volatile String endpoint = OverpassAlprClient.DEFAULT_ENDPOINT;

	public AlprRegionManager(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void setEndpoint(@NonNull String endpoint) {
		this.endpoint = endpoint;
	}

	@NonNull
	public File getRegionsDir() {
		return app.getAppPath(IndexConstants.DEFLOCK_INDEX_DIR);
	}

	@NonNull
	public File getRegionFile(@NonNull String regionKey) {
		return new File(getRegionsDir(), AlprRegionKey.toFileName(regionKey));
	}

	// --- indexing ----------------------------------------------------------

	/**
	 * Scans the region directory and rebuilds the coverage index. Cheap: it reads each file's
	 * header, not its cameras.
	 */
	public synchronized void reindex() {
		availableRegions.clear();
		loaded.clear();
		coverage.clear();

		File dir = getRegionsDir();
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			String key = AlprRegionKey.fromDataFileName(file.getName());
			if (key == null) {
				continue;
			}
			QuadRect bounds = boundsForRegion(key);
			if (bounds == null) {
				// No matching region: keep the data usable by falling back to the file's own
				// declared bounds rather than discarding it.
				bounds = readDeclaredBounds(file);
			}
			if (bounds != null) {
				availableRegions.put(key, bounds);
				coverage.add(bounds);
			} else {
				log.warn("Ignoring ALPR region file with no usable bounds: " + file.getName());
			}
		}
	}

	@Nullable
	private QuadRect readDeclaredBounds(@NonNull File file) {
		try {
			return AlprRegionFile.read(file, AlprRegionKey.fromDataFileName(file.getName())).getBounds();
		} catch (IOException | RuntimeException e) {
			log.warn("Could not read ALPR region file " + file.getName(), e);
			return null;
		}
	}

	@Nullable
	public QuadRect boundsForRegion(@NonNull String regionKey) {
		WorldRegion region = app.getRegions().getRegionDataByDownloadName(regionKey);
		return region != null ? region.getBoundingBox() : null;
	}

	/**
	 * Every downloaded map that could carry camera data, with its current state. Driven by
	 * {@code ResourceManager.getIndexFileNames}, so it lists exactly the maps the user has.
	 */
	@NonNull
	public synchronized List<RegionState> getRegionStates() {
		Set<String> keys = new LinkedHashSet<>();
		for (String fileName : app.getResourceManager().getIndexFileNames().keySet()) {
			String key = AlprRegionKey.fromMapFileName(fileName);
			if (key != null) {
				keys.add(key);
			}
		}
		// Region files with no matching map still deserve to be listed, so they can be deleted.
		keys.addAll(availableRegions.keySet());

		List<RegionState> states = new ArrayList<>(keys.size());
		for (String key : keys) {
			File file = getRegionFile(key);
			int count = 0;
			long generated = 0;
			if (file.exists()) {
				try {
					AlprRegionFile data = AlprRegionFile.read(file, key);
					count = data.size();
					generated = data.getGenerated();
				} catch (IOException | RuntimeException e) {
					log.warn("Could not read ALPR region file for " + key, e);
				}
			}
			states.add(new RegionState(key, app.getRegions().getRegionDataByDownloadName(key),
					file.exists() ? file : null, count, generated));
		}
		states.sort(Comparator.comparing(RegionState::getRegionKey));
		return states;
	}

	// --- reading -----------------------------------------------------------

	/**
	 * @return cameras from region files overlapping the bounds; empty when nothing is downloaded
	 */
	@NonNull
	public List<AlprCameraPoint> getCameras(@NonNull QuadRect latLonBounds) {
		double south = Math.min(latLonBounds.top, latLonBounds.bottom);
		double north = Math.max(latLonBounds.top, latLonBounds.bottom);
		double west = Math.min(latLonBounds.left, latLonBounds.right);
		double east = Math.max(latLonBounds.left, latLonBounds.right);

		List<String> keys;
		synchronized (this) {
			keys = new ArrayList<>();
			for (Map.Entry<String, QuadRect> e : availableRegions.entrySet()) {
				if (intersects(e.getValue(), west, north, east, south)) {
					keys.add(e.getKey());
				}
			}
		}
		if (keys.isEmpty()) {
			return new ArrayList<>();
		}
		Map<Long, AlprCameraPoint> merged = new LinkedHashMap<>();
		for (String key : keys) {
			for (AlprCameraPoint camera : camerasOf(key)) {
				if (camera.getLatitude() >= south && camera.getLatitude() <= north
						&& camera.getLongitude() >= west && camera.getLongitude() <= east) {
					// Regions overlap at borders, so dedupe by OSM id.
					merged.put(camera.getOsmId(), camera);
				}
			}
		}
		return new ArrayList<>(merged.values());
	}

	@NonNull
	private List<AlprCameraPoint> camerasOf(@NonNull String regionKey) {
		List<AlprCameraPoint> cached = loaded.get(regionKey);
		if (cached != null) {
			return cached;
		}
		File file = getRegionFile(regionKey);
		List<AlprCameraPoint> cameras;
		try {
			cameras = AlprRegionFile.read(file, regionKey).getCameras();
		} catch (IOException | RuntimeException e) {
			log.warn("Could not load ALPR cameras for " + regionKey, e);
			cameras = new ArrayList<>();
		}
		loaded.put(regionKey, cameras);
		return cameras;
	}

	@NonNull
	public AlprCoverageIndex.Coverage getCoverage(@NonNull QuadRect latLonBounds) {
		synchronized (this) {
			return coverage.coverageOf(latLonBounds);
		}
	}

	public synchronized boolean hasAnyRegionData() {
		return !availableRegions.isEmpty();
	}

	private static boolean intersects(QuadRect r, double west, double north, double east, double south) {
		double rw = Math.min(r.left, r.right);
		double re = Math.max(r.left, r.right);
		double rs = Math.min(r.top, r.bottom);
		double rn = Math.max(r.top, r.bottom);
		return rw <= east && re >= west && rs <= north && rn >= south;
	}

	// --- downloading -------------------------------------------------------

	public void downloadRegion(@NonNull String regionKey, @NonNull RegionProgressListener listener) {
		executor.submit(() -> {
			try {
				int count = downloadRegionBlocking(regionKey, listener);
				app.runInUIThread(() -> listener.onFinished(true, null));
				log.info("Downloaded " + count + " ALPR cameras for " + regionKey);
			} catch (IOException | RuntimeException e) {
				log.warn("Could not download ALPR cameras for " + regionKey, e);
				app.runInUIThread(() -> listener.onFinished(false, e.getMessage()));
			}
		});
	}

	private int downloadRegionBlocking(@NonNull String regionKey,
	                                   @NonNull RegionProgressListener listener) throws IOException {
		QuadRect bounds = boundsForRegion(regionKey);
		if (bounds == null) {
			throw new IOException("No region bounds known for " + regionKey);
		}
		List<QuadRect> cells = splitForQuery(bounds);
		OverpassAlprClient client = new OverpassAlprClient(endpoint);
		Map<Long, AlprCameraPoint> merged = new LinkedHashMap<>();
		for (int i = 0; i < cells.size(); i++) {
			for (AlprCameraPoint camera : client.fetch(cells.get(i))) {
				merged.put(camera.getOsmId(), camera);
			}
			int done = i + 1;
			int total = cells.size();
			int soFar = merged.size();
			app.runInUIThread(() -> listener.onProgress(done, total, soFar));
		}
		AlprRegionFile file = new AlprRegionFile(regionKey, System.currentTimeMillis(), endpoint,
				bounds, new ArrayList<>(merged.values()));
		file.write(getRegionFile(regionKey));
		reindex();
		return merged.size();
	}

	/**
	 * Splits a region's bounding box into query-sized cells. A single Overpass query over a
	 * country times out, and a failure part-way through must not leave a half-written file — so
	 * cells are merged in memory and only written once all of them succeed.
	 */
	@NonNull
	static List<QuadRect> splitForQuery(@NonNull QuadRect bounds) {
		double west = Math.min(bounds.left, bounds.right);
		double east = Math.max(bounds.left, bounds.right);
		double south = Math.min(bounds.top, bounds.bottom);
		double north = Math.max(bounds.top, bounds.bottom);

		int cols = (int) Math.max(1, Math.ceil((east - west) / MAX_QUERY_SPAN_DEG));
		int rows = (int) Math.max(1, Math.ceil((north - south) / MAX_QUERY_SPAN_DEG));
		while ((long) cols * rows > MAX_QUERY_CELLS) {
			cols = Math.max(1, cols / 2);
			rows = Math.max(1, rows / 2);
		}
		List<QuadRect> cells = new ArrayList<>(cols * rows);
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

	// --- import / export / delete ------------------------------------------

	/**
	 * Copies an imported file into the region directory, validating it first so a bad file is
	 * rejected rather than stored.
	 */
	public void importRegionFile(@NonNull File source, @NonNull String suggestedKey,
	                             @NonNull RegionProgressListener listener) {
		executor.submit(() -> {
			try {
				AlprRegionFile data = AlprRegionFile.read(source, suggestedKey);
				String key = data.getRegionKey() != null ? data.getRegionKey() : suggestedKey;
				QuadRect bounds = data.getBounds() != null ? data.getBounds() : boundsForRegion(key);
				new AlprRegionFile(key, data.getGenerated(), data.getSource(), bounds,
						data.getCameras()).write(getRegionFile(key));
				reindex();
				app.runInUIThread(() -> listener.onFinished(true, null));
			} catch (IOException | RuntimeException e) {
				log.warn("Could not import ALPR region file", e);
				app.runInUIThread(() -> listener.onFinished(false, e.getMessage()));
			}
		});
	}

	public synchronized boolean deleteRegion(@NonNull String regionKey) {
		File file = getRegionFile(regionKey);
		boolean deleted = !file.exists() || file.delete();
		if (deleted) {
			reindex();
		}
		return deleted;
	}
}
