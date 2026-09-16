package net.osmand.plus.plugins.deflock;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.aidlapi.OsmAndCustomizationConstants;
import net.osmand.map.WorldRegion;
import net.osmand.plus.download.IndexItem;
import net.osmand.router.deflock.AlprRegionKey;
import net.osmand.util.Algorithms;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.render.RenderingRuleProperty;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter;
import net.osmand.plus.widgets.ctxmenu.callback.ItemClickListener;
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem;
import net.osmand.router.deflock.CameraCoverage;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shows automated license plate reader (ALPR) cameras from the DeFlock project on the map, and
 * lets navigation route around what those cameras can see.
 *
 * <p>The cameras come from OpenStreetMap ({@code man_made=surveillance} +
 * {@code surveillance:type=ALPR}) but are downloaded from Overpass rather than read from offline
 * maps, because OsmAnd's map builder deliberately keeps surveillance nodes out of the OBF POI
 * index.
 */
public class DeFlockPlugin extends OsmandPlugin {

	public static final String DEFLOCK_ID = "osmand.deflock";

	public final CommonPreference<Boolean> SHOW_ALPR_CAMERAS;
	public final CommonPreference<Float> ALPR_VIEW_RANGE_M;
	public final CommonPreference<Float> ALPR_VIEW_CONE_DEG;
	public final CommonPreference<Float> ALPR_AVOIDANCE_MARGIN_M;
	public final CommonPreference<Boolean> AVOID_ALPR_CAMERAS;
	public final CommonPreference<Integer> ALPR_DETOUR_BUDGET_MIN;
	public final CommonPreference<String> OVERPASS_ENDPOINT;
	/** Fetch a region's cameras automatically when its offline map is downloaded. */
	public final CommonPreference<Boolean> AUTO_DOWNLOAD_WITH_MAPS;

	private final AlprCameraRepository cameraRepository;
	private AlprCameraLayer cameraLayer;
	// What avoidance did to the most recently calculated route, for the route details UI.
	private volatile AlprAvoidanceHelper.Outcome lastAvoidanceOutcome;

	public DeFlockPlugin(@NonNull OsmandApplication app) {
		super(app);

		SHOW_ALPR_CAMERAS = registerBooleanPreference("show_alpr_cameras", true).makeProfile();
		ALPR_VIEW_RANGE_M = registerFloatPreference("alpr_view_range_m",
				(float) CameraCoverage.DEFAULT_RANGE_M).makeProfile().cache();
		ALPR_VIEW_CONE_DEG = registerFloatPreference("alpr_view_cone_deg",
				(float) CameraCoverage.DEFAULT_CONE_DEG).makeProfile().cache();
		ALPR_AVOIDANCE_MARGIN_M = registerFloatPreference("alpr_avoidance_margin_m",
				(float) CameraCoverage.DEFAULT_AVOIDANCE_MARGIN_M).makeProfile().cache();
		AVOID_ALPR_CAMERAS = registerBooleanPreference("avoid_alpr_cameras", false).makeProfile().cache();
		ALPR_DETOUR_BUDGET_MIN = registerIntPreference("alpr_detour_budget_min",
				DEFAULT_DETOUR_BUDGET_MIN).makeProfile().cache();
		OVERPASS_ENDPOINT = registerStringPreference("alpr_overpass_endpoint",
				OverpassAlprClient.DEFAULT_ENDPOINT).makeGlobal().makeShared();
		AUTO_DOWNLOAD_WITH_MAPS = registerBooleanPreference("alpr_auto_download_with_maps", true)
				.makeGlobal().makeShared();

		cameraRepository = new AlprCameraRepository(app);
		cameraRepository.setEndpoint(OVERPASS_ENDPOINT.get());
	}

	/** Ten minutes: enough to route around a cluster of cameras, not enough to double a commute. */
	public static final int DEFAULT_DETOUR_BUDGET_MIN = 10;

	/** Largest detour the slider offers. */
	public static final int MAX_DETOUR_BUDGET_MIN = 60;

	@Override
	public String getId() {
		return DEFLOCK_ID;
	}

	@Override
	public String getName() {
		return app.getString(R.string.deflock_plugin_name);
	}

	@Override
	public CharSequence getDescription(boolean linksEnabled) {
		return app.getString(R.string.deflock_plugin_description);
	}

	@Override
	public int getLogoResourceId() {
		return R.drawable.ic_alpr_camera;
	}

	@Override
	public boolean isEnableByDefault() {
		return false;
	}

	@Nullable
	@Override
	public SettingsScreenType getSettingsScreenType() {
		return SettingsScreenType.DEFLOCK_SETTINGS;
	}

	@NonNull
	public AlprCameraRepository getCameraRepository() {
		return cameraRepository;
	}

	@Nullable
	public AlprCameraLayer getCameraLayer() {
		return cameraLayer;
	}

	/**
	 * @return true when camera avoidance should be applied to routes for the given profile
	 */
	public boolean isAvoidanceEnabled(@NonNull ApplicationMode mode) {
		return isActive() && AVOID_ALPR_CAMERAS.getModeValue(mode);
	}

	/**
	 * @return the extra travel time the user is willing to accept, in seconds
	 */
	public int getDetourBudgetSeconds(@NonNull ApplicationMode mode) {
		return ALPR_DETOUR_BUDGET_MIN.getModeValue(mode) * 60;
	}

	public void setLastAvoidanceOutcome(@Nullable AlprAvoidanceHelper.Outcome outcome) {
		this.lastAvoidanceOutcome = outcome;
	}

