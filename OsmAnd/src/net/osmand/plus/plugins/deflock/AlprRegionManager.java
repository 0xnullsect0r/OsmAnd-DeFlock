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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

	/** Attempts per query cell before a download is given up as failed. */
	private static final int MAX_CELL_ATTEMPTS = 4;

	private static final long RETRY_BASE_MS = 5_000;
	private static final long RETRY_MAX_MS = 60_000;

	/** Pause between cells, to stay a well-behaved client of a free shared service. */
	private static final long BETWEEN_CELLS_MS = 1_000;

	/** Notified whenever any region's download status changes. */
	public interface StatusListener {
		void onRegionStatusChanged(@NonNull String regionKey, @NonNull DownloadStatus status);
	}

	/**
	 * Where a region's download has got to.
	 *
	 * <p>Held by the manager rather than by whichever screen started it, so a download survives
	 * the screen being recreated.
	 */
	public static class DownloadStatus {

		public enum State {RUNNING, DONE, EMPTY, FAILED, CANCELLED}

		private final State state;
		private final int cellsDone;
		private final int cellsTotal;
		private final int cameras;
		private final String error;

		private DownloadStatus(State state, int cellsDone, int cellsTotal, int cameras, String error) {
			this.state = state;
			this.cellsDone = cellsDone;
			this.cellsTotal = cellsTotal;
			this.cameras = cameras;
			this.error = error;
		}

		static DownloadStatus starting() {
			return new DownloadStatus(State.RUNNING, 0, 0, 0, null);
		}

		static DownloadStatus running(int done, int total, int cameras) {
			return new DownloadStatus(State.RUNNING, done, total, cameras, null);
		}

		static DownloadStatus done(int cameras) {
			return new DownloadStatus(State.DONE, 0, 0, cameras, null);
		}

		static DownloadStatus empty() {
			return new DownloadStatus(State.EMPTY, 0, 0, 0, null);
		}

		static DownloadStatus failed(@Nullable String error) {
			return new DownloadStatus(State.FAILED, 0, 0, 0, error);
		}

		static DownloadStatus cancelled() {
			return new DownloadStatus(State.CANCELLED, 0, 0, 0, null);
		}

		@NonNull
		public State getState() {
			return state;
		}

		public boolean isRunning() {
			return state == State.RUNNING;
		}

		public int getCellsDone() {
			return cellsDone;
		}

		public int getCellsTotal() {
			return cellsTotal;
		}

		public int getCameras() {
			return cameras;
		}

		@Nullable
		public String getError() {
			return error;
		}
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
	private volatile String fallbackEndpoint = OverpassAlprClient.FALLBACK_ENDPOINT;
	private volatile boolean indexed;
	// Fired after the set of region files changes, so the map layer can drop its cached query.
	private volatile Runnable changeListener;

	// regionKey -> where its download has got to, kept here so it outlives any screen.
	private final Map<String, DownloadStatus> statuses = new ConcurrentHashMap<>();
	private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
	private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();

	public AlprRegionManager(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void setEndpoint(@NonNull String endpoint) {
		this.endpoint = endpoint;
		// Only the stock endpoint falls back to the public mirror. Someone who has deliberately
		// pointed the plugin at a particular server - their own, or one they trust - did not ask
		// for their search areas to be sent anywhere else, and a busy server is a poor excuse.
		this.fallbackEndpoint = OverpassAlprClient.DEFAULT_ENDPOINT.equals(endpoint)
				? OverpassAlprClient.FALLBACK_ENDPOINT : endpoint;
	}

	public void addStatusListener(@NonNull StatusListener listener) {
		statusListeners.add(listener);
	}

	public void removeStatusListener(@NonNull StatusListener listener) {
		statusListeners.remove(listener);
	}

	@Nullable
	public DownloadStatus getStatus(@NonNull String regionKey) {
		return statuses.get(regionKey);
	}

	/** Clears a finished status once a screen has shown it, so it is not reported twice. */
	public void consumeStatus(@NonNull String regionKey) {
		DownloadStatus status = statuses.get(regionKey);
		if (status != null && !status.isRunning()) {
			statuses.remove(regionKey);
		}
	}

	private void setStatus(@NonNull String regionKey, @NonNull DownloadStatus status) {
		statuses.put(regionKey, status);
		app.runInUIThread(() -> {
			for (StatusListener listener : statusListeners) {
				listener.onRegionStatusChanged(regionKey, status);
			}
		});
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
			AlprRegionFile.Header header = readHeader(file);
			// An empty region file can only mislead: it would claim FULL coverage for ground the
			// router then reports as camera-free with complete confidence. Builds that asked
			// Overpass for the wrong output mode wrote exactly these, so ignore them and let the
			// region be downloaded again.
			if (header != null && header.getCount() == 0) {
				log.warn("Ignoring empty ALPR region file: " + file.getName());
				continue;
			}
			QuadRect bounds = header != null ? header.getBounds() : null;
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
	 * Reads what a file declares about itself, without parsing its cameras.
	 */
	@Nullable
	private AlprRegionFile.Header readHeader(@NonNull File file) {
		try {
			return AlprRegionFile.readHeader(file);
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

	/**
	 * Starts a download, unless one is already running for this region.
	 *
	 * <p>Progress is not reported through a callback owned by a screen: a region download outlives
	 * the fragment that started it, so it is held here and observed through
	 * {@link #addStatusListener}. That way rotating the device or walking away and coming back
	 * shows the download still running rather than losing it.
	 */
	public void downloadRegion(@NonNull String regionKey) {
		DownloadStatus current = statuses.get(regionKey);
		if (current != null && current.isRunning()) {
			return;
		}
		cancelled.remove(regionKey);
		setStatus(regionKey, DownloadStatus.starting());
		executor.submit(() -> {
			try {
				int count = downloadRegionBlocking(regionKey);
				if (cancelled.remove(regionKey)) {
					setStatus(regionKey, DownloadStatus.cancelled());
				} else if (count == 0) {
					// Not a failure, and emphatically not coverage: nothing was written, so the
					// region still reads as "no data" rather than as ground known to be clear.
					setStatus(regionKey, DownloadStatus.empty());
				} else {
					log.info("Downloaded " + count + " ALPR cameras for " + regionKey);
					setStatus(regionKey, DownloadStatus.done(count));
				}
			} catch (CancelledException e) {
				setStatus(regionKey, DownloadStatus.cancelled());
			} catch (IOException | RuntimeException e) {
				log.warn("Could not download ALPR cameras for " + regionKey, e);
				setStatus(regionKey, DownloadStatus.failed(e.getMessage()));
			} finally {
				cancelled.remove(regionKey);
			}
		});
	}

	/** Asks a running download to stop at the next cell boundary. */
	public void cancelDownload(@NonNull String regionKey) {
		DownloadStatus current = statuses.get(regionKey);
		if (current != null && current.isRunning()) {
			cancelled.add(regionKey);
		}
	}

	private int downloadRegionBlocking(@NonNull String regionKey) throws IOException {
		QuadRect bounds = boundsForRegion(regionKey);
		if (bounds == null) {
			throw new IOException("No region bounds known for " + regionKey);
		}
		List<QuadRect> cells = AlprQueryGrid.split(bounds);
		Map<Long, AlprCameraPoint> merged = new LinkedHashMap<>();
		for (int i = 0; i < cells.size(); i++) {
			if (cancelled.contains(regionKey)) {
				throw new CancelledException();
			}
			if (i > 0) {
				// Overpass is a shared free service; do not machine-gun it.
				pause(regionKey, BETWEEN_CELLS_MS);
			}
			for (AlprCameraPoint camera : fetchCellWithRetries(regionKey, cells.get(i), i, cells.size())) {
				merged.put(camera.getOsmId(), camera);
			}
			setStatus(regionKey, DownloadStatus.running(i + 1, cells.size(), merged.size()));
		}
		if (merged.isEmpty()) {
			// Writing this would claim FULL coverage over ground nothing is known about, and the
			// route option would then report "no cameras in view" with total confidence.
			return 0;
		}
		AlprRegionFile file = new AlprRegionFile(regionKey, System.currentTimeMillis(), endpoint,
				bounds, new ArrayList<>(merged.values()));
		file.write(getRegionFile(regionKey));
		reindex();
		notifyChanged();
		return merged.size();
	}

	/**
	 * Fetches one cell, retrying a busy server and falling back to the mirror.
	 *
	 * <p>Overpass refuses a large share of requests at busy times, and a region is many cells, so
	 * without this a long download almost always dies part way through. Every cell must succeed:
	 * a region file assembled from some of its cells would claim coverage it does not have.
	 */
	@NonNull
	private List<AlprCameraPoint> fetchCellWithRetries(@NonNull String regionKey,
	                                                   @NonNull QuadRect cell, int index, int total)
			throws IOException {
		long backoff = RETRY_BASE_MS;
		IOException last = null;
		for (int attempt = 0; attempt < MAX_CELL_ATTEMPTS; attempt++) {
			// Alternate to the mirror once the primary has refused, rather than waiting it out.
			String target = (attempt > 0 && attempt % 2 == 1 && !endpoint.equals(fallbackEndpoint))
					? fallbackEndpoint : endpoint;
			try {
				return new OverpassAlprClient(target).fetch(cell);
			} catch (OverpassAlprClient.OverpassBusyException e) {
				last = e;
				log.info("Overpass busy on area " + (index + 1) + "/" + total + " via " + target
						+ ", attempt " + (attempt + 1) + ": " + e.getMessage());
			} catch (IOException e) {
				last = e;
				log.warn("Area " + (index + 1) + "/" + total + " failed via " + target
						+ ", attempt " + (attempt + 1), e);
			}
			if (attempt < MAX_CELL_ATTEMPTS - 1) {
				pause(regionKey, backoff);
				backoff = Math.min(RETRY_MAX_MS, backoff * 2);
			}
		}
		throw new IOException("Area " + (index + 1) + " of " + total + " could not be downloaded: "
				+ (last == null ? "unknown error" : last.getMessage()), last);
	}

	/**
	 * Waits, but keeps watching for a cancel. A backoff can be a minute long, and a Cancel button
	 * that does nothing for a minute is not a Cancel button.
	 */
	private void pause(@NonNull String regionKey, long ms) throws CancelledException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			if (cancelled.contains(regionKey)) {
				throw new CancelledException();
			}
			try {
				Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CancelledException();
			}
		}
	}

	/** Raised when the user stops a download; not an error worth reporting as one. */
	private static class CancelledException extends IOException {
	}

	// --- import / export / delete ------------------------------------------

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
