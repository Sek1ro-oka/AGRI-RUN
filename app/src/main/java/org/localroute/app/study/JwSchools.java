package org.localroute.app.study;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 教务系统学校入口：内置部分使用正方 jwglxt 的高校，并支持手动填写其它学校的
 * 教务登录地址。应用不再假定用户所在学校，只记住用户自己选择过的入口。
 *
 * <p>这里只保存学校名称与登录页地址，不涉及账号密码；地址解析保持纯 Java，
 * 便于 JVM 单测。
 */
public final class JwSchools {

    private static final String PREFS = "study_jw";
    private static final String KEY_NAME = "school_name";
    private static final String KEY_LOGIN_URL = "school_login_url";
    private static final String JWGLXT = "/jwglxt";
    private static final String LOGIN_SUFFIX = "/xtgl/login_slogin.html";

    /** 一所学校的教务入口。 */
    public static final class School {
        private final String name;
        private final String loginUrl;

        public School(@NonNull String name, @NonNull String loginUrl) {
            this.name = name;
            this.loginUrl = loginUrl;
        }

        @NonNull
        public String name() {
            return name;
        }

        @NonNull
        public String loginUrl() {
            return loginUrl;
        }
    }

    private static final List<School> BUILT_INS;

    static {
        List<School> schools = new ArrayList<>();
        // 内置入口只选用了可直接登录正方课表页的学校；找不到或地址失效时可手动填写。
        schools.add(new School("福建师范大学",
                "https://jwglxt.fjnu.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("广州南方学院",
                "https://jwxt.nfu.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("华南农业大学",
                "https://jwzf.scau.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("南通大学",
                "https://tdjw.ntu.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("青岛城市学院",
                "https://jwgl.qdc.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("绍兴大学",
                "https://jwxt.usx.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("苏州科技大学",
                "https://jwgl.usts.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("浙江工业大学",
                "http://www.gdjw.zjut.edu.cn/jwglxt/xtgl/login_slogin.html"));
        schools.add(new School("浙江农林大学",
                "http://jwxt.zafu.edu.cn/jwglxt/xtgl/login_slogin.html"));
        BUILT_INS = Collections.unmodifiableList(schools);
    }

    private JwSchools() {
    }

    @NonNull
    public static List<School> builtIns() {
        return BUILT_INS;
    }

    /** 读取用户上次选择的学校；没有选择过时返回 null。 */
    @Nullable
    public static School loadSaved(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        String name = prefs.getString(KEY_NAME, "");
        String url = prefs.getString(KEY_LOGIN_URL, "");
        if (name == null || name.trim().isEmpty()
                || url == null || url.trim().isEmpty()) {
            return null;
        }
        return new School(name.trim(), url.trim());
    }

    public static void save(@NonNull Context context, @NonNull School school) {
        prefs(context).edit()
                .putString(KEY_NAME, school.name())
                .putString(KEY_LOGIN_URL, school.loginUrl())
                .apply();
    }

    /**
     * 把用户填写的地址规范成正方登录页：缺协议时补 http://，只填域名或站点根时
     * 补全正方登录页路径；填的是正方站点里的其它页面时换成登录页。
     *
     * <p>不含 <code>/jwglxt</code> 且带具体路径的地址一律原样保留：webvpn 的加密入口、
     * 统一认证页等本来就不是正方路径，强行补 <code>/jwglxt/xtgl/login_slogin.html</code>
     * 只会把用户从浏览器里复制来的可用地址改坏。
     *
     * @return 无法解析、没有主机名或主机名不像学校域名时返回 null
     */
    @Nullable
    public static School normalizeCustom(@Nullable String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            return null;
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            input = "http://" + input;
        }
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException ignored) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || !isDomain(host)) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        int marker = path.indexOf(JWGLXT);
        if (marker >= 0 && path.indexOf("login_slogin", marker) < 0) {
            path = path.substring(0, marker + JWGLXT.length()) + LOGIN_SUFFIX;
        } else if (marker < 0 && isSiteRoot(path)) {
            path = JWGLXT + LOGIN_SUFFIX;
        }
        try {
            URI login = new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(),
                    path, null, null);
            return new School(host, login.toString());
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    /** 只填了域名或站点根，没有指向任何具体页面。 */
    private static boolean isSiteRoot(@NonNull String path) {
        return path.isEmpty() || path.equals("/");
    }

    /** 主机名至少要有一级域名，避免把误输入的单个单词当成学校地址。 */
    private static boolean isDomain(@NonNull String host) {
        if (host.startsWith(".") || host.endsWith(".")) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 学校课表接口的根地址（用于清理登录 Cookie），例如
     * https://host/jwglxt/。非正方入口（webvpn、统一认证页）没有 <code>/jwglxt</code>
     * 可退，按站点根清理，否则「重新登录」会去清一个用户从未访问过的路径。
     * 地址异常时退回登录页本身。
     */
    @NonNull
    public static String baseUrl(@NonNull School school) {
        try {
            URI uri = new URI(school.loginUrl());
            String path = uri.getPath() == null ? "" : uri.getPath();
            int marker = path.indexOf(JWGLXT);
            String basePath = marker < 0
                    ? "/" : path.substring(0, marker + JWGLXT.length()) + "/";
            return uri.getScheme() + "://" + uri.getAuthority() + basePath;
        } catch (URISyntaxException ignored) {
            return school.loginUrl();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
