package org.localroute.app.study;

import androidx.annotation.Nullable;

/**
 * 教务入口被学校侧拦截时的判定。
 *
 * <p>部分高校的教务系统只对校内网或学校 VPN 开放，网关会返回一张写有固定文案的
 * 拦截页，且状态码并不统一（实测石家庄铁道大学返回 HTTP 483 带「访问策略禁止」正文，
 * 中南林业科技大学的 webvpn 入口返回「未被授权的应用请求」）。这类页面在 WebView 看来
 * 是加载成功的，若不识别就只能告诉用户「页面不是教务系统」，把学校的限制说成了应用故障。
 *
 * <p>这里只做识别，不做任何绕过：应用不伪造来源 IP、不改包名或 UA 去骗过学校网关。
 *
 * <p>纯 Java，可在 JVM 单元测试中直接验证。
 */
public final class JwAccessDiagnosis {

    /** 学校网关按访问来源（IP、网段）拒绝，需要校内网或学校 VPN。 */
    public static final String ACCESS_BLOCKED = "ACCESS_BLOCKED";
    /** VPN/应用网关判定请求来源未被授权，多见于手动填写的 webvpn 入口。 */
    public static final String UNAUTHORIZED_APP = "UNAUTHORIZED_APP";

    private static final String[] BLOCKED_MARKERS = {
            "访问策略禁止",
            "没有权限访问相应的资源",
            "错误代码：403",
            "错误代码:403",
            "访问出错 - 403",
            "访问出错-403",
            "403 Forbidden",
    };

    private static final String[] UNAUTHORIZED_MARKERS = {
            "未被授权的应用请求",
            "未授权的应用请求",
            "未经授权的应用请求",
            "非法的应用请求",
    };

    private JwAccessDiagnosis() {
    }

    /** 拦截页文案命中时返回类型，普通教务页面返回 null。 */
    @Nullable
    public static String classifyText(@Nullable String pageText) {
        if (pageText == null || pageText.isEmpty()) {
            return null;
        }
        for (String marker : UNAUTHORIZED_MARKERS) {
            if (pageText.contains(marker)) {
                return UNAUTHORIZED_APP;
            }
        }
        for (String marker : BLOCKED_MARKERS) {
            if (pageText.contains(marker)) {
                return ACCESS_BLOCKED;
            }
        }
        return null;
    }

    /**
     * 主文档的 HTTP 状态码：401/403/406 与正方网关实测会用到的 483 视为来源被拒。
     * 429、5xx 属于临时故障，不在此判定，避免把「稍后再试」说成「学校限制你」。
     */
    @Nullable
    public static String classifyStatus(int httpStatus) {
        switch (httpStatus) {
            case 401:
            case 403:
            case 406:
            case 483:
                return ACCESS_BLOCKED;
            default:
                return null;
        }
    }

    /** 正文优先：网关常用 200 返回拦截页，也可能用非 403 的状态码返回同一张页。 */
    @Nullable
    public static String classify(int httpStatus, @Nullable String pageText) {
        String byText = classifyText(pageText);
        return byText != null ? byText : classifyStatus(httpStatus);
    }
}
