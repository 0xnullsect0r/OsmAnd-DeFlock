package net.osmand.plus.auto;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.car.app.Screen;
import androidx.car.app.Session;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.auto.screens.ClusterScreen;

/**
 * The session the host creates for a vehicle's instrument-cluster display.
 *
 * <p>Intentionally minimal. Three rules hold this together, each guarding a real hazard:
 *
 * <ul>
 *   <li>It never registers itself as {@code OsmandApplication.carNavigationSession}. That setter
 *       calls {@code RoutingHelper.onCarNavigationSessionChanged()}, which <em>stops or pauses
 *       navigation</em> when the value goes null - so a cluster display disconnecting would end the
 *       drive.</li>
 *   <li>It never builds a {@link SurfaceRenderer}. {@code setupOffscreenRenderer()} detaches the
 *       map renderer from its current owner, which would rip the map off the centre screen.</li>
 *   <li>It never takes a {@code NavigationManager}. Trip ownership stays with the main session;
 *       the cluster only reads the published {@link TripSnapshot}.</li>
 * </ul>
 */
public class ClusterSession extends Session implements DefaultLifecycleObserver {

	@Nullable
	private ClusterScreen screen;

	@NonNull
	private OsmandApplication getApp() {
		return (OsmandApplication) getCarContext().getApplicationContext();
	}

	@NonNull
	@Override
	public Screen onCreateScreen(@NonNull Intent intent) {
		getLifecycle().addObserver(this);
		screen = new ClusterScreen(getCarContext());
		getApp().setClusterNavigationSession(this);
		return screen;
	}

	@Override
	public void onDestroy(@NonNull LifecycleOwner owner) {
		getApp().setClusterNavigationSession(null);
		screen = null;
	}

	/** Redraws the cluster from the latest published snapshot. */
	public void invalidateScreen() {
		ClusterScreen screen = this.screen;
		if (screen != null) {
			screen.invalidate();
		}
	}
}
