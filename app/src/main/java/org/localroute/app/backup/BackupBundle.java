package org.localroute.app.backup;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份备份的内容：元信息 + 按段落存放的数据。
 *
 * <p>类本身不碰 Android API，收集与写回由 {@code BackupStore} 负责，
 * 因此格式、合并策略这些容易出错的部分可以完全用 JVM 用例覆盖。
 */
public final class BackupBundle {

    /** 备份文件的整体格式版本；字段含义变了才需要动它。 */
    public static final int FORMAT_VERSION = 1;
    public static final String FORMAT_ID = "unloop.backup";

    private final long createdAtMillis;
    private final String appVersionName;
    private final int appVersionCode;
    private final Map<String, BackupSection> sections = new LinkedHashMap<>();

    public BackupBundle(long createdAtMillis, String appVersionName, int appVersionCode) {
        this.createdAtMillis = createdAtMillis;
        this.appVersionName = appVersionName;
        this.appVersionCode = appVersionCode;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public String appVersionName() {
        return appVersionName;
    }

    public int appVersionCode() {
        return appVersionCode;
    }

    /** 同名段落以最后一次写入为准，重复收集不会产生两份。 */
    public void put(BackupSection section) {
        sections.put(section.name(), section);
    }

    public BackupSection get(String name) {
        return sections.get(name);
    }

    public boolean has(String name) {
        return sections.containsKey(name);
    }

    @NonNull
    public List<String> names() {
        return new ArrayList<>(sections.keySet());
    }

    @NonNull
    public List<BackupSection> sections() {
        return new ArrayList<>(sections.values());
    }

    /** 本版本不认识但备份里存在的段落名，写回时要原样保留，避免旧版覆盖新版后丢数据。 */
    @NonNull
    public List<String> unknownNames(List<String> knownNames) {
        List<String> unknown = new ArrayList<>();
        for (BackupSection section : sections.values()) {
            if (!knownNames.contains(section.name())) {
                unknown.add(section.name());
            }
        }
        return unknown;
    }
}
