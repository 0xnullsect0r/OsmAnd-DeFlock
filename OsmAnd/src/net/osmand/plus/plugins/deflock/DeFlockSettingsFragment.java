package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.utils.AndroidUtils;

public class DeFlockSettingsFragment extends BaseSettingsFragment {

	private static final String CLEAR_CACHE_PREF_ID = "alpr_clear_cache";
	private static final String OFFLINE_DATA_PREF_ID = "alpr_offline_data";

	private final DeFlockPlugin plugin = PluginsHelper.requirePlugin(DeFlockPlugin.class);

	@Override
	protected void setupPreferences() {
		setupShowCameras();
		setupViewRange();
		setupViewCone();
		setupAvoidCameras();
		setupDetourBudget();
		setupOfflineData();
		setupClearCache();
		loadSummariesAsync();
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

	private void setupOfflineData() {
		Preference preference = findPreference(OFFLINE_DATA_PREF_ID);
		if (preference != null) {
			preference.setIcon(getActiveIcon(R.drawable.ic_action_map_download));
			preference.setSummary(R.string.alpr_offline_data_description);
		}
	}

	private void setupClearCache() {
		Preference preference = findPreference(CLEAR_CACHE_PREF_ID);
		if (preference != null) {
			preference.setIcon(getActiveIcon(R.drawable.ic_action_delete_dark));
		}
	}

	/**
	 * Fills in the two summaries that cost real work to produce: counting downloaded regions means
	 * reading a header out of every region file, and the cached camera count is a database query.
	 * Neither belongs on the thread drawing the screen, so both start as placeholders.
	 */
	private void loadSummariesAsync() {
		AlprRegionManager manager = plugin.getCameraRepository().getRegionManager();
		new Thread(() -> {
			int downloaded = 0;
			int total = 0;
			for (AlprRegionManager.RegionState state : manager.getRegionStates()) {
				total++;
				if (state.hasData()) {
					downloaded++;
				}
			}
			int cached = plugin.getCameraRepository().getCachedCameraCount();

			int finalDownloaded = downloaded;
			int finalTotal = total;
			app.runInUIThread(() -> {
				if (!isAdded()) {
					return;
				}
				Preference offline = findPreference(OFFLINE_DATA_PREF_ID);
				if (offline != null && finalTotal > 0) {
					offline.setSummary(getString(R.string.alpr_offline_data_summary,
							finalDownloaded, finalTotal));
				}
				Preference clear = findPreference(CLEAR_CACHE_PREF_ID);
				if (clear != null) {
					clear.setSummary(getString(R.string.alpr_cached_cameras, cached));
				}
			});
		}, "alpr-settings-summaries").start();
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		if (OFFLINE_DATA_PREF_ID.equals(preference.getKey())) {
			BaseSettingsFragment.showInstance(requireActivity(), SettingsScreenType.DEFLOCK_REGIONS);
			return true;
		}
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
