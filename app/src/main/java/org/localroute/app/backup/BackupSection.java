package org.localroute.app.backup;

import androidx.annotation.NonNull;

import org.json.JSONObject;

/**
 * 备份文件里的一段数据。payload 用 JSONObject 而不是序列化字符串保存，
 * 目的是让备份文件本身可读、可 diff——用户能用文本编辑器看出里面有什么。
 */
public final class BackupSection {

    private final String name;
    private final int schema;
    private final int count;
    private final JSONObject payload;

    public BackupSection(@NonNull String name, int schema, int count, @NonNull JSONObject payload) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        this.name = name;
        this.schema = schema;
        this.count = Math.max(0, count);
        this.payload = payload;
    }

    @NonNull
    public String name() {
        return name;
    }

    /** 条目数，只为导入预览与日志服务；不参与数据还原。 */
    public int count() {
        return count;
    }

    /** 该段自己的结构版本，与各段存储类的 CURRENT_VERSION 对应。 */
    public int schema() {
        return schema;
    }

    @NonNull
    public JSONObject payload() {
        return payload;
    }
}
