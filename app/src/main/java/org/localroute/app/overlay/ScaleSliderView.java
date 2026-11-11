package org.localroute.app.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;

import java.util.Locale;

/**
 * 悬浮窗等比缩放滑杆。
 *
 * <p>视觉是一枚 72×48 dp 的胶囊：上半显示百分比，下半是轨道与滑点。左右拖动时按
 * <b>屏幕原始坐标的位移</b>换算比例（而不是读取被缩放后的局部坐标），这样悬浮窗在拖动过程中
 * 实时缩放也不会让滑点追着手指跑偏。整个控制台的可视大小由 {@link OverlayScaleHost} 按同一比例
 * 缩放窗口，按钮、摇杆与间距一起等比变化。
 */
public final class ScaleSliderView extends View {

    public static final float MIN_SCALE = 0.7f;
    public static final float MAX_SCALE = 1.4f;

    /** 缩放变化回调，均在主线程触发。 */
    public interface Listener {
        /** 拖动过程中连续回调，用于实时改变悬浮窗大小。 */
        void onScaleChanged(float scale);

        /** 松手后回调一次，用于持久化。 */
        void onScaleCommitted(float scale);
    }

    /** 拖满整个范围（0.7）需要滑动多少 dp；数值越大越精细。 */
    private static final float FULL_RANGE_DRAG_DP = 140f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();

    private float scale = 1f;
    private float downRawX;
    private float downScale;
    private boolean dragging;
    @Nullable
    private Listener listener;

    public ScaleSliderView(Context context) {
        super(context);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ContextCompat.getColor(context, R.color.overlay_button_fill));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        ringPaint.setColor(ContextCompat.getColor(context, R.color.overlay_ring));

        textPaint.setColor(ContextCompat.getColor(context, R.color.overlay_icon));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(11f));
        textPaint.setFakeBoldText(true);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(2f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.overlay_ring));

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(ContextCompat.getColor(context, R.color.overlay_mint));

        setClickable(true);
        setContentDescription(context.getString(R.string.overlay_scale));
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setScale(float value) {
        this.scale = clamp(value);
        invalidate();
    }

    public float scale() {
        return scale;
    }

    public static float clamp(float value) {
        if (!Float.isFinite(value)) {
            return 1f;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    public static int percentOf(float scale) {
        return Math.round(clamp(scale) * 100f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        pill.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f));
        float radius = pill.height() / 2f;
        canvas.drawRoundRect(pill, radius, radius, fillPaint);
        canvas.drawRoundRect(pill, radius, radius, ringPaint);

        String percent = String.format(Locale.US,
                getContext().getString(R.string.overlay_scale_percent), percentOf(scale));
        canvas.drawText(percent, width / 2f, dp(19f), textPaint);

        float trackStart = dp(12f);
        float trackEnd = width - dp(12f);
        float trackY = height - dp(14f);
        canvas.drawLine(trackStart, trackY, trackEnd, trackY, trackPaint);

        float fraction = fractionOf(scale);
        canvas.drawCircle(trackStart + (trackEnd - trackStart) * fraction, trackY,
                dp(4.5f), knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                downRawX = event.getRawX();
                downScale = scale;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }
                float deltaDp = (event.getRawX() - downRawX)
                        / getResources().getDisplayMetrics().density;
                float next = clamp(downScale
                        + deltaDp / FULL_RANGE_DRAG_DP * (MAX_SCALE - MIN_SCALE));
                if (Math.abs(next - scale) > 0.0001f) {
                    scale = next;
                    invalidate();
                    if (listener != null) {
                        listener.onScaleChanged(scale);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    return false;
                }
                dragging = false;
                performClick();
                if (listener != null) {
                    listener.onScaleCommitted(scale);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return false;
                }
                dragging = false;
                if (listener != null) {
                    listener.onScaleCommitted(scale);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static float fractionOf(float value) {
        return (clamp(value) - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
