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
import net.osmand.router.deflock.AlprQueryGrid;
import net.osmand.router.deflock.AlprRegionKey;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
	private volatile boolean indexed;
	// Fired after the set of region files changes, so the map layer can drop its cached query.
	private volatile Runnable changeListener;

	public AlprRegionManager(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void setEndpoint(@NonNull String endpoint) {
		this.endpoint = endpoint;
	}

	public void setChangeListener(@Nullable Runnable changeListener) {
		this.changeListener = changeListener;
	}

	private void notifyChanged() {
		Runnable listener = changeListener;
		if (listener != null) {
			listener.run();
		}
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
	 * Indexes what is on disk if that has not happened yet.
	 *
	 * <p>Indexing is lazy rather than eager because the plugin is constructed from
	 * {@code PluginsHelper.initPlugins} on the main thread during {@code onCreate}, which carries
	 * an explicit sub-second budget. Scanning the region directory there would put file I/O on
	 * the startup path, proportional to how much data the user had downloaded.
	 */
	public void ensureIndexed() {
		if (!indexed) {
			reindex();
		}
	}

	/**
	 * Scans the region directory and rebuilds the coverage index. Reads each file's header only,
	 * never its cameras.
	 */
	public synchronized void reindex() {
		availableRegions.clear();
		loaded.clear();
		coverage.clear();
		indexed = true;

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
			// The file records the bounds it was downloaded for, so indexing does not depend on
			// OsmandRegions being loaded - which it is not, this early in the app's life.
			QuadRect bounds = readDeclaredBounds(file);
			if (bounds == null) {
				bounds = boundsForRegion(key);
			}
			if (bounds != null) {
				availableRegions.put(key, bounds);
				coverage.add(bounds);
			} else {
				log.warn("Ignoring ALPR region file with no usable bounds: " + file.getName());
			}
		}
	}

	/**
	 * Reads the bounds a file declares, without parsing its cameras.
	 */
	@Nullable
	private QuadRect readDeclaredBounds(@NonNull File file) {
		try {
			AlprRegionFile.Header header = AlprRegionFile.readHeader(file);
			return header != null ? header.getBounds() : null;
		} catch (IOException | RuntimeException e) {
			log.warn("Could not read ALPR region header " + file.getName(), e);
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
		ensureIndexed();
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
					AlprRegionFile.Header header = AlprRegionFile.readHeader(file);
					if (header != null) {
						count = header.getCount();
						generated = header.getGenerated();
					}
				} catch (IOException | RuntimeException e) {
					log.warn("Could not read ALPR region header for " + key, e);
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
		ensureIndexed();
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
		ensureIndexed();
		synchronized (this) {
			return coverage.coverageOf(latLonBounds);
		}
	}

	public boolean hasAnyRegionData() {
		ensureIndexed();
		synchronized (this) {
			return !availableRegions.isEmpty();
		}
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
		List<QuadRect> cells = AlprQueryGrid.split(bounds);
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
		notifyChanged();
		return merged.size();
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
				importRegionFileBlocking(source, suggestedKey);
				app.runInUIThread(() -> listener.onFinished(true, null));
			} catch (IOException | RuntimeException e) {
				log.warn("Could not import ALPR region file", e);
				app.runInUIThread(() -> listener.onFinished(false, e.getMessage()));
			}
		});
	}

	/**
	 * Validates a candidate region file and stores it under the region key it declares.
	 *
	 * <p>Reading it fully first is the point: a truncated or malformed file that was simply moved
	 * into place would be indexed as coverage, and routes over that ground would then claim to be
	 * better informed than they are. Callers must already be off the main thread.
	 *
	 * @return how many cameras were imported
	 */
	public int importRegionFileBlocking(@NonNull File source, @NonNull String suggestedKey)
			throws IOException {
		AlprRegionFile data = AlprRegionFile.read(source, suggestedKey);
		String key = data.getRegionKey() != null ? data.getRegionKey() : suggestedKey;
		QuadRect bounds = data.getBounds() != null ? data.getBounds() : boundsForRegion(key);
		new AlprRegionFile(key, data.getGenerated(), data.getSource(), bounds, data.getCameras())
				.write(getRegionFile(key));
		reindex();
		notifyChanged();
		return data.size();
	}

	public synchronized boolean deleteRegion(@NonNull String regionKey) {
		File file = getRegionFile(regionKey);
		boolean deleted = !file.exists() || file.delete();
		if (deleted) {
			reindex();
			notifyChanged();
		}
		return deleted;
	}
}
