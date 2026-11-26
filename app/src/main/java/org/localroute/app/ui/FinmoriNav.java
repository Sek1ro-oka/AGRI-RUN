package org.localroute.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;
import org.localroute.app.databinding.ViewBottomNavBinding;

/**
 * 四个目的地共用的底部导航：选中态与跳转都在这里，页面只说自己是哪一个。
 *
 * <p>四个页面各自是独立 Activity（与本工程既有结构一致，没有 Fragment 与导航图），所以切换用
 * {@code REORDER_TO_FRONT} 把已有实例提到前面，不重建、不叠栈；转场动画关掉，避免同级切换看起来
 * 像进了下一级。点当前页不做事。
 */
final class FinmoriNav {

    enum Tab {
        HOME, FUNCTIONS, PROFILE, SETTINGS
    }

    private FinmoriNav() {
    }

    static void bind(@NonNull AppCompatActivity activity, @NonNull ViewBottomNavBinding nav,
                     @NonNull Tab current) {
        apply(activity, nav.navHome, nav.iconNavHome, nav.textNavHome, current == Tab.HOME,
                R.string.fm_home);
        apply(activity, nav.navFunctions, nav.iconNavFunctions, nav.textNavFunctions,
                current == Tab.FUNCTIONS, R.string.fm_functions);
        apply(activity, nav.navProfile, nav.iconNavProfile, nav.textNavProfile,
                current == Tab.PROFILE, R.string.fm_profile);
        apply(activity, nav.navSettings, nav.iconNavSettings, nav.textNavSettings,
                current == Tab.SETTINGS, R.string.home_app_settings_title);

        nav.navHome.setOnClickListener(v -> open(activity, current, Tab.HOME));
        nav.navFunctions.setOnClickListener(v -> open(activity, current, Tab.FUNCTIONS));
        nav.navProfile.setOnClickListener(v -> open(activity, current, Tab.PROFILE));
        nav.navSettings.setOnClickListener(v -> open(activity, current, Tab.SETTINGS));
        FinmoriMotion.press(nav.navHome);
        FinmoriMotion.press(nav.navFunctions);
        FinmoriMotion.press(nav.navProfile);
        FinmoriMotion.press(nav.navSettings);
        constrainWideLayout(nav.getRoot());
    }

    private static void apply(@NonNull AppCompatActivity activity, @NonNull android.view.View column,
                              @NonNull ImageView icon, @NonNull TextView label, boolean selected,
                              int labelRes) {
        int color = ContextCompat.getColor(activity,
                selected ? R.color.fm_on_nav : R.color.fm_nav_idle);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        label.setTextColor(color);
        label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        column.setSelected(selected);
        column.setContentDescription(activity.getString(labelRes));
    }

    /** Keep the four top-level pages readable when the app window is wide. */
    private static void constrainWideLayout(View navigation) {
        if (!(navigation.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) navigation.getParent();
        int maxWidth = root.getResources().getDimensionPixelSize(R.dimen.content_max_width);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - root.getPaddingLeft() - root.getPaddingRight();
            if (available <= 0) return;
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child instanceof ScrollView) {
                    ScrollView scroll = (ScrollView) child;
                    if (scroll.getChildCount() == 0) continue;
                    View content = scroll.getChildAt(0);
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
                    int width = Math.min(available, maxWidth);
                    if (params.width != width || params.gravity != Gravity.CENTER_HORIZONTAL) {
                        params.width = width;
                        params.gravity = Gravity.CENTER_HORIZONTAL;
                        content.setLayoutParams(params);
                    }
                } else if (child == navigation || child instanceof com.google.android.material.appbar.MaterialToolbar) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                    int width = Math.min(maxWidth, available - params.leftMargin - params.rightMargin);
                    if (params.width != width || params.gravity != Gravity.CENTER_HORIZONTAL) {
                        params.width = width;
                        params.gravity = Gravity.CENTER_HORIZONTAL;
                        child.setLayoutParams(params);
                    }
                }
            }
        });
    }

    private static void open(@NonNull AppCompatActivity activity, @NonNull Tab current,
                             @NonNull Tab target) {
        if (current == target) {
            return;
        }
        Class<?> destination;
        switch (target) {
            case FUNCTIONS:
                destination = FunctionsActivity.class;
                break;
            case PROFILE:
                destination = ProfileActivity.class;
                break;
            case SETTINGS:
                destination = AppSettingsActivity.class;
                break;
            default:
                destination = MainActivity.class;
                break;
        }
        activity.startActivity(new Intent(activity, destination)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        activity.overridePendingTransition(0, 0);
    }
}
