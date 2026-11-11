package org.localroute.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 可左滑露出操作按钮的列表行。
 *
 * <p>内容层（{@link #setContent}）盖在右侧按钮上方：默认完全盖住，向左拖动后露出
 * 「修改」「删除」。竖直方向的滑动不拦截，交给外层 ScrollView；只有水平位移超过触摸阈值
 * 才接管。轻点内容层触发它的 OnClickListener（由调用方设置），行处于展开状态时轻点先收起。
 */
public final class SwipeActionRow extends FrameLayout {

    private static final int ACTION_WIDTH_DP = 72;
    private static final long SETTLE_MS = 160L;

    private final LinearLayout actionBar;
    private final int actionWidthPx;
    private final int touchSlop;

    private View content;
    private int actionCount;
    private float downRawX;
    private float downRawY;
    private float startTranslation;
    private boolean horizontalDrag;
    private boolean verticalPass;

    public SwipeActionRow(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        actionWidthPx = Math.round(ACTION_WIDTH_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        actionBar = new LinearLayout(context);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.END);
        addView(actionBar, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END));
    }

    /** 设置盖在按钮上方的内容视图；它需要有完全不透明的背景，否则按钮会透出来。 */
    public void setContent(View content) {
        if (this.content != null) {
            removeView(this.content);
        }
        this.content = content;
        content.setOnTouchListener(new SwipeTouchListener());
        addView(content, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 从右往左添加操作按钮：先添加的靠左，最后添加的贴在行最右侧。 */
    public void addAction(String label, int backgroundColor, Runnable action) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        button.setBackground(background);
        button.setOnClickListener(v -> {
            close();
            if (action != null) {
                action.run();
            }
        });
        actionBar.addView(button, new LinearLayout.LayoutParams(
                actionWidthPx, ViewGroup.LayoutParams.MATCH_PARENT));
        actionCount++;
    }

    /** 收起操作按钮（例如别处点了删除后重建列表）。 */
    public void close() {
        if (content == null) {
            return;
        }
        content.animate().translationX(0f).setDuration(SETTLE_MS).start();
    }

    private int totalActionWidth() {
        return actionCount * actionWidthPx;
    }

    private void settle(boolean open) {
        if (content == null) {
            return;
        }
        content.animate()
                .translationX(open ? -totalActionWidth() : 0f)
                .setDuration(SETTLE_MS)
                .start();
    }

    private final class SwipeTouchListener implements OnTouchListener {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startTranslation = content.getTranslationX();
                    horizontalDrag = false;
                    verticalPass = false;
                    content.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!horizontalDrag && !verticalPass) {
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                            horizontalDrag = true;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        } else if (Math.abs(dy) > touchSlop) {
                            // 竖直滑动让给 ScrollView，本行不再处理这次手势。
                            verticalPass = true;
                            return false;
                        }
                    }
                    if (horizontalDrag) {
                        float next = Math.max(-totalActionWidth(),
                                Math.min(0f, startTranslation + dx));
                        content.setTranslationX(next);
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (horizontalDrag) {
                        settle(content.getTranslationX() < -totalActionWidth() / 2f);
                        return true;
                    }
                    if (verticalPass) {
                        return false;
                    }
                    if (content.getTranslationX() < -1f) {
                        close();
                    } else {
                        content.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (horizontalDrag) {
                        settle(content.getTranslationX() < -totalActionWidth() / 2f);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
