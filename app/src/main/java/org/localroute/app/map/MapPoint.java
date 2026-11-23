package org.localroute.app.map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.localroute.core.GeoPoint;

import java.util.UUID;

/**
 * 一个收藏的点位：名称 + 坐标 + 收藏时间。
 *
 * <p>点位不可变，改名是换一个新对象；坐标由 {@link GeoPoint} 保证有限且在范围内，
 * 所以这里不再重复校验范围。名称留空时按坐标兜底，避免列表里出现空白行。
 */
public final class MapPoint {

    public static final int MAX_NAME_LENGTH = 40;
    public static final String FALLBACK_NAME = "未命名点位";

    private final String id;
    private final String name;
    private final GeoPoint point;
    private final long createdAtMillis;

    public MapPoint(@NonNull String id, @Nullable String name, @NonNull GeoPoint point,
                    long createdAtMillis) {
        String trimmedId = id == null ? "" : id.trim();
        if (trimmedId.isEmpty()) {
            throw new IllegalArgumentException("收藏点位缺少 id");
        }
        this.id = trimmedId;
        this.name = displayName(name);
        this.point = point;
        this.createdAtMillis = Math.max(0L, createdAtMillis);
    }

    /** 新收藏用随机 id；时间由调用方给出，方便测试里固定。 */
    @NonNull
    public static MapPoint create(@Nullable String name, @NonNull GeoPoint point,
                                  long createdAtMillis) {
        return new MapPoint(UUID.randomUUID().toString(), name, point, createdAtMillis);
    }

    /** 名称空值与超长的处理集中在这里，界面与反序列化都走同一条路。 */
    @NonNull
    public static String displayName(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            text = FALLBACK_NAME;
        }
        return text.length() <= MAX_NAME_LENGTH ? text : text.substring(0, MAX_NAME_LENGTH);
    }

    @NonNull
    public MapPoint withName(@Nullable String raw) {
        return new MapPoint(id, raw, point, createdAtMillis);
    }

    @NonNull
    public String id() {
        return id;
    }

    @NonNull
    public String name() {
        return name;
    }

    @NonNull
    public GeoPoint point() {
        return point;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }
}
