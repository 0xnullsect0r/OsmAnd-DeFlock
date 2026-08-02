package net.osmand.plus.plugins.deflock;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem;
import net.osmand.plus.base.bottomsheetmenu.BottomSheetItemWithCompoundButton;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.LongDescriptionItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.utils.UiUtilities;

/**
 * Turns ALPR camera avoidance on or off for the current profile and sets how much extra travel
 * time is acceptable in exchange.
 */
public class AvoidCamerasBottomSheet extends MenuBottomSheetDialogFragment {

	public static final String TAG = AvoidCamerasBottomSheet.class.getSimpleName();

	private static final String APP_MODE_KEY = "app_mode_key";

	private DeFlockPlugin plugin;
	private ApplicationMode appMode;
	@ColorInt
	private Integer activeColor;

	private int detourMinutes;
	private boolean avoidEnabled;

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		plugin = PluginsHelper.getPlugin(DeFlockPlugin.class);
		if (plugin == null) {
			return;
		}
		if (appMode == null) {
			appMode = app.getSettings().getApplicationMode();
		}
		if (activeColor == null) {
			activeColor = appMode.getProfileColor(nightMode);
		}
		avoidEnabled = plugin.AVOID_ALPR_CAMERAS.getModeValue(appMode);
		detourMinutes = plugin.ALPR_DETOUR_BUDGET_MIN.getModeValue(appMode);

		items.add(new TitleItem(getString(R.string.alpr_avoid_cameras)));
		items.add(new LongDescriptionItem(getString(R.string.alpr_avoid_cameras_description)));

		BottomSheetItemWithCompoundButton[] toggle = new BottomSheetItemWithCompoundButton[1];
		toggle[0] = (BottomSheetItemWithCompoundButton) new BottomSheetItemWithCompoundButton.Builder()
				.setChecked(avoidEnabled)
				.setCompoundButtonColor(activeColor)
				.setTitle(getString(R.string.alpr_avoid_cameras))
				.setLayoutId(R.layout.bottom_sheet_item_with_switch)
				.setOnClickListener(view -> {
					avoidEnabled = !avoidEnabled;
					toggle[0].setChecked(avoidEnabled);
				})
				.create();
		items.add(toggle[0]);
		items.add(createDetourSliderItem());
		items.add(new LongDescriptionItem(getString(R.string.alpr_avoidance_uses_standard_routing)));
	}

	@NonNull
	private BaseBottomSheetItem createDetourSliderItem() {
		View view = LayoutInflater.from(getContext()).inflate(R.layout.alpr_detour_slider, null);
		TextView valueView = view.findViewById(R.id.detour_value);
		Slider slider = view.findViewById(R.id.detour_slider);

		slider.setValueFrom(0);
		slider.setValueTo(DeFlockPlugin.MAX_DETOUR_BUDGET_MIN);
		slider.setValue(Math.min(detourMinutes, DeFlockPlugin.MAX_DETOUR_BUDGET_MIN));
		valueView.setText(formatDetour(detourMinutes));
		slider.addOnChangeListener((s, value, fromUser) -> {
			detourMinutes = (int) value;
			valueView.setText(formatDetour(detourMinutes));
		});
		UiUtilities.setupSlider(slider, nightMode, activeColor, false);

		return new BaseBottomSheetItem.Builder().setCustomView(view).create();
	}

	@NonNull
	private String formatDetour(int minutes) {
		return getString(R.string.alpr_detour_budget_value, minutes + " " + getString(R.string.int_min));
	}

	@Override
	protected int getRightBottomButtonTextId() {
		return R.string.shared_string_apply;
	}

	@Override
	protected void onRightBottomButtonClick() {
		if (plugin != null && appMode != null) {
			plugin.AVOID_ALPR_CAMERAS.setModeValue(appMode, avoidEnabled);
			plugin.ALPR_DETOUR_BUDGET_MIN.setModeValue(appMode, detourMinutes);
			app.getRoutingHelper().onSettingsChanged(appMode);
		}
		Fragment target = getTargetFragment();
		if (target instanceof AvoidCamerasListener listener) {
			listener.onAvoidCamerasApplied();
		}
		dismiss();
	}

	public interface AvoidCamerasListener {
		void onAvoidCamerasApplied();
	}

	public static void showInstance(@NonNull FragmentActivity activity, @Nullable Fragment target,
	                                @Nullable ApplicationMode appMode, @ColorInt Integer activeColor) {
		FragmentManager manager = activity.getSupportFragmentManager();
		if (manager.findFragmentByTag(TAG) == null) {
			AvoidCamerasBottomSheet fragment = new AvoidCamerasBottomSheet();
			fragment.appMode = appMode;
			fragment.activeColor = activeColor;
			fragment.setTargetFragment(target, 0);
			fragment.show(manager, TAG);
		}
	}
}
