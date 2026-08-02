package net.osmand.plus.plugins.deflock;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.data.QuadRect;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.router.deflock.AlprCameraPoint;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Downloads ALPR camera nodes from the Overpass API.
 *
 * <p>The DeFlock project crowdsources these cameras straight into OpenStreetMap, so Overpass is
 * the canonical source. They are not available from OsmAnd's offline maps: {@code
 * man_made=surveillance} is declared {@code no_indx="true"} in poi_types.xml, which keeps it out
 * of the OBF POI index entirely, and {@code surveillance:type} is not modelled at all.
 */
public class OverpassAlprClient {

	private static final Log log = PlatformUtil.getLog(OverpassAlprClient.class);

	public static final String DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter";
	public static final String FALLBACK_ENDPOINT = "https://overpass.kumi.systems/api/interpreter";

	private static final int QUERY_TIMEOUT_S = 90;
	private static final int CONNECT_TIMEOUT_MS = 20000;
	private static final int READ_TIMEOUT_MS = 120000;
	private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

	/** Thrown when the server asks us to back off, so the caller can retry later rather than
	 * caching an empty result. */
	public static class OverpassBusyException extends IOException {
		public OverpassBusyException(String message) {
			super(message);
		}
	}

	private final String endpoint;

	public OverpassAlprClient() {
		this(DEFAULT_ENDPOINT);
	}

	public OverpassAlprClient(@NonNull String endpoint) {
		this.endpoint = endpoint;
	}

	/**
	 * Fetches every ALPR camera within the given lat/lon bounds.
	 *
	 * @throws OverpassBusyException when the endpoint is rate limiting or overloaded
	 * @throws IOException on any other network or parse failure
	 */
	@NonNull
	public List<AlprCameraPoint> fetch(@NonNull QuadRect latLonBounds) throws IOException {
		String query = buildQuery(latLonBounds);
		String response = post(endpoint, query);
		return parse(response);
	}

	static String buildQuery(@NonNull QuadRect latLonBounds) {
		// QuadRect for lat/lon bounds holds top = max latitude, bottom = min latitude.
		double south = Math.min(latLonBounds.top, latLonBounds.bottom);
		double north = Math.max(latLonBounds.top, latLonBounds.bottom);
		double west = Math.min(latLonBounds.left, latLonBounds.right);
		double east = Math.max(latLonBounds.left, latLonBounds.right);
		// surveillance:type=ALPR is the reliable selector: in live data every DeFlock node carries
		// it, while manufacturer=Flock Safety is only on part of them.
		return "[out:json][timeout:" + QUERY_TIMEOUT_S + "];"
				+ "node[\"surveillance:type\"=\"ALPR\"]("
				+ south + "," + west + "," + north + "," + east + ");"
				+ "out tags;";
	}

	private String post(@NonNull String url, @NonNull String query) throws IOException {
		HttpURLConnection connection = NetworkUtils.getHttpURLConnection(url);
		try {
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			connection.setRequestProperty("Accept", "application/json");

			byte[] body = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(body.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body);
			}

			int code = connection.getResponseCode();
			if (code == 429 || code == 504 || code == 503) {
				throw new OverpassBusyException("Overpass is busy (HTTP " + code + ")");
			}
			if (code != HttpURLConnection.HTTP_OK) {
				throw new IOException("Overpass request failed with HTTP " + code + " "
						+ connection.getResponseMessage());
			}
			return read(connection);
		} finally {
			connection.disconnect();
		}
	}

	private String read(@NonNull HttpURLConnection connection) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			char[] buffer = new char[8192];
			int read;
			while ((read = reader.read(buffer)) != -1) {
				sb.append(buffer, 0, read);
				if (sb.length() > MAX_RESPONSE_BYTES) {
					throw new IOException("Overpass response exceeded " + MAX_RESPONSE_BYTES + " bytes");
				}
			}
		}
		return sb.toString();
	}

	@NonNull
	static List<AlprCameraPoint> parse(String json) throws IOException {
		List<AlprCameraPoint> cameras = new ArrayList<>();
		if (Algorithms.isEmpty(json)) {
			return cameras;
		}
		try {
			JSONObject root = new JSONObject(json);
			JSONArray elements = root.optJSONArray("elements");
			if (elements == null) {
				return cameras;
			}
			for (int i = 0; i < elements.length(); i++) {
				JSONObject element = elements.optJSONObject(i);
				if (element == null || !element.has("lat") || !element.has("lon")) {
					continue;
				}
				long id = element.optLong("id");
				double lat = element.optDouble("lat");
				double lon = element.optDouble("lon");
				if (Double.isNaN(lat) || Double.isNaN(lon)) {
					continue;
				}
				cameras.add(AlprCameraPoint.fromTags(id, lat, lon, readTags(element.optJSONObject("tags"))));
			}
		} catch (Exception e) {
			throw new IOException("Could not parse Overpass response", e);
		}
		return cameras;
	}

	@NonNull
	private static Map<String, String> readTags(JSONObject tags) {
		Map<String, String> result = new HashMap<>();
		if (tags != null) {
			for (Iterator<String> it = tags.keys(); it.hasNext(); ) {
				String key = it.next();
				result.put(key, tags.optString(key));
			}
		}
		return result;
	}

	public String getEndpoint() {
		return endpoint;
	}
}
