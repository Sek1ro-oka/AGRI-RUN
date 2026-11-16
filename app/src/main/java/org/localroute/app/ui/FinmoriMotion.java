package org.localroute.app.ui;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Interruptible touch feedback for discrete controls. */
final class FinmoriMotion {
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.23f, 1f, .32f, 1f);
    private FinmoriMotion() { }

    static void press(View target) {
        target.setOnTouchListener((view, event) -> {
            if (!ValueAnimator.areAnimatorsEnabled()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(.98f).scaleY(.98f).setDuration(120).setInterpolator(EASE_OUT).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(EASE_OUT).start();
            }
            return false;
        });
    }

    static void reveal(View target) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        target.animate().cancel();
        target.setAlpha(.85f);
        target.animate().alpha(1f).setDuration(160).setInterpolator(EASE_OUT).start();
    }
}
