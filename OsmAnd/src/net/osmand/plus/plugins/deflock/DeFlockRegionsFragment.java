package net.osmand.plus.plugins.deflock;

import android.content.Context;
import android.os.Bundle;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import net.osmand.map.WorldRegion;
import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.deflock.AlprRegionManager.RegionProgressListener;
import net.osmand.plus.plugins.deflock.AlprRegionManager.RegionState;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.util.Algorithms;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Lists the downloaded map regions and what ALPR camera data exists for each, so offline data is
 * acquired deliberately rather than accumulating by accident as the map is panned.
 *
 * <p>Deliberately not built on OsmAnd's download manager: {@code DownloadActivityType} items come
 * from the index published by download.osmand.net, whereas camera data is fetched from Overpass,
 * so reusing that machinery would mean fighting it.
 */
public class DeFlockRegionsFragment extends BaseSettingsFragment {

	private static final String REGION_PREF_PREFIX = "alpr_region_";

	private DeFlockPlugin plugin;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		plugin = PluginsHelper.getPlugin(DeFlockPlugin.class);
		super.onCreate(savedInstanceState);
	}

	@Override
	protected void setupPreferences() {
		PreferenceScreen screen = getPreferenceScreen();
		if (plugin == null || screen == null) {
			return;
		}
		AlprRegionManager manager = plugin.getCameraRepository().getRegionManager();
		manager.reindex();
		List<RegionState> states = manager.getRegionStates();

		if (states.isEmpty()) {
			Preference empty = new Preference(requireContext());
			empty.setKey("alpr_no_regions");
			empty.setTitle(R.string.alpr_no_regions);
			empty.setSelectable(false);
			empty.setLayoutResource(R.layout.preference_with_descr);
			screen.addPreference(empty);
			return;
		}
		for (RegionState state : states) {
			screen.addPreference(createRegionPreference(state));
		}
	}

	@NonNull
	private Preference createRegionPreference(@NonNull RegionState state) {
		Preference preference = new Preference(requireContext());
		preference.setKey(REGION_PREF_PREFIX + state.getRegionKey());
		preference.setTitle(getRegionTitle(state));
		preference.setSummary(getRegionSummary(state));
		preference.setLayoutResource(R.layout.preference_with_descr);
		preference.setIcon(state.hasData()
				? getActiveIcon(R.drawable.ic_alpr_camera)
				: getContentIcon(R.drawable.ic_alpr_camera));
		return preference;
	}

	@NonNull
	private String getRegionTitle(@NonNull RegionState state) {
		WorldRegion region = state.getRegion();
		if (region != null && !Algorithms.isEmpty(region.getLocaleName())) {
			return region.getLocaleName();
		}
		// Fall back to the raw key, so a data file with no matching map is still identifiable
		// and therefore deletable.
		return state.getRegionKey();
	}

	@NonNull
	private String getRegionSummary(@NonNull RegionState state) {
		if (!state.hasData()) {
			return getString(R.string.alpr_region_no_data);
		}
		DateFormat format = android.text.format.DateFormat.getMediumDateFormat(requireContext());
		return getString(R.string.alpr_region_downloaded, state.getCameraCount(),
				format.format(new Date(state.getGenerated())));
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		String key = preference.getKey();
		if (key != null && key.startsWith(REGION_PREF_PREFIX) && plugin != null) {
			showRegionActions(key.substring(REGION_PREF_PREFIX.length()));
			return true;
		}
		return super.onPreferenceClick(preference);
	}

	private void showRegionActions(@NonNull String regionKey) {
		AlprRegionManager manager = plugin.getCameraRepository().getRegionManager();
		boolean hasData = manager.getRegionFile(regionKey).exists();

		Context themed = new ContextThemeWrapper(requireContext(),
				isNightMode() ? R.style.OsmandDarkTheme : R.style.OsmandLightTheme);
		AlertDialog.Builder builder = new AlertDialog.Builder(themed);
		builder.setTitle(regionKey);
		if (hasData) {
			builder.setItems(new CharSequence[] {
					getString(R.string.alpr_update_cameras),
					getString(R.string.shared_string_delete)
			}, (dialog, which) -> {
				if (which == 0) {
					downloadRegion(regionKey);
				} else {
					deleteRegion(regionKey);
				}
			});
		} else {
			builder.setItems(new CharSequence[] {getString(R.string.alpr_download_cameras)},
					(dialog, which) -> downloadRegion(regionKey));
		}
		builder.setNegativeButton(R.string.shared_string_cancel, null);
		builder.show();
	}

	private void downloadRegion(@NonNull String regionKey) {
		if (plugin == null) {
			return;
		}
		plugin.getCameraRepository().getRegionManager().downloadRegion(regionKey,
				new RegionProgressListener() {
					@Override
					public void onProgress(int cellsDone, int cellsTotal, int camerasSoFar) {
						Preference preference = findPreference(REGION_PREF_PREFIX + regionKey);
						if (preference != null) {
							preference.setSummary(getString(R.string.alpr_downloading_cameras,
									cellsDone, cellsTotal, camerasSoFar));
						}
					}

					@Override
					public void onFinished(boolean success, @Nullable String error) {
						if (!success) {
							app.showToastMessage(getString(R.string.alpr_download_failed,
									error == null ? "" : error));
						}
						updateAllSettings();
					}
				});
	}

	private void deleteRegion(@NonNull String regionKey) {
		if (plugin != null) {
			plugin.getCameraRepository().getRegionManager().deleteRegion(regionKey);
			updateAllSettings();
		}
	}
}
