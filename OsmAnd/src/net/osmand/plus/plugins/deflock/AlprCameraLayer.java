package net.osmand.plus.plugins.deflock;

import static net.osmand.data.PointDescription.POINT_TYPE_ALPR_CAMERA;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Pair;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;


import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.MapMarker;
import net.osmand.core.jni.MapMarkerBuilder;
import net.osmand.core.jni.MapMarkersCollection;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.SwigUtilities;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.router.deflock.AlprCameraPoint;
import net.osmand.router.deflock.CameraCoverage;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws ALPR cameras and the direction they face.
 *
 * <p>Each camera gets a wedge showing its field of view, sized to the configured detection range
 * so what you see on the map is what the router avoids.
 */
public class AlprCameraLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider,
		AlprCameraRepository.CamerasLoadedListener {

	/** Below this zoom the cameras are too dense to be readable. */
	public static final int START_ZOOM = 13;

	/** Below this zoom the cone is drawn but the camera icon is omitted to reduce clutter. */
	private static final int START_ZOOM_ICONS = 15;

	private static final int TOUCH_RADIUS_DP = 16;
	private static final float MIN_CONE_RADIUS_PX = 12;
	private static final float MAX_CONE_RADIUS_PX = 400;

	/** Opacity of the circle drawn for a camera whose facing is not mapped. */
	private static final int OMNIDIRECTIONAL_ALPHA = 70;

	/**
	 * The cone drawable is 96 units across with its apex at the centre and the arc at 44 units,
	 * so a bitmap drawn {@code radius * CONE_BITMAP_SCALE} wide reaches exactly {@code radius}.
	 */
	private static final float CONE_BITMAP_SCALE = 96f / 44f;

	private final DeFlockPlugin plugin;

	private ContextMenuLayer contextMenuLayer;
	private MapLayerData<List<AlprCameraPoint>> data;

	private Bitmap cameraIcon;
	private Bitmap coneIcon;
	private Paint iconPaint;
	private Paint conePaint;
	private Paint circlePaint;

	// OpenGL: remembers what the marker collection was built from, so it is rebuilt only on change.
	private int renderedCameraCount = -1;
	private float renderedRange;
	private float renderedCone;

	public AlprCameraLayer(@NonNull Context context, @NonNull DeFlockPlugin plugin) {
		super(context);
		this.plugin = plugin;
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		contextMenuLayer = view.getLayerByClass(ContextMenuLayer.class);
		createResources();
		plugin.getCameraRepository().addListener(this);

		data = new MapLayerData<>() {
			{
				ZOOM_THRESHOLD = 0;
			}

			@Override
			protected Pair<List<AlprCameraPoint>, List<AlprCameraPoint>> calculateResult(
					@NonNull QuadRect bounds, int zoom) {
				List<AlprCameraPoint> cameras = zoom < START_ZOOM
						? new ArrayList<>()
						: plugin.getCameraRepository().getCameras(bounds, true);
				return new Pair<>(cameras, cameras);
			}
		};
	}

	@Override
	protected void updateResources() {
		super.updateResources();
		createResources();
	}

	private void createResources() {
		// Both icons are vector drawables, which BitmapFactory cannot decode, so render them.
		cameraIcon = rasterize(R.drawable.ic_alpr_camera, getTextScale());
		coneIcon = rasterize(R.drawable.ic_alpr_view_cone, 1f);

		int color = ContextCompat.getColor(getContext(), R.color.deflock_camera_color);
		iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		iconPaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
		// The cone drawable already carries its own translucency, so tinting with SRC_IN keeps it.
		conePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		conePaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
		// A flat circle has no baked-in alpha, so give it its own translucent paint.
		circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		circlePaint.setStyle(Paint.Style.FILL);
		circlePaint.setColor(color);
		circlePaint.setAlpha(OMNIDIRECTIONAL_ALPHA);
	}

	@Nullable
	private Bitmap rasterize(@DrawableRes int drawableId, float scale) {
		Drawable drawable = AppCompatResources.getDrawable(getContext(), drawableId);
		if (drawable == null) {
			return null;
		}
		return AndroidUtils.drawableToBitmap(drawable, Math.max(scale, 0.1f), true);
	}

	@Override
	protected void cleanupResources() {
		super.cleanupResources();
		plugin.getCameraRepository().removeListener(this);
	}

	@Override
	public void onCamerasLoaded() {
		if (data != null) {
			data.clearCache();
		}
		if (view != null) {
			view.refreshMap();
		}
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		if (!plugin.isActive() || !plugin.SHOW_ALPR_CAMERAS.get() || tileBox.getZoom() < START_ZOOM) {
			clearMapMarkersCollections();
			renderedCameraCount = -1;
			return;
		}
		data.queryNewData(tileBox);
		List<AlprCameraPoint> cameras = data.getResults();
		if (cameras == null) {
			return;
		}
		float range = plugin.ALPR_VIEW_RANGE_M.get();
		float cone = plugin.ALPR_VIEW_CONE_DEG.get();

		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			if (renderedCameraCount != cameras.size() || renderedRange != range
					|| renderedCone != cone || mapActivityInvalidated) {
				clearMapMarkersCollections();
				initMarkersCollection(cameras);
				renderedCameraCount = cameras.size();
				renderedRange = range;
				renderedCone = cone;
				mapActivityInvalidated = false;
			}
			return;
		}
		drawCones(canvas, tileBox, cameras, range, cone);
		if (tileBox.getZoom() >= START_ZOOM_ICONS) {
			drawIcons(canvas, tileBox, cameras);
		}
	}

	/**
	 * Draws each camera's view sector. The canvas is first rotated into north-up space so a cone
	 * can simply be turned to the camera's true bearing, matching how the AIS layer draws heading.
	 */
	private void drawCones(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
	                       @NonNull List<AlprCameraPoint> cameras, float rangeM, float coneDeg) {
		float coneRadiusPx = metresToPixels(tileBox, rangeM);
		if (coneRadiusPx < MIN_CONE_RADIUS_PX) {
			coneRadiusPx = MIN_CONE_RADIUS_PX;
		} else if (coneRadiusPx > MAX_CONE_RADIUS_PX) {
			coneRadiusPx = MAX_CONE_RADIUS_PX;
		}
		// The drawable is a fixed 60 degree wedge; widen or narrow it to the configured angle by
		// scaling across the facing.
		float widthScale = (float) (Math.tan(Math.toRadians(Math.min(coneDeg, 178) / 2))
				/ Math.tan(Math.toRadians(CameraCoverage.DEFAULT_CONE_DEG / 2)));
		float size = coneRadiusPx * CONE_BITMAP_SCALE;

		canvas.save();
		canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
		for (AlprCameraPoint camera : cameras) {
			float x = tileBox.getPixXFromLonNoRot(camera.getLongitude());
			float y = tileBox.getPixYFromLatNoRot(camera.getLatitude());
			if (!camera.hasDirection()) {
				// Unknown facing: the router treats the camera as omnidirectional, so draw a full
				// circle rather than implying a direction that was never mapped.
				canvas.drawCircle(x, y, coneRadiusPx, circlePaint);
				continue;
			}
			if (coneIcon == null) {
				continue;
			}
			canvas.save();
			canvas.rotate(camera.getDirection(), x, y);
			canvas.scale(widthScale, 1f, x, y);
			Rect dest = new Rect((int) (x - size / 2), (int) (y - size / 2),
					(int) (x + size / 2), (int) (y + size / 2));
			canvas.drawBitmap(coneIcon, null, dest, conePaint);
			canvas.restore();
		}
		canvas.restore();
	}

	private void drawIcons(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
	                       @NonNull List<AlprCameraPoint> cameras) {
		if (cameraIcon == null) {
			return;
		}
		float textScale = getTextScale();
		for (AlprCameraPoint camera : cameras) {
			float x = tileBox.getPixXFromLatLon(camera.getLatitude(), camera.getLongitude());
			float y = tileBox.getPixYFromLatLon(camera.getLatitude(), camera.getLongitude());
			Rect dest = getIconDestinationRect(x, y, cameraIcon.getWidth(), cameraIcon.getHeight(), textScale);
			canvas.drawBitmap(cameraIcon, null, dest, iconPaint);
		}
	}

	private static float metresToPixels(@NonNull RotatedTileBox tileBox, float metres) {
		double screenWidthM = tileBox.getDistance(0, tileBox.getPixHeight() / 2,
				tileBox.getPixWidth(), tileBox.getPixHeight() / 2);
		if (screenWidthM <= 0) {
			return MIN_CONE_RADIUS_PX;
		}
		return (float) (tileBox.getPixWidth() / screenWidthM * metres);
	}

	// --- selection ---------------------------------------------------------

	@Override
	public void collectObjectsFromPoint(@NonNull MapSelectionResult result, @NonNull MapSelectionRules rules) {
		RotatedTileBox tileBox = result.getTileBox();
		List<AlprCameraPoint> cameras = data == null ? null : data.getResults();
		if (cameras == null || cameras.isEmpty() || tileBox.getZoom() < START_ZOOM
				|| rules.isOnlyTouchableObjects() || !plugin.SHOW_ALPR_CAMERAS.get()) {
			return;
		}
		PointF point = result.getPoint();
		float radius = getScaledTouchRadius(getApplication(),
				(int) (TOUCH_RADIUS_DP * tileBox.getDensity())) * TOUCH_RADIUS_MULTIPLIER;
		QuadRect screenArea = new QuadRect(point.x - radius, point.y - radius,
				point.x + radius, point.y + radius);
		MapRendererView mapRenderer = getMapRenderer();
		List<PointI> touchPolygon31 = null;
		if (mapRenderer != null) {
			touchPolygon31 = NativeUtilities.getPolygon31FromScreenArea(mapRenderer, screenArea);
			if (touchPolygon31 == null) {
				return;
			}
		}
		for (AlprCameraPoint camera : cameras) {
			LatLon latLon = new LatLon(camera.getLatitude(), camera.getLongitude());
			boolean add = mapRenderer != null
					? NativeUtilities.isPointInsidePolygon(latLon, touchPolygon31)
					: tileBox.isLatLonInsidePixelArea(latLon, screenArea);
			if (add) {
				result.collect(camera, this);
			}
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof AlprCameraPoint camera) {
			return new LatLon(camera.getLatitude(), camera.getLongitude());
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof AlprCameraPoint camera) {
			return new PointDescription(POINT_TYPE_ALPR_CAMERA, getCameraTitle(camera));
		}
		return null;
	}

	@NonNull
	private String getCameraTitle(@NonNull AlprCameraPoint camera) {
		String manufacturer = camera.getTag("manufacturer");
		String operator = camera.getTag("operator");
		String name = getContext().getString(R.string.alpr_camera);
		if (manufacturer != null && !manufacturer.isEmpty()) {
			return name + " (" + manufacturer + ")";
		}
		if (operator != null && !operator.isEmpty()) {
			return name + " (" + operator + ")";
		}
		return name;
	}

	@Nullable
	public List<AlprCameraPoint> getVisibleCameras() {
		return data == null ? null : data.getResults();
	}

	/** OpenGL */
	private void initMarkersCollection(@NonNull List<AlprCameraPoint> cameras) {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer == null || mapMarkersCollection != null) {
			return;
		}
		Bitmap coneBitmap = tint(coneIcon);
		Bitmap iconBitmap = tint(cameraIcon);
		mapMarkersCollection = new MapMarkersCollection();
		for (AlprCameraPoint camera : cameras) {
			int x = MapUtils.get31TileNumberX(camera.getLongitude());
			int y = MapUtils.get31TileNumberY(camera.getLatitude());
			MapMarkerBuilder builder = new MapMarkerBuilder()
					.setPosition(new PointI(x, y))
					.setIsAccuracyCircleSupported(false)
					.setBaseOrder(getPointsOrder())
					.setPinIcon(NativeUtilities.createSkImageFromBitmap(iconBitmap))
					.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
					.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical);
			if (camera.hasDirection()) {
				builder.addOnMapSurfaceIcon(SwigUtilities.getOnSurfaceIconKey(1),
						NativeUtilities.createSkImageFromBitmap(coneBitmap));
			}
			MapMarker marker = builder.buildAndAddToCollection(mapMarkersCollection);
			if (camera.hasDirection() && marker != null) {
				marker.setOnMapSurfaceIconDirection(SwigUtilities.getOnSurfaceIconKey(1),
						camera.getDirection());
			}
		}
		mapRenderer.addSymbolsProvider(mapMarkersCollection);
	}

	/** OpenGL: the native renderer takes a plain image, so bake the tint in. */
	@NonNull
	private Bitmap tint(@NonNull Bitmap source) {
		Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
		new Canvas(result).drawBitmap(source, 0, 0, iconPaint);
		return result;
	}
}
