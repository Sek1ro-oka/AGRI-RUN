package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 调课与节假日记录的 JSON 编解码。与课表一样：整体读坏了当没有，坏一条丢一条。
 */
public final class DayAdjustmentCodec {

    /** 一次学期顶多几十天调课，留 120 条足够，再多说明是误操作堆出来的。 */
    public static final int MAX_ITEMS = 120;

    private DayAdjustmentCodec() {
    }

    @NonNull
    public static String encode(@NonNull List<DayAdjustment> items) {
        JSONArray array = new JSONArray();
        for (DayAdjustment item : keepRecent(items, LocalDate.now())) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // 只写字符串与整数，不会真失败；留着别打断整份记录。
            }
        }
        return array.toString();
    }

    @NonNull
    public static List<DayAdjustment> decode(@Nullable String json) {
        List<DayAdjustment> items = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                DayAdjustment item = DayAdjustment.fromJson(array.optJSONObject(i));
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (JSONException ignored) {
            // 存储损坏时视作没有调课，宁可多上一节也不误停一整天的课。
        }
        return items;
    }

    /**
     * 丢掉已经过去的记录并按日期排序后截到 {@link #MAX_ITEMS} 条。
     *
     * <p>过期记录留着没有任何作用——调课只影响当天及以后——但清掉它们能让存下来的
     * 一学期例外都看得清，也防止偏好文件无限膨胀。
     */
    @NonNull
    public static List<DayAdjustment> keepRecent(@NonNull List<DayAdjustment> items,
                                                 @NonNull LocalDate today) {
        List<DayAdjustment> kept = new ArrayList<>();
        for (DayAdjustment item : items) {
            if (!item.date().isBefore(today)) {
                kept.add(item);
            }
        }
        kept.sort((left, right) -> left.date().compareTo(right.date()));
        return kept.size() > MAX_ITEMS ? new ArrayList<>(kept.subList(0, MAX_ITEMS)) : kept;
    }
}
