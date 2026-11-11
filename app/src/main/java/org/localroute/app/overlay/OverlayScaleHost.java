package org.localroute.app.overlay;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 悬浮控制台的等比缩放容器。
 *
 * <p>内容始终按「基准尺寸」测量与布局，容器自身则按 {@link #setScale(float)} 的比例收缩或放大，
 * 并对内容施加同比例的 {@code scaleX/scaleY}（轴心在左上角）。这样窗口大小与视觉大小始终一致，
 * 触摸命中区域也不会错位；如果只缩放视图而不改窗口大小，四周会留下吃触摸的透明区域。
 */
final class OverlayScaleHost extends FrameLayout {

    private float scale = 1f;
    private int baseWidth;
    private int baseHeight;

    OverlayScaleHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setScale(float value) {
        float next = ScaleControlView.clamp(value);
        if (Math.abs(this.scale - next) < 0.0005f) {
            return;
        }
        this.scale = next;
        requestLayout();
    }

    float scale() {
        return scale;
    }

    int baseWidth() {
        return baseWidth;
    }

    int baseHeight() {
        return baseHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        baseWidth = child.getMeasuredWidth();
        baseHeight = child.getMeasuredHeight();
        setMeasuredDimension(Math.max(1, Math.round(baseWidth * scale)),
                Math.max(1, Math.round(baseHeight * scale)));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child == null) {
            return;
        }
        child.layout(0, 0, baseWidth, baseHeight);
        child.setPivotX(0f);
        child.setPivotY(0f);
        child.setScaleX(scale);
        child.setScaleY(scale);
    }
}
