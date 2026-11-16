package org.localroute.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import org.localroute.app.R;
import org.localroute.app.campus.CampusPoint;
import org.localroute.app.campus.CampusRoute;

/** Offline, north-up route thumbnail using the same geometry as the main map. */
public final class RoutePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    @Nullable private CampusRoute route;
    public RoutePreviewView(Context context) { this(context, null); }
    public RoutePreviewView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public void setRoute(@Nullable CampusRoute route) {
        if (this.route == route) return;
        this.route = route;
        setContentDescription(route == null ? getContext().getString(R.string.campus_route_none)
                : getContext().getString(R.string.route_preview_description, route.name()));
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.campus_mint_pale));
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 12 * density, 12 * density, paint);
        if (route == null || route.points().isEmpty()) return;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (CampusPoint p : route.points()) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        double scale = Math.min((getWidth() - 20 * density) / Math.max(1e-6, maxX - minX),
                (getHeight() - 20 * density) / Math.max(1e-6, maxY - minY));
        double ox = (getWidth() - (maxX - minX) * scale) / 2;
        double oy = (getHeight() - (maxY - minY) * scale) / 2;
        path.rewind();
        boolean first = true;
        for (CampusPoint p : route.points()) {
            float x = (float) (ox + (p.x - minX) * scale);
            float y = (float) (oy + (p.y - minY) * scale);
            if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
        }
        path.close();
        paint.setColor(getContext().getColor(R.color.campus_mint_dark));
        paint.setStrokeWidth(2.5f * density); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE); canvas.drawPath(path, paint);
        CampusPoint start = route.points().get(0);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle((float) (ox + (start.x - minX) * scale),
                (float) (oy + (start.y - minY) * scale), 3.5f * density, paint);
    }
}
