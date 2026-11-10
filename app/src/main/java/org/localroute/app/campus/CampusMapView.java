package org.localroute.app.campus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 校园快跑的自绘地图：底图、路线、进度、跑者与传送点。
 *
 * <p>坐标使用 360 × 620 的设计坐标（与原型一致），绘制时按比例缩放并居中。
 * 地图不是真实测绘结果，只用于演示虚拟定位跑圈。
 */
public final class CampusMapView extends View {

    public interface OnMapClickListener {
        /** 设计坐标下的一次点击。 */
        void onMapClick(CampusPoint point);
    }

    private static final String MAP_BG = "#EDF5EF";
    private static final String ROAD = "#FFFFFF";
    private static final String BUILDING = "#D8E5F3";
    private static final String BUILDING_LIGHT = "#E7EEF6";
    private static final String TRACK = "#DE8E7C";
    private static final String TRACK_INNER = "#9FCF92";
    private static final String TREE = "#9FCD97";
    private static final String INK = "#6E7F77";
    private static final int MINT = Color.rgb(18, 207, 169);
    private static final int MINT_DARK = Color.rgb(14, 158, 130);
    private static final int ORANGE = Color.rgb(242, 153, 74);
    private static final int RUNNER = Color.rgb(47, 126, 229);

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    @Nullable
    private CampusRoute route;
    @Nullable
    private CampusPoint markerOverride;
    @Nullable
    private CampusPoint teleportPoint;
    @Nullable
    private OnMapClickListener mapClickListener;
    private List<CampusPoint> draft = Collections.emptyList();
    private boolean drawMode;
    private boolean pulse;
    private double progressFraction;
    private float mapScale = 1f;
    private float offsetX;
    private float offsetY;

    public CampusMapView(Context context) {
        this(context, null);
    }

