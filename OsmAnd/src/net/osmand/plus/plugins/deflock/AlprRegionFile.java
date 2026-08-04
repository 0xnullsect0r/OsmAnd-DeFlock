package net.osmand.plus.plugins.deflock;

import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.QuadRect;
import net.osmand.router.deflock.AlprCameraPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes the offline camera data file for one map region.
 *
 * <p>The native format is gzipped JSON with a header, so a file can be validated and versioned
 * rather than guessed at. Cameras are written sorted by OSM id, which makes a region's file
 * byte-identical between runs and so makes "has anything changed?" a meaningful question.
 *
 * <p>Reading also accepts a plain GeoJSON {@code FeatureCollection}, which costs almost nothing
 * because {@link AlprCameraPoint#fromTags} already does the tag conversion, and lets an export
 * from DeFlock or Overpass be imported directly.
 */
public class AlprRegionFile {

	/** Bumped only on an incompatible change; readers reject anything higher. */
	public static final int FORMAT_VERSION = 1;

	private static final String KEY_FORMAT = "format";
	private static final String KEY_REGION = "region";
	private static final String KEY_GENERATED = "generated";
	private static final String KEY_SOURCE = "source";
	private static final String KEY_BOUNDS = "bounds";
	private static final String KEY_COUNT = "count";
	private static final String KEY_CAMERAS = "cameras";

	private final String regionKey;
	private final long generated;
	private final String source;
	private final QuadRect bounds;
	private final List<AlprCameraPoint> cameras;

	public AlprRegionFile(@NonNull String regionKey, long generated, @Nullable String source,
	                      @Nullable QuadRect bounds, @NonNull List<AlprCameraPoint> cameras) {
		this.regionKey = regionKey;
		this.generated = generated;
		this.source = source;
		this.bounds = bounds;
		this.cameras = cameras;
	}

	public String getRegionKey() {
		return regionKey;
	}

	public long getGenerated() {
		return generated;
	}

	@Nullable
	public String getSource() {
		return source;
	}

	@Nullable
	public QuadRect getBounds() {
		return bounds;
	}

	@NonNull
	public List<AlprCameraPoint> getCameras() {
		return cameras;
	}

	public int size() {
		return cameras.size();
	}

	// --- writing -----------------------------------------------------------

	/**
	 * Writes the file atomically: a partly written file after a crash or a killed download would
	 * otherwise look like valid offline data.
	 */
	public void write(@NonNull File target) throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IOException("Could not create " + parent);
		}
		File temp = new File(target.getAbsolutePath() + ".tmp");
		try (OutputStream out = new GZIPOutputStream(
				new BufferedOutputStream(new FileOutputStream(temp)))) {
			out.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
		}
		if (target.exists() && !target.delete()) {
			throw new IOException("Could not replace " + target);
		}
		if (!temp.renameTo(target)) {
			throw new IOException("Could not move " + temp + " to " + target);
		}
	}

	@NonNull
	JSONObject toJson() {
		List<AlprCameraPoint> sorted = new ArrayList<>(cameras);
		sorted.sort(Comparator.comparingLong(AlprCameraPoint::getOsmId));

		JSONObject root = new JSONObject();
		try {
			root.put(KEY_FORMAT, FORMAT_VERSION);
			root.put(KEY_REGION, regionKey);
			root.put(KEY_GENERATED, generated);
			if (source != null) {
				root.put(KEY_SOURCE, source);
			}
			if (bounds != null) {
				JSONObject b = new JSONObject();
				b.put("l", bounds.left);
				b.put("t", bounds.top);
				b.put("r", bounds.right);
				b.put("b", bounds.bottom);
				root.put(KEY_BOUNDS, b);
			}
			root.put(KEY_COUNT, sorted.size());

			JSONArray array = new JSONArray();
			for (AlprCameraPoint camera : sorted) {
				JSONObject c = new JSONObject();
				c.put("id", camera.getOsmId());
				c.put("lat", camera.getLatitude());
				c.put("lon", camera.getLongitude());
				if (camera.hasDirection()) {
					c.put("dir", camera.getDirection());
				}
				if (!camera.getTags().isEmpty()) {
					c.put("tags", new JSONObject(camera.getTags()));
				}
				array.put(c);
			}
			root.put(KEY_CAMERAS, array);
		} catch (Exception e) {
			throw new IllegalStateException("Could not build region JSON", e);
		}
		return root;
	}

	// --- reading -----------------------------------------------------------

	/**
	 * Just the header of a region file: enough to know what ground it covers and how much is in
	 * it, without materialising thousands of cameras.
	 */
	public static class Header {
		private final String regionKey;
		private final long generated;
		private final QuadRect bounds;
		private final int count;

		Header(String regionKey, long generated, QuadRect bounds, int count) {
			this.regionKey = regionKey;
			this.generated = generated;
			this.bounds = bounds;
			this.count = count;
		}

		public String getRegionKey() {
			return regionKey;
		}

		public long getGenerated() {
			return generated;
		}

		@Nullable
		public QuadRect getBounds() {
			return bounds;
		}

		public int getCount() {
			return count;
		}
	}

	/**
	 * Reads only the header, stopping before the camera array.
	 *
	 * <p>This exists so that indexing what is on disk stays cheap: the alternative parsed every
	 * camera of every region just to recover a bounding box. Fields are written before
	 * {@code cameras}, so a streaming reader can stop as soon as it reaches it.
	 *
	 * @return the header, or null if this is not a native region file (a GeoJSON import has none)
	 */
	@Nullable
	public static Header readHeader(@NonNull File file) throws IOException {
		try (JsonReader reader = new JsonReader(
				new InputStreamReader(openMaybeGzipped(file), StandardCharsets.UTF_8))) {
			String regionKey = null;
			long generated = 0;
			QuadRect bounds = null;
			int count = -1;
			boolean nativeFormat = false;

			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				if (KEY_CAMERAS.equals(name)) {
					// Everything needed comes before this; stop rather than parse the payload.
					nativeFormat = true;
					break;
				}
				switch (name) {
					case KEY_FORMAT:
						nativeFormat |= reader.nextInt() > 0;
						break;
					case KEY_REGION:
						regionKey = reader.nextString();
						break;
					case KEY_GENERATED:
						generated = reader.nextLong();
						break;
					case KEY_COUNT:
						count = reader.nextInt();
						break;
					case KEY_BOUNDS:
						bounds = readBoundsStreaming(reader);
						break;
					default:
						reader.skipValue();
						break;
				}
			}
			return nativeFormat ? new Header(regionKey, generated, bounds, Math.max(count, 0)) : null;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Could not read ALPR region header", e);
		}
	}

	@Nullable
	private static QuadRect readBoundsStreaming(@NonNull JsonReader reader) throws IOException {
		double l = 0, t = 0, r = 0, b = 0;
		boolean any = false;
		reader.beginObject();
		while (reader.hasNext()) {
			String key = reader.nextName();
			double value = reader.nextDouble();
			any = true;
			switch (key) {
				case "l": l = value; break;
				case "t": t = value; break;
				case "r": r = value; break;
				case "b": b = value; break;
				default: break;
			}
		}
		reader.endObject();
		return any ? new QuadRect(l, t, r, b) : null;
	}

	/**
	 * @param fallbackRegionKey used when the file carries no region of its own, as a GeoJSON
	 *                          import will not
	 */
	@NonNull
	public static AlprRegionFile read(@NonNull File file, @NonNull String fallbackRegionKey)
			throws IOException {
		return parse(readAllMaybeGzipped(file), fallbackRegionKey);
	}

	@NonNull
	public static AlprRegionFile parse(@NonNull String json, @NonNull String fallbackRegionKey)
			throws IOException {
		try {
			JSONObject root = new JSONObject(json);
			if (root.has(KEY_CAMERAS)) {
				return parseNative(root, fallbackRegionKey);
			}
			if ("FeatureCollection".equals(root.optString("type"))) {
				return parseGeoJson(root, fallbackRegionKey);
			}
			throw new IOException("Not an ALPR camera file: no \"cameras\" and not a FeatureCollection");
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not parse ALPR camera file", e);
		}
	}

	@NonNull
	private static AlprRegionFile parseNative(@NonNull JSONObject root,
	                                          @NonNull String fallbackRegionKey) throws IOException {
		int format = root.optInt(KEY_FORMAT, 0);
		if (format <= 0) {
			throw new IOException("Missing format version");
		}
		if (format > FORMAT_VERSION) {
			throw new IOException("File uses format " + format
					+ ", this version of OsmAnd understands up to " + FORMAT_VERSION);
		}
		JSONArray array = root.optJSONArray(KEY_CAMERAS);
		if (array == null) {
			throw new IOException("Missing cameras");
		}
		List<AlprCameraPoint> cameras = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			JSONObject c = array.optJSONObject(i);
			if (c == null || !c.has("lat") || !c.has("lon")) {
				continue;
			}
			Float direction = c.has("dir") ? (float) c.optDouble("dir") : null;
			cameras.add(new AlprCameraPoint(c.optLong("id"), c.optDouble("lat"), c.optDouble("lon"),
					direction, readTags(c.optJSONObject("tags"))));
		}
		// A count that disagrees with the payload means the file was truncated or edited.
		int declared = root.optInt(KEY_COUNT, cameras.size());
		if (declared != cameras.size()) {
			throw new IOException("File declares " + declared + " cameras but contains "
					+ cameras.size() + "; it looks truncated");
		}
		return new AlprRegionFile(root.optString(KEY_REGION, fallbackRegionKey),
				root.optLong(KEY_GENERATED), root.has(KEY_SOURCE) ? root.optString(KEY_SOURCE) : null,
				readBounds(root.optJSONObject(KEY_BOUNDS)), cameras);
	}

	@NonNull
	private static AlprRegionFile parseGeoJson(@NonNull JSONObject root,
	                                           @NonNull String fallbackRegionKey) {
		List<AlprCameraPoint> cameras = new ArrayList<>();
		JSONArray features = root.optJSONArray("features");
		if (features != null) {
			for (int i = 0; i < features.length(); i++) {
				JSONObject feature = features.optJSONObject(i);
				if (feature == null) {
					continue;
				}
				JSONObject geometry = feature.optJSONObject("geometry");
				if (geometry == null || !"Point".equals(geometry.optString("type"))) {
					continue;
				}
				JSONArray coords = geometry.optJSONArray("coordinates");
				if (coords == null || coords.length() < 2) {
					continue;
				}
				double lon = coords.optDouble(0);
				double lat = coords.optDouble(1);
				Map<String, String> tags = readTags(feature.optJSONObject("properties"));
				long id = feature.has("id") ? feature.optLong("id") : 0;
				cameras.add(AlprCameraPoint.fromTags(id, lat, lon, tags));
			}
		}
		return new AlprRegionFile(fallbackRegionKey, System.currentTimeMillis(), "geojson import",
				null, cameras);
	}

	@NonNull
	private static Map<String, String> readTags(@Nullable JSONObject tags) {
		Map<String, String> result = new HashMap<>();
		if (tags != null) {
			for (Iterator<String> it = tags.keys(); it.hasNext(); ) {
				String key = it.next();
				result.put(key, tags.optString(key));
			}
		}
		return result;
	}

	@Nullable
	private static QuadRect readBounds(@Nullable JSONObject b) {
		if (b == null) {
			return null;
		}
		return new QuadRect(b.optDouble("l"), b.optDouble("t"), b.optDouble("r"), b.optDouble("b"));
	}

	/**
	 * Opens a file that may or may not be gzipped, so a hand-unzipped file still imports.
	 */
	@NonNull
	private static InputStream openMaybeGzipped(@NonNull File file) throws IOException {
		PushbackInputStream pushback =
				new PushbackInputStream(new BufferedInputStream(new FileInputStream(file)), 2);
		byte[] magic = new byte[2];
		int read = pushback.read(magic);
		if (read > 0) {
			pushback.unread(magic, 0, read);
		}
		boolean gzipped = read == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
		return gzipped ? new GZIPInputStream(pushback) : pushback;
	}

	@NonNull
	private static String readAllMaybeGzipped(@NonNull File file) throws IOException {
		try (InputStream in = openMaybeGzipped(file)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) != -1) {
				out.write(buffer, 0, n);
			}
			return out.toString(StandardCharsets.UTF_8.name());
		}
	}
}
