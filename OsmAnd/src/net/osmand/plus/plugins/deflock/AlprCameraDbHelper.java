package net.osmand.plus.plugins.deflock;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.router.deflock.AlprCameraPoint;

import org.apache.commons.logging.Log;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * On-disk cache of ALPR cameras, keyed by download tile so the app works offline once an area has
 * been fetched once.
 *
 * <p>Tiles are stored at {@link #TILE_ZOOM} (roughly 40 km across), which keeps the number of
 * Overpass requests low while staying small enough that a single request is quick.
 */
public class AlprCameraDbHelper extends SQLiteOpenHelper {

	private static final Log log = PlatformUtil.getLog(AlprCameraDbHelper.class);

	public static final String DB_NAME = "deflock_cameras";

	/**
	 * 2: forces a refetch of everything cached by builds that asked Overpass for {@code out tags}.
	 * Those builds cached every tile as empty and fresh for 30 days, so without this bump the map
	 * would stay blank for a month after the fix.
	 */
	private static final int DB_VERSION = 2;

	/** Zoom level at which download tiles are cut. */
	public static final int TILE_ZOOM = 10;

	/** How long a downloaded tile stays fresh. Cameras are added steadily but rarely move. */
	public static final long TILE_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000;

	private static final String CAMERAS_TABLE = "alpr_cameras";
	private static final String COL_OSM_ID = "osm_id";
	private static final String COL_LAT = "lat";
	private static final String COL_LON = "lon";
	private static final String COL_DIRECTION = "direction";
	private static final String COL_TAGS = "tags";
	private static final String COL_TILE_X = "tile_x";
	private static final String COL_TILE_Y = "tile_y";

	private static final String TILES_TABLE = "alpr_tiles";
	private static final String COL_FETCHED_AT = "fetched_at";

	private static final String CAMERAS_TABLE_CREATE = "CREATE TABLE " + CAMERAS_TABLE + " ("
			+ COL_OSM_ID + " bigint PRIMARY KEY, "
			+ COL_LAT + " double, "
			+ COL_LON + " double, "
			+ COL_DIRECTION + " double, "
			+ COL_TAGS + " TEXT, "
			+ COL_TILE_X + " int, "
			+ COL_TILE_Y + " int);";

	private static final String CAMERAS_TILE_INDEX_CREATE =
			"CREATE INDEX alpr_cameras_tile_idx ON " + CAMERAS_TABLE
					+ " (" + COL_TILE_X + ", " + COL_TILE_Y + ");";

	private static final String TILES_TABLE_CREATE = "CREATE TABLE " + TILES_TABLE + " ("
			+ COL_TILE_X + " int, "
			+ COL_TILE_Y + " int, "
			+ COL_FETCHED_AT + " long, "
			+ "PRIMARY KEY (" + COL_TILE_X + ", " + COL_TILE_Y + "));";

	public AlprCameraDbHelper(@NonNull Context context) {
		super(context, DB_NAME, null, DB_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(CAMERAS_TABLE_CREATE);
		db.execSQL(CAMERAS_TILE_INDEX_CREATE);
		db.execSQL(TILES_TABLE_CREATE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// Nothing to migrate yet: the cache is disposable, so on any future schema change the
		// simplest correct thing is to drop and refetch.
		db.execSQL("DROP TABLE IF EXISTS " + CAMERAS_TABLE);
		db.execSQL("DROP TABLE IF EXISTS " + TILES_TABLE);
		onCreate(db);
	}

	/**
	 * @return true when the tile has been downloaded and has not expired
	 */
	public boolean isTileFresh(int tileX, int tileY) {
		try (Cursor cursor = getReadableDatabase().query(TILES_TABLE, new String[] {COL_FETCHED_AT},
				COL_TILE_X + " = ? AND " + COL_TILE_Y + " = ?",
				new String[] {String.valueOf(tileX), String.valueOf(tileY)}, null, null, null)) {
			if (cursor.moveToFirst()) {
				long fetchedAt = cursor.getLong(0);
				return System.currentTimeMillis() - fetchedAt < TILE_EXPIRY_MS;
			}
		} catch (RuntimeException e) {
			log.error("Could not read ALPR tile state", e);
		}
		return false;
	}

	/**
	 * Replaces the contents of one tile with a freshly downloaded set of cameras.
	 */
	public void saveTile(int tileX, int tileY, @NonNull List<AlprCameraPoint> cameras) {
		SQLiteDatabase db = getWritableDatabase();
		try {
			db.beginTransaction();
			db.delete(CAMERAS_TABLE, COL_TILE_X + " = ? AND " + COL_TILE_Y + " = ?",
					new String[] {String.valueOf(tileX), String.valueOf(tileY)});
			for (AlprCameraPoint camera : cameras) {
				ContentValues values = new ContentValues();
				values.put(COL_OSM_ID, camera.getOsmId());
				values.put(COL_LAT, camera.getLatitude());
				values.put(COL_LON, camera.getLongitude());
				if (camera.hasDirection()) {
					values.put(COL_DIRECTION, camera.getDirection());
				} else {
					values.putNull(COL_DIRECTION);
				}
				values.put(COL_TAGS, encodeTags(camera.getTags()));
				values.put(COL_TILE_X, tileX);
				values.put(COL_TILE_Y, tileY);
				// A camera on a tile boundary can be returned for two tiles; keep the newest copy.
				db.insertWithOnConflict(CAMERAS_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
			}
			ContentValues tile = new ContentValues();
			tile.put(COL_TILE_X, tileX);
			tile.put(COL_TILE_Y, tileY);
			tile.put(COL_FETCHED_AT, System.currentTimeMillis());
			db.insertWithOnConflict(TILES_TABLE, null, tile, SQLiteDatabase.CONFLICT_REPLACE);
			db.setTransactionSuccessful();
		} catch (RuntimeException e) {
			log.error("Could not save ALPR tile " + tileX + "/" + tileY, e);
		} finally {
			try {
				db.endTransaction();
			} catch (RuntimeException e) {
				log.error("Could not close ALPR tile transaction", e);
			}
		}
	}

	@NonNull
	public List<AlprCameraPoint> getTileCameras(int tileX, int tileY) {
		List<AlprCameraPoint> cameras = new ArrayList<>();
		try (Cursor cursor = getReadableDatabase().query(CAMERAS_TABLE,
				new String[] {COL_OSM_ID, COL_LAT, COL_LON, COL_DIRECTION, COL_TAGS},
				COL_TILE_X + " = ? AND " + COL_TILE_Y + " = ?",
				new String[] {String.valueOf(tileX), String.valueOf(tileY)}, null, null, null)) {
			while (cursor.moveToNext()) {
				Float direction = cursor.isNull(3) ? null : (float) cursor.getDouble(3);
				cameras.add(new AlprCameraPoint(cursor.getLong(0), cursor.getDouble(1),
						cursor.getDouble(2), direction, decodeTags(cursor.getString(4))));
			}
		} catch (RuntimeException e) {
			log.error("Could not read ALPR tile " + tileX + "/" + tileY, e);
		}
		return cameras;
	}

	public void clearCache() {
		try {
			SQLiteDatabase db = getWritableDatabase();
			db.delete(CAMERAS_TABLE, null, null);
			db.delete(TILES_TABLE, null, null);
		} catch (RuntimeException e) {
			log.error("Could not clear ALPR cache", e);
		}
	}

	public int getCachedCameraCount() {
		try (Cursor cursor = getReadableDatabase().rawQuery(
				"SELECT COUNT(*) FROM " + CAMERAS_TABLE, null)) {
			if (cursor.moveToFirst()) {
				return cursor.getInt(0);
			}
		} catch (RuntimeException e) {
			log.error("Could not count cached ALPR cameras", e);
		}
		return 0;
	}

	@Nullable
	private static String encodeTags(@Nullable Map<String, String> tags) {
		if (tags == null || tags.isEmpty()) {
			return null;
		}
		try {
			return new JSONObject(tags).toString();
		} catch (RuntimeException e) {
			log.warn("Could not encode ALPR tags", e);
			return null;
		}
	}

	@NonNull
	private static Map<String, String> decodeTags(@Nullable String encoded) {
		Map<String, String> tags = new HashMap<>();
		if (encoded == null || encoded.isEmpty()) {
			return tags;
		}
		try {
			JSONObject json = new JSONObject(encoded);
			for (Iterator<String> it = json.keys(); it.hasNext(); ) {
				String key = it.next();
				tags.put(key, json.optString(key));
			}
		} catch (Exception e) {
			log.warn("Could not decode ALPR tags", e);
		}
		return tags;
	}
}
