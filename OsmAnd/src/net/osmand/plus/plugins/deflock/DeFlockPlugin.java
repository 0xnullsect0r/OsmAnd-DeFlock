package net.osmand.plus.plugins.deflock;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.aidlapi.OsmAndCustomizationConstants;
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

import java.util.List;

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
