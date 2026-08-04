package net.osmand.plus.importfiles.tasks;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import net.osmand.plus.R;
import net.osmand.plus.importfiles.ImportHelper;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.deflock.AlprRegionManager;
import net.osmand.plus.plugins.deflock.DeFlockPlugin;
import net.osmand.router.deflock.AlprRegionKey;

import java.io.File;

/**
 * Imports offline ALPR camera data for one map region.
 *
 * <p>The file is staged, then handed to {@link AlprRegionManager} to validate and store, so an
 * import updates the coverage index and refreshes the map by exactly the same path a download
 * does.
 */
public class DeflockImportTask extends BaseImportAsyncTask<Void, Void, String> {

	private final Uri uri;
	private final String name;

	public DeflockImportTask(@NonNull FragmentActivity activity, @NonNull Uri uri, @NonNull String name) {
		super(activity);
		this.uri = uri;
		this.name = name;
	}

	@Override
	protected String doInBackground(Void... voids) {
		DeFlockPlugin plugin = PluginsHelper.getPlugin(DeFlockPlugin.class);
		if (plugin == null) {
			return app.getString(R.string.alpr_import_failed, name);
		}
		String regionKey = AlprRegionKey.fromDataFileName(name);
		if (regionKey == null) {
			return app.getString(R.string.alpr_import_failed, name);
		}
		AlprRegionManager manager = plugin.getCameraRepository().getRegionManager();
		File dir = manager.getRegionsDir();
		dir.mkdirs();
		// Staged under a temporary name so an unreadable file is never left sitting in the region
		// directory, where the coverage index would pick it up as data the routing can trust.
		File staged = new File(dir, regionKey + ".import.tmp");
		String error = ImportHelper.copyFile(app, staged, uri, true, false);
		if (error != null) {
			staged.delete();
			return app.getString(R.string.alpr_import_failed, error);
		}
		try {
			int count = manager.importRegionFileBlocking(staged, regionKey);
			return app.getString(R.string.alpr_imported_cameras, count);
		} catch (Exception e) {
			return app.getString(R.string.alpr_import_failed,
					e.getMessage() == null ? name : e.getMessage());
		} finally {
			staged.delete();
		}
	}

	@Override
	protected void onPostExecute(String message) {
		hideProgress();
		notifyImportFinished();
		app.showShortToastMessage(message);
	}
}
