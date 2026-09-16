package net.osmand.plus.auto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.model.Distance;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;

/**
 * An immutable snapshot of the current navigation state, published by {@link NavigationSession} on
 * every trip update and read by the instrument-cluster screen.
 *
 * <p>This is deliberately the <em>entire</em> coupling between the main display session and the
 * cluster session. The cluster must never reach into {@code NavigationSession} directly: the map
 * renderer and the {@code NavigationManager} are single-owner resources belonging to the main
 * display, and sharing them would tear the map off the centre screen or duplicate trip ownership.
 */
public class TripSnapshot {

	public final boolean navigating;
	public final boolean rerouting;
	public final boolean arrived;
	@Nullable
	public final Step currentStep;
	@Nullable
	public final Distance stepRemainingDistance;
	@Nullable
	public final TravelEstimate destinationTravelEstimate;

	public TripSnapshot(boolean navigating, boolean rerouting, boolean arrived,
	                    @Nullable Step currentStep, @Nullable Distance stepRemainingDistance,
	                    @Nullable TravelEstimate destinationTravelEstimate) {
		this.navigating = navigating;
		this.rerouting = rerouting;
		this.arrived = arrived;
		this.currentStep = currentStep;
		this.stepRemainingDistance = stepRemainingDistance;
		this.destinationTravelEstimate = destinationTravelEstimate;
	}

	/** Nothing is being navigated. */
	@NonNull
	public static TripSnapshot idle() {
		return new TripSnapshot(false, false, false, null, null, null);
	}

	/** True when there is a maneuver the cluster can actually draw. */
	public boolean hasStep() {
		return currentStep != null && stepRemainingDistance != null;
	}
}
