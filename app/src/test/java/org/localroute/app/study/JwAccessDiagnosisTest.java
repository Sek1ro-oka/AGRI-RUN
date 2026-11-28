package org.localroute.app.study;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 学校网关拦截页的识别，文案取自实测页面。 */
public class JwAccessDiagnosisTest {

    /** 按来源 IP/网段拦截的网关实测返回 HTTP 483 + 这段正文。 */
    private static final String GATEWAY_BLOCK_PAGE =
            "访问出错 - 403 出错啦！访问策略禁止您访问该网站，可能的原因为 (错误代码：403) "
                    + "您(IP：…)，没有权限访问相应的资源，请联系管理员以获取相应的访问权限";

    /** webvpn 网关判定请求来源未被授权时的实测拦截页。 */
    private static final String WEBVPN_DENY_PAGE = "出错了 未被授权的应用请求!";

    @Test
    public void detectsIpPolicyBlockPage() {
        assertEquals(JwAccessDiagnosis.ACCESS_BLOCKED,
                JwAccessDiagnosis.classifyText(GATEWAY_BLOCK_PAGE));
    }

    @Test
    public void detectsUnauthorizedAppPage() {
        assertEquals(JwAccessDiagnosis.UNAUTHORIZED_APP,
                JwAccessDiagnosis.classifyText(WEBVPN_DENY_PAGE));
    }

    @Test
    public void detectsHalfWidthErrorCode() {
        assertEquals(JwAccessDiagnosis.ACCESS_BLOCKED,
                JwAccessDiagnosis.classifyText("访问出错 错误代码:403"));
    }

    @Test
    public void ignoresNormalZhengfangPages() {
        assertNull(JwAccessDiagnosis.classifyText(null));
        assertNull(JwAccessDiagnosis.classifyText(""));
        assertNull(JwAccessDiagnosis.classifyText("正方教务系统 用户登录 学号 密码 验证码"));
        assertNull(JwAccessDiagnosis.classifyText(
                "课表查询 学期 2024-2025 周次 第 1 周 高等数学 1-2 节 第三教学楼"));
        // 「权限」二字单独出现在正常页面里不算拦截。
        assertNull(JwAccessDiagnosis.classifyText("没有选课权限的学期不会显示"));
    }

    @Test
    public void treatsSourceRejectingStatusCodesAsBlocked() {
        assertEquals(JwAccessDiagnosis.ACCESS_BLOCKED,
                JwAccessDiagnosis.classifyStatus(403));
        assertEquals(JwAccessDiagnosis.ACCESS_BLOCKED,
                JwAccessDiagnosis.classifyStatus(483));
        // 临时故障不能对用户说成「学校限制了你」。
        assertNull(JwAccessDiagnosis.classifyStatus(429));
        assertNull(JwAccessDiagnosis.classifyStatus(500));
        assertNull(JwAccessDiagnosis.classifyStatus(503));
        assertNull(JwAccessDiagnosis.classifyStatus(200));
    }

    @Test
    public void pageTextWinsOverStatus() {
        // 网关用 200 返回拦截页时仍要识别出来。
        assertEquals(JwAccessDiagnosis.UNAUTHORIZED_APP,
                JwAccessDiagnosis.classify(200, WEBVPN_DENY_PAGE));
        assertEquals(JwAccessDiagnosis.ACCESS_BLOCKED, JwAccessDiagnosis.classify(483, ""));
        assertNull(JwAccessDiagnosis.classify(200, "正方教务系统 用户登录"));
    }
}
