package net.osmand.plus.auto.screens;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.TripSnapshot;

/**
 * The screen shown on a vehicle's instrument cluster.
 *
 * <p>Cluster displays are non-interactive and accept only {@link NavigationTemplate}, so this draws
 * turn-by-turn information and nothing else - no map surface, no action handling, no pan controls.
 * It reads a {@link TripSnapshot} published by the main session rather than touching the map
 * renderer, which belongs to the centre display.
 */
public class ClusterScreen extends Screen {

	public ClusterScreen(@NonNull CarContext carContext) {
		super(carContext);
	}

	@NonNull
	private OsmandApplication getApp() {
		return (OsmandApplication) getCarContext().getApplicationContext();
	}

	@NonNull
	@Override
	public Template onGetTemplate() {
		NavigationTemplate.Builder builder = new NavigationTemplate.Builder();
		// NavigationTemplate.build() rejects a template with no action strip, even on a display that
		// cannot be touched, so supply the minimal one.
		builder.setActionStrip(new ActionStrip.Builder().addAction(Action.APP_ICON).build());

		TripSnapshot snapshot = getApp().getCarTripSnapshot();
		if (snapshot == null || !snapshot.navigating) {
			return builder.setNavigationInfo(new MessageInfo.Builder(
					getCarContext().getString(R.string.shared_string_navigation)).build()).build();
		}
		if (snapshot.arrived) {
			builder.setNavigationInfo(new MessageInfo.Builder(
					getCarContext().getString(R.string.arrived_at_destination)).build());
		} else if (snapshot.rerouting || !snapshot.hasStep()) {
			builder.setNavigationInfo(new RoutingInfo.Builder().setLoading(true).build());
		} else {
			builder.setNavigationInfo(new RoutingInfo.Builder()
					.setCurrentStep(snapshot.currentStep, snapshot.stepRemainingDistance)
					.build());
		}
		if (snapshot.destinationTravelEstimate != null) {
			builder.setDestinationTravelEstimate(snapshot.destinationTravelEstimate);
		}
		return builder.build();
	}
}