	@Nullable
	public AlprAvoidanceHelper.Outcome getLastAvoidanceOutcome() {
		return lastAvoidanceOutcome;
	}

	// Regions whose camera download this plugin started itself, so only those are reported back.
	private final Set<String> autoStartedRegions = Collections.synchronizedSet(new HashSet<>());
	private AlprRegionManager.StatusListener autoDownloadListener;

	/**
	 * Pulls a region's ALPR cameras down as soon as its offline map finishes downloading.
	 *
	 * <p>Camera data is fetched cell by cell from Overpass and takes minutes for a large region,
	 * which is intolerable to sit and watch. Piggybacking on the map download means the data is
	 * simply there when the map is, and the work happens on the region manager's own executor.
	 */
	@Override
	public void onIndexItemDownloaded(@NonNull IndexItem item, boolean updatingFile) {
		if (!isActive() || !AUTO_DOWNLOAD_WITH_MAPS.get()) {
			return;
		}
		// Null for anything that is not a routable map - srtm, wiki, travel, depth, voice prompts -
		// because those cover ground that a separately listed map already accounts for.
		String regionKey = AlprRegionKey.fromMapFileName(item.getTargetFileName());
		if (regionKey == null) {
			return;
		}
		AlprRegionManager manager = cameraRepository.getRegionManager();
		// Updating a map should not silently redo a download that already succeeded. Refreshing
		// existing camera data stays a deliberate action, offered in the plugin's own screen.
		if (updatingFile && manager.getRegionFile(regionKey).exists()) {
			return;
		}
		ensureAutoDownloadReporting(manager);
		// Announce once per batch: downloading a continent queues many maps, and one toast per
		// region would bury the screen.
		if (autoStartedRegions.isEmpty()) {
			app.showToastMessage(app.getString(R.string.alpr_auto_download_started, regionName(regionKey)));
		}
		autoStartedRegions.add(regionKey);
		manager.downloadRegion(regionKey);
	}

	/**
	 * Reports the outcome of downloads this plugin started. Without this the work would be wholly
	 * invisible, which is the opposite failure to making the user watch a progress bar.
	 */
	private void ensureAutoDownloadReporting(@NonNull AlprRegionManager manager) {
		if (autoDownloadListener != null) {
			return;
		}
		autoDownloadListener = (regionKey, status) -> {
			if (status.isRunning() || !autoStartedRegions.remove(regionKey)) {
				return;
			}
			switch (status.getState()) {
				case DONE -> app.showToastMessage(app.getString(R.string.alpr_auto_download_done,
						regionName(regionKey), status.getCameras()));
				case FAILED -> app.showToastMessage(app.getString(R.string.alpr_auto_download_failed,
						regionName(regionKey)));
				default -> {
					// EMPTY or CANCELLED: nothing a driver needs interrupting for.
				}
			}
		};
		manager.addStatusListener(autoDownloadListener);
	}

	@NonNull
	private String regionName(@NonNull String regionKey) {
		WorldRegion region = app.getRegions().getRegionDataByDownloadName(regionKey);
		if (region != null && !Algorithms.isEmpty(region.getLocaleName())) {
			return region.getLocaleName();
		}
		return regionKey;
	}

	@Override
	public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		OsmandMapTileView mapView = app.getOsmandMap().getMapView();
		if (cameraLayer != null) {
			mapView.removeLayer(cameraLayer);
		}
		cameraLayer = new AlprCameraLayer(context, this);
		// Just under the POI layer, so cameras sit above the map but below tappable POIs.
		mapView.addLayer(cameraLayer, 3.4f);
	}

	@Override
	public void updateLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		OsmandMapTileView mapView = app.getOsmandMap().getMapView();
		if (isActive() && SHOW_ALPR_CAMERAS.get()) {
			if (cameraLayer == null) {
				registerLayers(context, mapActivity);
			} else if (!mapView.getLayers().contains(cameraLayer)) {
				mapView.addLayer(cameraLayer, 3.4f);
			}
			mapView.refreshMap();
		} else if (cameraLayer != null) {
			mapView.removeLayer(cameraLayer);
			mapView.refreshMap();
		}
	}

	@Override
	protected void registerLayerContextMenuActions(@NonNull ContextMenuAdapter adapter,
	                                               @NonNull MapActivity mapActivity,
	                                               @NonNull List<RenderingRuleProperty> customRules) {
		if (!isEnabled()) {
			return;
		}
		ItemClickListener listener = (uiAdapter, view, item, isChecked) -> {
			if (item.getTitleId() == R.string.layer_alpr_cameras) {
				SHOW_ALPR_CAMERAS.set(!SHOW_ALPR_CAMERAS.get());
				item.setSelected(SHOW_ALPR_CAMERAS.get());
				item.setColor(app, SHOW_ALPR_CAMERAS.get()
						? R.color.osmand_orange : ContextMenuItem.INVALID_ID);
				if (uiAdapter != null) {
					uiAdapter.onDataSetChanged();
				}
				updateLayers(mapActivity, mapActivity);
			}
			return true;
		};
		adapter.addItem(new ContextMenuItem(OsmAndCustomizationConstants.ALPR_CAMERAS_LAYER_ID)
				.setTitleId(R.string.layer_alpr_cameras, app)
				.setSelected(SHOW_ALPR_CAMERAS.get())
				.setIcon(R.drawable.ic_alpr_camera)
				.setColor(mapActivity, SHOW_ALPR_CAMERAS.get()
						? R.color.osmand_orange : ContextMenuItem.INVALID_ID)
				.setItemDeleteAction(SHOW_ALPR_CAMERAS)
				.setListener(listener));
	}
}
