package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.utils.AndroidUtils;

public class DeFlockSettingsFragment extends BaseSettingsFragment {

	private static final String CLEAR_CACHE_PREF_ID = "alpr_clear_cache";

	private final DeFlockPlugin plugin = PluginsHelper.requirePlugin(DeFlockPlugin.class);

	@Override
	protected void setupPreferences() {
		setupShowCameras();
		setupViewRange();
		setupViewCone();
		setupAvoidCameras();
		setupDetourBudget();
		setupClearCache();
	}

	private void setupShowCameras() {
		SwitchPreferenceCompat preference = findPreference(plugin.SHOW_ALPR_CAMERAS.getId());
		if (preference != null) {
			preference.setIcon(getPersistentPrefIcon(R.drawable.ic_alpr_camera));
		}
	}

	private void setupViewRange() {
		ListPreferenceEx preference = findPreference(plugin.ALPR_VIEW_RANGE_M.getId());
		if (preference == null) {
			return;
		}
		Float[] values = {30f, 45f, 60f, 90f, 120f, 200f};
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = Math.round(values[i]) + " " + getString(R.string.m);
		}
		preference.setEntries(names);
		preference.setEntryValues(values);
		preference.setDescription(R.string.alpr_view_range_description);
	}

	private void setupViewCone() {
		ListPreferenceEx preference = findPreference(plugin.ALPR_VIEW_CONE_DEG.getId());
		if (preference == null) {
			return;
		}
		Float[] values = {30f, 45f, 60f, 90f, 120f, 360f};
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = values[i] >= 360
					? getString(R.string.alpr_camera_omnidirectional)
					: Math.round(values[i]) + "°";
		}
		preference.setEntries(names);
		preference.setEntryValues(values);
		preference.setDescription(R.string.alpr_view_cone_description);
	}

	private void setupAvoidCameras() {
		SwitchPreferenceCompat preference = findPreference(plugin.AVOID_ALPR_CAMERAS.getId());
		if (preference != null) {
			preference.setIcon(getPersistentPrefIcon(R.drawable.ic_action_road_works_dark));
		}
	}

	private void setupDetourBudget() {
		ListPreferenceEx preference = findPreference(plugin.ALPR_DETOUR_BUDGET_MIN.getId());
		if (preference == null) {
			return;
		}
		Integer[] values = {0, 2, 5, 10, 15, 20, 30, 45, DeFlockPlugin.MAX_DETOUR_BUDGET_MIN};
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = getString(R.string.alpr_detour_budget_value,
					formatMinutes(values[i]));
		}
		preference.setEntries(names);
		preference.setEntryValues(values);
		preference.setDescription(R.string.alpr_detour_budget_description);
	}

	@NonNull
	private String formatMinutes(int minutes) {
		return minutes + " " + getString(R.string.int_min);
	}

	private void setupClearCache() {
		Preference preference = findPreference(CLEAR_CACHE_PREF_ID);
		if (preference != null) {
			preference.setIcon(getActiveIcon(R.drawable.ic_action_delete_dark));
			int cached = plugin.getCameraRepository().getCachedCameraCount();
			preference.setSummary(getString(R.string.alpr_cached_cameras, cached));
		}
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		if (CLEAR_CACHE_PREF_ID.equals(preference.getKey())) {
			plugin.getCameraRepository().clearCache();
			setupClearCache();
			if (getContext() != null) {
				AndroidUtils.getApp(getContext()).showShortToastMessage(R.string.shared_string_done);
			}
			return true;
		}
		return super.onPreferenceClick(preference);
	}
}
