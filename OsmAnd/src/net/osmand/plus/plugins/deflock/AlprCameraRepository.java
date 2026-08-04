package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.QuadRect;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.deflock.AlprCameraPoint;
import net.osmand.router.deflock.AlprCoverageIndex;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps ALPR cameras available to the rest of the app: serves them out of memory, backs them with
 * the on-disk tile cache, and downloads missing tiles from Overpass in the background.
 *
 * <p>Cameras are bucketed by download tile rather than held in a spatial tree. Tiles are ~40 km
 * across, so any realistic query - a map viewport or a routing corridor - touches only a handful
 * of buckets, and bucket lookup keeps eviction and refresh trivial.
 */
public class AlprCameraRepository {

	private static final Log log = PlatformUtil.getLog(AlprCameraRepository.class);

	/**
	 * Largest number of tiles we will download for one request. A zoomed-out map or a very long
	 * route would otherwise ask Overpass for a continent.
	 */
	public static final int MAX_TILES_PER_REQUEST = 16;

	/** Minimum gap between retries after the endpoint asked us to back off. */
	private static final long BACKOFF_BASE_MS = 30_000;
	private static final long BACKOFF_MAX_MS = 15 * 60_000;

	public interface CamerasLoadedListener {
		void onCamerasLoaded();
	}

	private final OsmandApplication app;
	private final AlprCameraDbHelper dbHelper;
	// Deliberately downloaded, per map region, never expires. Authoritative when present.
	private final AlprRegionManager regionManager;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	// tileKey -> cameras. Populated from the database, then from downloads.
	private final Map<Long, List<AlprCameraPoint>> loadedTiles = new ConcurrentHashMap<>();
	private final Set<Long> tilesInFlight = Collections.synchronizedSet(new LinkedHashSet<>());
	private final List<CamerasLoadedListener> listeners = new CopyOnWriteArrayList<>();

	private volatile long backoffUntil;
	private volatile long backoffDelay = BACKOFF_BASE_MS;
	private volatile String endpoint = OverpassAlprClient.DEFAULT_ENDPOINT;

	public AlprCameraRepository(@NonNull OsmandApplication app) {
		this.app = app;
		this.dbHelper = new AlprCameraDbHelper(app);
		this.regionManager = new AlprRegionManager(app);
		// Deliberately not indexed here: this constructor runs from PluginsHelper.initPlugins on
		// the main thread during onCreate, which has a sub-second budget. The manager indexes
		// lazily on first use instead. Region changes refresh whatever is drawing cameras.
		this.regionManager.setChangeListener(this::notifyListeners);
	}

	@NonNull
	public AlprRegionManager getRegionManager() {
		return regionManager;
	}

	/**
	 * @return how much of the area is covered by deliberately downloaded region data
	 */
	@NonNull
	public AlprCoverageIndex.Coverage getOfflineCoverage(@NonNull QuadRect latLonBounds) {
		return regionManager.getCoverage(latLonBounds);
	}

