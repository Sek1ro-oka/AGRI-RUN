package org.localroute.app.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;

import java.util.Locale;

/**
 * 悬浮窗等比缩放键：右上角一枚圆形「⌊ ⌉」按钮。
 *
 * <p>按住后沿对角线拖动调整整个控制台的大小：往左下拖缩小，往右上拖放大；两者是同一根
 * 对角线上的投影，斜着拖也能稳定变化。位移用屏幕原始坐标计算，悬浮窗在拖动过程中实时缩放
 * 不会让手指与按钮错位。平时显示「⌊ ⌉」（左括号保留下半段、右括号保留上半段），拖动期间在
 * 按钮中间显示当前百分比。
 *
 * <p>整个面板的可视大小由 {@link OverlayScaleHost} 按同一比例缩放窗口，按钮、摇杆与间距
 * 一起等比变化。
 */
public final class ScaleControlView extends View {

    public static final float MIN_SCALE = 0.7f;
    public static final float MAX_SCALE = 1.4f;

    /** 缩放变化回调，均在主线程触发。 */
    public interface Listener {
        /** 拖动过程中连续回调，用于实时改变悬浮窗大小。 */
        void onScaleChanged(float scale);

        /** 松手后回调一次，用于持久化。 */
        void onScaleCommitted(float scale);
    }

    /** 沿对角线拖满整个范围（0.7）需要滑动的 dp；数值越大越精细。 */
    private static final float FULL_RANGE_DRAG_DP = 120f;

    /** 小于这个对角位移视为点按，不改变缩放。 */
    private static final float TOUCH_SLOP_DP = 3f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphs = new Path();
    private final RectF oval = new RectF();

    private float scale = 1f;
    private float downRawX;
    private float downRawY;
    private float downScale;
    private boolean dragging;
    @Nullable
    private Listener listener;

    public ScaleControlView(Context context) {
        super(context);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ContextCompat.getColor(context, R.color.overlay_button_fill));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        ringPaint.setColor(ContextCompat.getColor(context, R.color.overlay_ring));

        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.6f));
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        glyphPaint.setColor(ContextCompat.getColor(context, R.color.overlay_mint));

        textPaint.setColor(ContextCompat.getColor(context, R.color.overlay_icon));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setFakeBoldText(true);

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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildGlyphs(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        oval.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f));
        canvas.drawOval(oval, fillPaint);
        ringPaint.setColor(ContextCompat.getColor(getContext(),
                dragging ? R.color.overlay_mint : R.color.overlay_ring));
        canvas.drawOval(oval, ringPaint);

        if (dragging) {
            String percent = String.format(Locale.US,
                    getContext().getString(R.string.overlay_scale_percent), percentOf(scale));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(percent, width / 2f, baseline, textPaint);
        } else {
            canvas.drawPath(glyphs, glyphPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downScale = scale;
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dxDp = (event.getRawX() - downRawX)
                        / getResources().getDisplayMetrics().density;
                // 对角线投影：右上（dx 为正、dy 为负）增大，左下（dx 为负、dy 为正）减小。
                float deltaDp = dxDp - (event.getRawY() - downRawY)
                        / getResources().getDisplayMetrics().density;
                if (!dragging && Math.abs(deltaDp) < TOUCH_SLOP_DP) {
                    return true;
                }
                dragging = true;
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
                boolean wasDragging = dragging;
                dragging = false;
                invalidate();
                performClick();
                if (wasDragging && listener != null) {
                    listener.onScaleCommitted(scale);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return true;
                }
                dragging = false;
                invalidate();
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

    /** 左「⌊」右「⌉」：左括号只留下半段，右括号只留上半段。 */
    private void buildGlyphs(float width, float height) {
        glyphs.reset();
        float cx = width / 2f;
        float cy = height / 2f;
        float arm = dp(4.2f);
        float half = dp(4.6f);
        float gap = dp(2.4f);
        glyphs.moveTo(cx - gap, cy);
        glyphs.lineTo(cx - gap, cy + half);
        glyphs.lineTo(cx - gap + arm, cy + half);
        glyphs.moveTo(cx + gap, cy);
        glyphs.lineTo(cx + gap, cy - half);
        glyphs.lineTo(cx + gap - arm, cy - half);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