    public CampusMapView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CampusMapView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
    }

    public void setRoute(@Nullable CampusRoute value) {
        route = value;
        invalidate();
    }

    /** 0..1 的进度；超出范围按圈取模，整圈值（如 1.0）显示为满圈。 */
    public void setProgress(double fraction) {
        if (!Double.isFinite(fraction) || fraction <= 0d) {
            progressFraction = 0d;
            invalidate();
            return;
        }
        double value = fraction % 1d;
        if (value == 0d) {
            // 恰好完成整圈（或整数圈）：保留满圈进度，否则会被取模成 0。
            value = 1d;
        }
        progressFraction = value;
        invalidate();
    }

    /** 单点保持时用实际坐标覆盖按进度计算的跑者位置。 */
    public void setMarker(@Nullable CampusPoint point) {
        markerOverride = point;
        invalidate();
    }

    public void setTeleportPoint(@Nullable CampusPoint point) {
        teleportPoint = point;
        invalidate();
    }

    public void setDrawMode(boolean enabled, List<CampusPoint> points) {
        drawMode = enabled;
        draft = points == null ? Collections.emptyList() : new ArrayList<>(points);
        invalidate();
    }

    /** 跑动中让跑者标记轻微脉动；只有界面可见时才应打开。 */
    public void setPulse(boolean enabled) {
        if (pulse != enabled) {
            pulse = enabled;
            invalidate();
        }
    }

    public void setMapClickListener(@Nullable OnMapClickListener listener) {
        mapClickListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        canvas.drawColor(0xFFF3FBF9);
        mapScale = Math.min(width / (float) CampusGeo.MAP_WIDTH, height / (float) CampusGeo.MAP_HEIGHT);
        offsetX = (width - (float) CampusGeo.MAP_WIDTH * mapScale) / 2f;
        offsetY = (height - (float) CampusGeo.MAP_HEIGHT * mapScale) / 2f;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(mapScale, mapScale);
        drawMapBase(canvas);
        drawRoute(canvas);
        drawDraft(canvas);
        drawMarkers(canvas);
        canvas.restore();

        if (pulse) {
            postInvalidateDelayed(60L);
        }
    }

    // ---------------------------------------------------------------- 底图

    private void drawMapBase(Canvas canvas) {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor(MAP_BG));
        canvas.drawRect(0f, 0f, (float) CampusGeo.MAP_WIDTH, (float) CampusGeo.MAP_HEIGHT, fillPaint);

        fillPaint.setColor(0x8CE2EFE2);
        canvas.drawCircle(46f, 70f, 60f, fillPaint);
        fillPaint.setColor(0x80E2EFE2);
        canvas.drawCircle(320f, 580f, 70f, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(14f);
        canvas.drawLine(0f, 224f, 360f, 224f, strokePaint);
        canvas.drawLine(0f, 402f, 360f, 402f, strokePaint);
        strokePaint.setStrokeWidth(9f);
        strokePaint.setColor(0xCCFFFFFF);
        canvas.drawLine(28f, 0f, 28f, 620f, strokePaint);
        canvas.drawLine(334f, 0f, 334f, 620f, strokePaint);

        drawBuilding(canvas, 58f, 250f, 110f, 60f, BUILDING);
        drawBuilding(canvas, 192f, 250f, 110f, 60f, "#DDE8F5");
        drawBuilding(canvas, 58f, 330f, 110f, 52f, BUILDING_LIGHT);
        drawBuilding(canvas, 192f, 330f, 110f, 52f, BUILDING_LIGHT);

        drawMapText(canvas, "教学楼", 113f, 285f, 10f, INK);
        drawMapText(canvas, "图书馆", 247f, 285f, 10f, INK);
        drawMapText(canvas, "体育馆", 113f, 360f, 10f, INK);
        drawMapText(canvas, "宿舍区", 247f, 360f, 10f, INK);

        drawStadium(canvas, 180f, 120f, 126f, 76f, 114f, 66f, 102f, 56f, "B操");
        drawStadium(canvas, 180f, 490f, 104f, 62f, 92f, 52f, 80f, 42f, "A操");

        fillPaint.setColor(Color.parseColor(TREE));
        int[][] trees = {
                {46, 36, 7}, {320, 42, 6}, {318, 196, 6}, {42, 200, 5},
                {320, 442, 6}, {42, 448, 6}, {44, 586, 7}, {316, 596, 6}, {180, 588, 5}
        };
        for (int[] tree : trees) {
            canvas.drawCircle(tree[0], tree[1], tree[2], fillPaint);
        }
    }

    private void drawBuilding(Canvas canvas, float x, float y, float w, float h, String color) {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor(color));
        rect.set(x, y, x + w, y + h);
        canvas.drawRoundRect(rect, 10f, 10f, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setColor(Color.WHITE);
        canvas.drawRoundRect(rect, 10f, 10f, strokePaint);
    }

    private void drawStadium(Canvas canvas, float cx, float cy, float rx, float ry,
                             float strokeRx, float strokeRy, float innerRx, float innerRy, String label) {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor(TRACK));
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawOval(rect, fillPaint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.6f);
        strokePaint.setColor(0xBFFFFFFF);
        rect.set(cx - strokeRx, cy - strokeRy, cx + strokeRx, cy + strokeRy);
        canvas.drawOval(rect, strokePaint);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor(TRACK_INNER));
        rect.set(cx - innerRx, cy - innerRy, cx + innerRx, cy + innerRy);
        canvas.drawOval(rect, fillPaint);

        drawMapText(canvas, label, cx, cy + 4f, 11f, "#5F8F63");
    }

    private void drawMapText(Canvas canvas, String text, float x, float y, float size, String color) {
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.parseColor(color));
        textPaint.setTextSize(size);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, x, y, textPaint);
    }

    // ---------------------------------------------------------------- 路线

    private void drawRoute(Canvas canvas) {
        if (route == null || route.points().isEmpty()) {
            return;
        }
        List<CampusPoint> points = route.points();
        trace(canvas, points, true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(4f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(0xCC12CFA9);
        strokePaint.setPathEffect(new DashPathEffect(new float[]{1f, 8f}, 0f));
        canvas.drawPath(path, strokePaint);
        strokePaint.setPathEffect(null);

        if (!drawMode && progressFraction > 0d) {
            List<CampusPoint> progress = CampusGeo.partialLoop(points, progressFraction);
            if (progress.size() > 1) {
                trace(canvas, progress, false);
                strokePaint.setStrokeWidth(5f);
                strokePaint.setColor(MINT);
                canvas.drawPath(path, strokePaint);

                // 已走过的部分再叠一层橙色点线：开启路径浮动时能看出轨迹偏移。
                strokePaint.setStrokeWidth(2.8f);
                strokePaint.setColor(0xF2F2994A);
                strokePaint.setPathEffect(new DashPathEffect(new float[]{1f, 5f}, 0f));
                canvas.drawPath(path, strokePaint);
                strokePaint.setPathEffect(null);
            }
        }
    }

    private void drawDraft(Canvas canvas) {
        if (!drawMode || draft.isEmpty()) {
            return;
        }
        if (draft.size() >= 2) {
            trace(canvas, draft, false);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2.4f);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setColor(0xE612CFA9);
            strokePaint.setPathEffect(new DashPathEffect(new float[]{5f, 5f}, 0f));
            canvas.drawPath(path, strokePaint);
            strokePaint.setPathEffect(null);
        }
        if (draft.size() >= 3) {
            CampusPoint first = draft.get(0);
            CampusPoint last = draft.get(draft.size() - 1);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setColor(0xFF8FC8B8);
            strokePaint.setPathEffect(new DashPathEffect(new float[]{4f, 5f}, 0f));
            canvas.drawLine(f(last.x), f(last.y), f(first.x), f(first.y), strokePaint);
            strokePaint.setPathEffect(null);
        }
        for (int i = 0; i < draft.size(); i++) {
            CampusPoint point = draft.get(i);
            boolean isFirst = i == 0;
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(isFirst ? MINT : Color.WHITE);
            canvas.drawCircle(f(point.x), f(point.y), isFirst ? 7f : 6f, fillPaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2f);
            strokePaint.setColor(isFirst ? Color.WHITE : MINT);
            canvas.drawCircle(f(point.x), f(point.y), isFirst ? 7f : 6f, strokePaint);
            drawMapText(canvas, String.valueOf(i + 1), f(point.x), f(point.y) + 2.8f, 7f,
                    isFirst ? "#FFFFFF" : "#0E9E82");
        }
    }

    private void drawMarkers(Canvas canvas) {
        if (route == null || route.points().isEmpty() || drawMode) {
            return;
        }
        CampusPoint start = route.startPoint();
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(MINT);
        canvas.drawCircle(f(start.x), f(start.y), 5f, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.5f);
        strokePaint.setColor(Color.WHITE);
        canvas.drawCircle(f(start.x), f(start.y), 5f, strokePaint);

        if (teleportPoint != null) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0x24F2994A);
            canvas.drawCircle(f(teleportPoint.x), f(teleportPoint.y), 15f, fillPaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.8f);
            strokePaint.setColor(ORANGE);
            canvas.drawCircle(f(teleportPoint.x), f(teleportPoint.y), 10f, strokePaint);
        }

        CampusPoint marker = markerOverride;
        if (marker == null) {
            marker = CampusGeo.pointAtFraction(route.points(), progressFraction);
        }
        if (marker == null) {
            return;
        }
        float pulseScale = pulse
                ? (float) (1d + 0.22d * Math.sin(System.currentTimeMillis() / 280d))
                : 1f;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x2E2F7EE5);
        canvas.drawCircle(f(marker.x), f(marker.y), 12f * pulseScale, fillPaint);
        fillPaint.setColor(RUNNER);
        canvas.drawCircle(f(marker.x), f(marker.y), 6.5f, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.5f);
        strokePaint.setColor(Color.WHITE);
        canvas.drawCircle(f(marker.x), f(marker.y), 6.5f, strokePaint);
    }

    private void trace(Canvas canvas, List<CampusPoint> points, boolean close) {
        if (points.isEmpty()) {
            return;
        }
        path.reset();
        path.moveTo(f(points.get(0).x), f(points.get(0).y));
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(f(points.get(i).x), f(points.get(i).y));
        }
        if (close) {
            path.close();
        }
    }

    private static float f(double value) {
        return (float) value;
    }

    // ---------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && mapClickListener != null
                && !isClickableEventCancelled(event)) {
            float x = (event.getX() - offsetX) / mapScale;
            float y = (event.getY() - offsetY) / mapScale;
            if (x >= 0f && x <= CampusGeo.MAP_WIDTH && y >= 0f && y <= CampusGeo.MAP_HEIGHT) {
                performClick();
                mapClickListener.onMapClick(new CampusPoint(x, y));
                return true;
            }
        }
        return true;
    }

    private boolean isClickableEventCancelled(MotionEvent event) {
        return event.getEventTime() - event.getDownTime() > 1200L;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 当前缩放比例（调试/测试用）。 */
    public float mapScale() {
        return mapScale;
    }

    /** 供无障碍/截图调试：当前地图中心的设计坐标。 */
    public String describeCenter() {
        return String.format(Locale.US, "%.0f,%.0f", CampusGeo.CENTER_X, CampusGeo.CENTER_Y);
    }
}
