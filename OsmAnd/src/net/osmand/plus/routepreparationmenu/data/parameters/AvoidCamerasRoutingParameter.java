package net.osmand.plus.routepreparationmenu.data.parameters;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.NAVIGATION_AVOID_ALPR_CAMERAS_ID;

import net.osmand.plus.R;

/**
 * "Avoid ALPR camera view" in the route options sheet.
 *
 * <p>Like {@link AvoidRoadsRoutingParameter} this is not backed by a routing.xml parameter: it
 * lives in the DeFlock plugin's own preferences, which keeps the feature self-contained in the app
 * rather than depending on the separate OsmAnd-resources repository.
 */
public class AvoidCamerasRoutingParameter extends LocalRoutingParameter {

	public static final String KEY = NAVIGATION_AVOID_ALPR_CAMERAS_ID;

	public AvoidCamerasRoutingParameter() {
		super(null);
	}

	public String getKey() {
		return KEY;
	}

	@Override
	public int getActiveIconId() {
		return R.drawable.ic_alpr_camera;
	}

	@Override
	public int getDisabledIconId() {
		return R.drawable.ic_alpr_camera;
	}
}
