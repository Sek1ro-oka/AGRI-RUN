package org.localroute.app.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.content.Context;
import android.content.SharedPreferences;

import org.localroute.core.GeoPoint;

import java.util.List;

/**
 * 收藏点位的新点（{@code map} 存储文件里的 {@code favorites} 键）。
 *
 * <p>与底图图层放在同一个文件里，但键名不同：图层是界面状态，收藏是用户数据，
 * 备份清单 {@code BackupCatalog} 按键取值，两者互不影响。
 */
public final class MapPointStore {

    private static final String PREFS = "map";
    private static final String KEY_FAVORITES = "favorites";
    /** 两点间小于这个距离就算「已经收藏过」，避免连续两次收藏同一个点。 */
    public static final double DUPLICATE_METERS = 5d;

    private MapPointStore() {
    }

    @NonNull
    public static List<MapPoint> load(Context context) {
        return MapPointCodec.decode(prefs(context).getString(KEY_FAVORITES, ""));
    }

    public static void save(Context context, @NonNull List<MapPoint> points) {
        prefs(context).edit().putString(KEY_FAVORITES, MapPointCodec.encode(points)).apply();
    }

    /** 收藏结果的三种情况，界面据此选文案。 */
    public enum AddResult {
        SAVED,
        DUPLICATE,
        LIMIT
    }

    /** 已经收藏过这个坐标时返回原条目，调用方据此提示而不是再存一条。 */
    @Nullable
    public static MapPoint findNear(@NonNull List<MapPoint> points, @NonNull GeoPoint target) {
        for (MapPoint point : points) {
            if (point.point().isNear(target, DUPLICATE_METERS)) {
                return point;
            }
        }
        return null;
    }

    /** 追加一条收藏。重复与超限分开返回，免得界面上「没存进去」说不清原因。 */
    @NonNull
    public static AddResult add(@NonNull Context context, @Nullable String name,
                                @NonNull GeoPoint point, long createdAtMillis) {
        List<MapPoint> points = load(context);
        if (findNear(points, point) != null) {
            return AddResult.DUPLICATE;
        }
        if (points.size() >= MapPointCodec.MAX_POINTS) {
            return AddResult.LIMIT;
        }
        points.add(MapPoint.create(name, point, createdAtMillis));
        save(context, points);
        return AddResult.SAVED;
    }

    public static void rename(@NonNull Context context, @NonNull String id, @Nullable String name) {
        List<MapPoint> points = load(context);
        if (replace(points, id, name)) {
            save(context, points);
        }
    }

    public static void remove(@NonNull Context context, @NonNull String id) {
        List<MapPoint> points = load(context);
        boolean changed = false;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id().equals(id)) {
                points.remove(i);
                changed = true;
                break;
            }
        }
        if (changed) {
            save(context, points);
        }
    }

    private static boolean replace(@NonNull List<MapPoint> points, @NonNull String id,
                                   @Nullable String name) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id().equals(id)) {
                points.set(i, points.get(i).withName(name));
                return true;
            }
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