	public void addListener(@NonNull CamerasLoadedListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeListener(@NonNull CamerasLoadedListener listener) {
		listeners.remove(listener);
	}

	public void setEndpoint(@NonNull String endpoint) {
		this.endpoint = endpoint;
		// Region downloads go through their own client, so a configured mirror has to reach it
		// too - otherwise the setting silently applies to map browsing only.
		regionManager.setEndpoint(endpoint);
	}

	/**
	 * Returns the cameras available for the given bounds.
	 *
	 * <p>Resolution order is region files, then the browsing tile cache, then - only when
	 * explicitly allowed - a background fetch. Region files are authoritative: where they cover
	 * the ground, nothing is fetched and nothing expires, which is what makes the feature usable
	 * with no network at all.
	 *
	 * @param allowDownload false to stay strictly offline (used while routing, where a network
	 *                      stall would block the calculation)
	 */
	@NonNull
	public List<AlprCameraPoint> getCameras(@NonNull QuadRect latLonBounds, boolean allowDownload) {
		AlprCoverageIndex.Coverage coverage = regionManager.getCoverage(latLonBounds);
		List<AlprCameraPoint> fromRegions = regionManager.getCameras(latLonBounds);
		if (coverage == AlprCoverageIndex.Coverage.FULL) {
			// Downloaded region data covers all of this ground; the tile cache can add nothing
			// and there is nothing to fetch.
			return fromRegions;
		}

		Map<Long, AlprCameraPoint> merged = new LinkedHashMap<>();
		for (AlprCameraPoint camera : fromRegions) {
			merged.put(camera.getOsmId(), camera);
		}

		List<int[]> tiles = tilesFor(latLonBounds);
		List<int[]> missing = new ArrayList<>();
		for (int[] tile : tiles) {
			long key = tileKey(tile[0], tile[1]);
			List<AlprCameraPoint> cached = loadedTiles.get(key);
			if (cached == null) {
				cached = loadTileFromDb(tile[0], tile[1]);
			}
			if (cached != null) {
				for (AlprCameraPoint camera : cached) {
					if (contains(latLonBounds, camera)) {
						merged.put(camera.getOsmId(), camera);
					}
				}
			}
			if (cached == null || !dbHelper.isTileFresh(tile[0], tile[1])) {
				missing.add(tile);
			}
		}
		if (allowDownload && !missing.isEmpty() && missing.size() <= MAX_TILES_PER_REQUEST) {
			scheduleDownload(missing);
		}
		return new ArrayList<>(merged.values());
	}

	@Nullable
	private List<AlprCameraPoint> loadTileFromDb(int tileX, int tileY) {
		if (!dbHelper.isTileFresh(tileX, tileY)) {
			// An expired tile is still better than nothing while the refresh is in flight, but we
			// only keep it if it was actually downloaded at some point.
			List<AlprCameraPoint> stale = dbHelper.getTileCameras(tileX, tileY);
			if (stale.isEmpty()) {
				return null;
			}
			loadedTiles.put(tileKey(tileX, tileY), stale);
			return stale;
		}
		List<AlprCameraPoint> cameras = dbHelper.getTileCameras(tileX, tileY);
		loadedTiles.put(tileKey(tileX, tileY), cameras);
		return cameras;
	}

	private void scheduleDownload(@NonNull List<int[]> tiles) {
		if (System.currentTimeMillis() < backoffUntil || !app.getSettings().isInternetConnectionAvailable()) {
			return;
		}
		for (int[] tile : tiles) {
			long key = tileKey(tile[0], tile[1]);
			if (!tilesInFlight.add(key)) {
				continue;
			}
			executor.submit(() -> {
				try {
					if (downloadTile(tile[0], tile[1])) {
						notifyListeners();
					}
				} finally {
					tilesInFlight.remove(key);
				}
			});
		}
	}

	/**
	 * @return true when the tile was downloaded and cached
	 */
	private boolean downloadTile(int tileX, int tileY) {
		if (System.currentTimeMillis() < backoffUntil) {
			return false;
		}
		QuadRect bounds = tileBounds(tileX, tileY);
		try {
			List<AlprCameraPoint> cameras = new OverpassAlprClient(endpoint).fetch(bounds);
			dbHelper.saveTile(tileX, tileY, cameras);
			loadedTiles.put(tileKey(tileX, tileY), cameras);
			backoffDelay = BACKOFF_BASE_MS;
			log.info("Downloaded " + cameras.size() + " ALPR cameras for tile " + tileX + "/" + tileY);
			return true;
		} catch (OverpassAlprClient.OverpassBusyException e) {
			// Do not cache an empty tile just because the server is loaded.
			backoffUntil = System.currentTimeMillis() + backoffDelay;
			backoffDelay = Math.min(BACKOFF_MAX_MS, backoffDelay * 2);
			log.info("Overpass busy, backing off: " + e.getMessage());
			return false;
		} catch (IOException | RuntimeException e) {
			log.warn("Could not download ALPR tile " + tileX + "/" + tileY, e);
			return false;
		}
	}

	private void notifyListeners() {
		app.runInUIThread(() -> {
			for (CamerasLoadedListener listener : listeners) {
				listener.onCamerasLoaded();
			}
		});
	}

	public void clearCache() {
		loadedTiles.clear();
		dbHelper.clearCache();
		backoffUntil = 0;
		backoffDelay = BACKOFF_BASE_MS;
	}

	public int getCachedCameraCount() {
		return dbHelper.getCachedCameraCount();
	}

	// --- tile helpers ------------------------------------------------------

	private static boolean contains(@NonNull QuadRect latLonBounds, @NonNull AlprCameraPoint camera) {
		double south = Math.min(latLonBounds.top, latLonBounds.bottom);
		double north = Math.max(latLonBounds.top, latLonBounds.bottom);
		double west = Math.min(latLonBounds.left, latLonBounds.right);
		double east = Math.max(latLonBounds.left, latLonBounds.right);
		return camera.getLatitude() >= south && camera.getLatitude() <= north
				&& camera.getLongitude() >= west && camera.getLongitude() <= east;
	}

	@NonNull
	static List<int[]> tilesFor(@NonNull QuadRect latLonBounds) {
		int zoom = AlprCameraDbHelper.TILE_ZOOM;
		double south = Math.min(latLonBounds.top, latLonBounds.bottom);
		double north = Math.max(latLonBounds.top, latLonBounds.bottom);
		double west = Math.min(latLonBounds.left, latLonBounds.right);
		double east = Math.max(latLonBounds.left, latLonBounds.right);
		// Tile Y grows southwards, so the northern edge gives the smaller index.
		int minX = (int) Math.floor(MapUtils.getTileNumberX(zoom, west));
		int maxX = (int) Math.floor(MapUtils.getTileNumberX(zoom, east));
		int minY = (int) Math.floor(MapUtils.getTileNumberY(zoom, north));
		int maxY = (int) Math.floor(MapUtils.getTileNumberY(zoom, south));
		List<int[]> tiles = new ArrayList<>();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				tiles.add(new int[] {x, y});
			}
		}
		return tiles;
	}

	@NonNull
	static QuadRect tileBounds(int tileX, int tileY) {
		int zoom = AlprCameraDbHelper.TILE_ZOOM;
		double west = MapUtils.getLongitudeFromTile(zoom, tileX);
		double east = MapUtils.getLongitudeFromTile(zoom, tileX + 1);
		double north = MapUtils.getLatitudeFromTile(zoom, tileY);
		double south = MapUtils.getLatitudeFromTile(zoom, tileY + 1);
		return new QuadRect(west, north, east, south);
	}

	static long tileKey(int tileX, int tileY) {
		return ((long) tileX << 32) | (tileY & 0xffffffffL);
	}
}
