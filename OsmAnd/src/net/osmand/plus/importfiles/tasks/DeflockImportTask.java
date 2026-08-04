package net.osmand.plus.importfiles.tasks;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import net.osmand.IndexConstants;
import net.osmand.plus.R;
import net.osmand.plus.importfiles.ImportHelper;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.deflock.AlprRegionFile;
import net.osmand.plus.plugins.deflock.AlprRegionManager;
import net.osmand.plus.plugins.deflock.DeFlockPlugin;
import net.osmand.router.deflock.AlprRegionKey;

import java.io.File;

/**
 * Imports offline ALPR camera data for one map region.
 *
 * <p>The file is copied into place, then read back and validated before the coverage index is
 * rebuilt: a corrupt or truncated file should be rejected rather than quietly treated as offline
 * coverage, which would make routes look better informed than they are.
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
		File dest = manager.getRegionFile(regionKey);
		File parent = dest.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		String error = ImportHelper.copyFile(app, dest, uri, false, false);
		if (error != null) {
			return app.getString(R.string.alpr_import_failed, error);
		}
		try {
			AlprRegionFile data = AlprRegionFile.read(dest, regionKey);
			manager.reindex();
			return app.getString(R.string.alpr_region_downloaded, data.size(),
					app.getString(R.string.alpr_imported_cameras));
		} catch (Exception e) {
			// Do not leave an unreadable file behind pretending to be coverage.
			dest.delete();
			return app.getString(R.string.alpr_import_failed,
					e.getMessage() == null ? name : e.getMessage());
		}
	}

	@Override
	protected void onPostExecute(String message) {
		hideProgress();
		notifyImportFinished();
		app.showShortToastMessage(message);
	}

	public static boolean isDeflockFile(@NonNull String fileName) {
		return fileName.endsWith(IndexConstants.DEFLOCK_FILE_EXT);
	}
}
