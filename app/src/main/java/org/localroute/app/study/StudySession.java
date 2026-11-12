package org.localroute.app.study;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 学习通绑定结果：只保存 Cookie 与展示信息，不保存账号密码。
 */
public final class StudySession {

    private final String uid;
    private final String name;
    private final String cookie;
    private final long boundAt;

    public StudySession(@NonNull String uid, @Nullable String name,
                        @NonNull String cookie, long boundAt) {
        this.uid = uid;
        this.name = name == null ? "" : name;
        this.cookie = cookie;
        this.boundAt = boundAt;
    }

    @NonNull
    public String uid() {
        return uid;
    }

    @NonNull
    public String name() {
        return name;
    }

    @NonNull
    public String cookie() {
        return cookie;
    }

    public long boundAt() {
        return boundAt;
    }

    @NonNull
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return "学习通用户 " + uid;
    }
}
