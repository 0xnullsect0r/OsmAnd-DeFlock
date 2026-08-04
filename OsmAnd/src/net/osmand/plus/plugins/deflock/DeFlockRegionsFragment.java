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
import net.osmand.plus.plugins.deflock.AlprRegionManager.DownloadStatus;
import net.osmand.plus.plugins.deflock.AlprRegionManager.RegionState;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.util.Algorithms;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Lists the downloaded map regions and what ALPR camera data exists for each, so offline data is
 * acquired deliberately rather than accumulating by accident as the map is panned.
 *
 * <p>Deliberately not built on OsmAnd's download manager: {@code DownloadActivityType} items come
 * from the index published by download.osmand.net, whereas camera data is fetched from Overpass,
 * so reusing that machinery would mean fighting it.
 *
 * <p>Region state is read on a background thread. Every entry means gunzipping and parsing a file
 * header, so doing it inline would stutter the screen in proportion to how much the user had
 * downloaded - and a download in flight is observed through {@link AlprRegionManager}, not held
 * in this fragment, so it survives the screen being recreated.
 */
public class DeFlockRegionsFragment extends BaseSettingsFragment
		implements AlprRegionManager.StatusListener {

	private static final String REGION_PREF_PREFIX = "alpr_region_";
	private static final String LOADING_PREF_ID = "alpr_regions_loading";

	private DeFlockPlugin plugin;
	private List<RegionState> states;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		plugin = PluginsHelper.getPlugin(DeFlockPlugin.class);
		super.onCreate(savedInstanceState);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (plugin != null) {
			plugin.getCameraRepository().getRegionManager().addStatusListener(this);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (plugin != null) {
			plugin.getCameraRepository().getRegionManager().removeStatusListener(this);
		}
	}

	@Override
	protected void setupPreferences() {
		PreferenceScreen screen = getPreferenceScreen();
		if (plugin == null || screen == null) {
			return;
		}
		if (states == null) {
			showLoading(screen);
			loadStatesAsync();
			return;
		}
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

	private void showLoading(@NonNull PreferenceScreen screen) {
		Preference loading = new Preference(requireContext());
		loading.setKey(LOADING_PREF_ID);
		loading.setTitle(R.string.shared_string_loading);
		loading.setSelectable(false);
		loading.setLayoutResource(R.layout.preference_with_descr);
		screen.addPreference(loading);
	}

	/**
	 * Reads every region's header off the main thread. This is file I/O proportional to how many
	 * regions exist, which is exactly the sort of thing that has no business on the UI thread.
	 */
	private void loadStatesAsync() {
		AlprRegionManager manager = plugin.getCameraRepository().getRegionManager();
		new Thread(() -> {
			List<RegionState> loaded = new ArrayList<>(manager.getRegionStates());
			app.runInUIThread(() -> {
				if (isAdded()) {
					states = loaded;
					updateAllSettings();
				}
			});
		}, "alpr-region-states").start();
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
		DownloadStatus status = plugin.getCameraRepository().getRegionManager()
				.getStatus(state.getRegionKey());
		if (status != null && status.isRunning()) {
			return status.getCellsTotal() == 0
					? getString(R.string.alpr_download_starting)
					: getString(R.string.alpr_downloading_cameras, status.getCellsDone(),
							status.getCellsTotal(), status.getCameras());
		}
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
		DownloadStatus status = manager.getStatus(regionKey);

		Context themed = new ContextThemeWrapper(requireContext(),
				isNightMode() ? R.style.OsmandDarkTheme : R.style.OsmandLightTheme);
		AlertDialog.Builder builder = new AlertDialog.Builder(themed);
		builder.setTitle(regionKey);

		if (status != null && status.isRunning()) {
			builder.setItems(new CharSequence[] {getString(R.string.shared_string_cancel_download)},
					(dialog, which) -> manager.cancelDownload(regionKey));
		} else if (manager.getRegionFile(regionKey).exists()) {
			builder.setItems(new CharSequence[] {
					getString(R.string.alpr_update_cameras),
					getString(R.string.shared_string_delete)
			}, (dialog, which) -> {
				if (which == 0) {
					manager.downloadRegion(regionKey);
				} else {
					deleteRegion(regionKey);
				}
			});
		} else {
			builder.setItems(new CharSequence[] {getString(R.string.alpr_download_cameras)},
					(dialog, which) -> manager.downloadRegion(regionKey));
		}
		builder.setNegativeButton(R.string.shared_string_cancel, null);
		builder.show();
	}

	@Override
	public void onRegionStatusChanged(@NonNull String regionKey, @NonNull DownloadStatus status) {
		if (!isAdded()) {
			return;
		}
		if (status.isRunning()) {
			// Cheap path: only the one row's summary changes, so do not rebuild the screen.
			Preference preference = findPreference(REGION_PREF_PREFIX + regionKey);
			if (preference != null) {
				preference.setSummary(status.getCellsTotal() == 0
						? getString(R.string.alpr_download_starting)
						: getString(R.string.alpr_downloading_cameras, status.getCellsDone(),
								status.getCellsTotal(), status.getCameras()));
			}
			return;
		}
		announce(status);
		plugin.getCameraRepository().getRegionManager().consumeStatus(regionKey);
		// The set of files changed, so the cached state is stale.
		states = null;
		updateAllSettings();
	}

	/**
	 * Says what happened. A finished download used to change a summary line and nothing else, so
	 * there was no way to tell success from a download that had quietly done nothing.
	 */
	private void announce(@NonNull DownloadStatus status) {
		switch (status.getState()) {
			case DONE:
				app.showToastMessage(getString(R.string.alpr_download_complete, status.getCameras()));
				break;
			case EMPTY:
				app.showToastMessage(getString(R.string.alpr_no_cameras_in_region));
				break;
			case FAILED:
				app.showToastMessage(getString(R.string.alpr_download_failed,
						status.getError() == null ? "" : status.getError()));
				break;
			case CANCELLED:
			default:
				break;
		}
	}

	private void deleteRegion(@NonNull String regionKey) {
		if (plugin != null) {
			plugin.getCameraRepository().getRegionManager().deleteRegion(regionKey);
			states = null;
			updateAllSettings();
		}
	}
}
