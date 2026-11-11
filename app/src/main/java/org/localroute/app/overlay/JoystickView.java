package org.localroute.app.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.localroute.app.R;

/**
 * 圆形虚拟摇杆。
 *
 * <p>按住拖动时输出归一化方向向量（右 +x，下 +y，长度 0—1），松手自动回中并回调
 * {@link Listener#onRelease()}。摇杆只负责输入，不直接改模拟位置；移动节奏由
 * {@link OverlayPanel} 按固定间隔推进。
 */
public final class JoystickView extends View {

    /** 输入回调，均在主线程触发。 */
    public interface Listener {
        void onMove(float x, float y);

        void onRelease();
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevrons = new Path();

    private float centerX;
    private float centerY;
    private float baseRadius;
    private float knobRadius;
    private float maxOffset;
    private float knobX;
    private float knobY;

    private boolean engaged;
    private boolean inputEnabled = true;
    @Nullable
    private Listener listener;

    public JoystickView(Context context) {
        this(context, null);
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(ContextCompat.getColor(context, R.color.overlay_joystick_fill));

        baseRingPaint.setStyle(Paint.Style.STROKE);
        baseRingPaint.setStrokeWidth(dp(1f));
        baseRingPaint.setColor(ContextCompat.getColor(context, R.color.overlay_joystick_ring));

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(dp(1.6f));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setColor(ContextCompat.getColor(context, R.color.overlay_chevron));

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(ContextCompat.getColor(context, R.color.overlay_knob_fill));

        knobRingPaint.setStyle(Paint.Style.STROKE);
        knobRingPaint.setStrokeWidth(dp(1.5f));
        knobRingPaint.setColor(ContextCompat.getColor(context, R.color.overlay_knob_ring));

        knobDotPaint.setStyle(Paint.Style.FILL);
        knobDotPaint.setColor(ContextCompat.getColor(context, R.color.overlay_mint));

        setClickable(true);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** 暂停或收起时禁用输入：立刻回中并通知调用方停止推进。 */
    public void setInputEnabled(boolean enabled) {
        this.inputEnabled = enabled;
        if (!enabled) {
            engaged = false;
            knobX = 0f;
            knobY = 0f;
            invalidate();
            if (listener != null) {
                listener.onRelease();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f - dp(2f);
        knobRadius = baseRadius * 0.40f;
        maxOffset = Math.max(1f, baseRadius - knobRadius - dp(2f));
        buildChevrons();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseRadius <= 0f) {
            return;
        }
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius - dp(0.5f), baseRingPaint);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.72f, baseRingPaint);
        canvas.drawPath(chevrons, chevronPaint);

        float x = centerX + knobX;
        float y = centerY + knobY;
        canvas.drawCircle(x, y, knobRadius, knobPaint);
        canvas.drawCircle(x, y, knobRadius, knobRingPaint);
        canvas.drawCircle(x, y, dp(3f), knobDotPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!inputEnabled) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (distanceToCenter(event) > baseRadius) {
                    return false;
                }
                engaged = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                applyTouch(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!engaged) {
                    return false;
                }
                applyTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (!engaged) {
                    return false;
                }
                performClick();
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!engaged) {
                    return false;
                }
                release();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void applyTouch(MotionEvent event) {
        float dx = event.getX() - centerX;
        float dy = event.getY() - centerY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance > maxOffset) {
            dx = dx / distance * maxOffset;
            dy = dy / distance * maxOffset;
        }
        knobX = dx;
        knobY = dy;
        invalidate();
        if (listener == null) {
            return;
        }
        float nx = knobX / maxOffset;
        float ny = knobY / maxOffset;
        if (Math.hypot(nx, ny) < 0.12f) {
            listener.onMove(0f, 0f);
        } else {
            listener.onMove(nx, ny);
        }
    }

    private void release() {
        engaged = false;
        knobX = 0f;
        knobY = 0f;
        invalidate();
        if (listener != null) {
            listener.onRelease();
        }
    }

    private float distanceToCenter(MotionEvent event) {
        return (float) Math.hypot(event.getX() - centerX, event.getY() - centerY);
    }

    private void buildChevrons() {
        chevrons.reset();
        float r = baseRadius * 0.84f;
        float s = dp(4.5f);
        // 上
        chevrons.moveTo(centerX - s, centerY - r + s);
        chevrons.lineTo(centerX, centerY - r);
        chevrons.lineTo(centerX + s, centerY - r + s);
        // 下
        chevrons.moveTo(centerX - s, centerY + r - s);
        chevrons.lineTo(centerX, centerY + r);
        chevrons.lineTo(centerX + s, centerY + r - s);
        // 左
        chevrons.moveTo(centerX - r + s, centerY - s);
        chevrons.lineTo(centerX - r, centerY);
        chevrons.lineTo(centerX - r + s, centerY + s);
        // 右
        chevrons.moveTo(centerX + r - s, centerY - s);
        chevrons.lineTo(centerX + r, centerY);
        chevrons.lineTo(centerX + r - s, centerY + s);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
