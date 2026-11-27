package org.localroute.app.study;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 学校选择与自定义教务地址的规范化。 */
public class JwSchoolsTest {

    @Test
    public void keepsCompleteLoginUrl() {
        JwSchools.School school = JwSchools.normalizeCustom(
                "https://jwxt.example.edu.cn/jwglxt/xtgl/login_slogin.html");
        assertNotNull(school);
        assertEquals("jwxt.example.edu.cn", school.name());
        assertEquals("https://jwxt.example.edu.cn/jwglxt/xtgl/login_slogin.html",
                school.loginUrl());
    }

    @Test
    public void completesMissingSchemeAndPath() {
        JwSchools.School school = JwSchools.normalizeCustom("jwxt.example.edu.cn");
        assertNotNull(school);
        assertEquals("http://jwxt.example.edu.cn/jwglxt/xtgl/login_slogin.html",
                school.loginUrl());

        JwSchools.School withSlash = JwSchools.normalizeCustom(
                "https://jwxt.example.edu.cn/jwglxt/");
        assertNotNull(withSlash);
        assertEquals("https://jwxt.example.edu.cn/jwglxt/xtgl/login_slogin.html",
                withSlash.loginUrl());
    }

    @Test
    public void keepsPortAndCustomRoot() {
        JwSchools.School school = JwSchools.normalizeCustom(
                "http://10.1.2.3:8080/school/jwglxt/xtgl/login_slogin.html");
        assertNotNull(school);
        assertEquals("10.1.2.3", school.name());
        assertEquals("http://10.1.2.3:8080/school/jwglxt/xtgl/login_slogin.html",
                school.loginUrl());
        assertEquals("http://10.1.2.3:8080/school/jwglxt/",
                JwSchools.baseUrl(school));
    }

    @Test
    public void keepsWebVpnStyleEntryUntouched() {
        // 实测中南林业科技大学的 webvpn 改写域名：不含 /jwglxt 的完整地址不能被改坏。
        JwSchools.School school = JwSchools.normalizeCustom(
                "https://https-jwxt-csuft-edu-cn-443.webvpn.csuft.edu.cn/xtgl/index_initMenu.html");
        assertNotNull(school);
        assertEquals("https-jwxt-csuft-edu-cn-443.webvpn.csuft.edu.cn", school.name());
        assertEquals("https://https-jwxt-csuft-edu-cn-443.webvpn.csuft.edu.cn"
                + "/xtgl/index_initMenu.html", school.loginUrl());
        // 非正方入口按站点根清理 Cookie，而不是补一个没访问过的 /jwglxt。
        assertEquals("https://https-jwxt-csuft-edu-cn-443.webvpn.csuft.edu.cn/",
                JwSchools.baseUrl(school));
    }

    @Test
    public void keepsEncryptedWebVpnPathUntouched() {
        JwSchools.School school = JwSchools.normalizeCustom(
                "https://webvpn.example.edu.cn/http/77726476706e69736865642f6a77676c2f6a77");
        assertNotNull(school);
        assertEquals("https://webvpn.example.edu.cn/http/77726476706e69736865642f6a77676c2f6a77",
                school.loginUrl());
    }

    @Test
    public void stillCompletesZhengfangLoginPath() {
        JwSchools.School portal = JwSchools.normalizeCustom("https://jwxt.example.edu.cn/jwglxt");
        assertNotNull(portal);
        assertEquals("https://jwxt.example.edu.cn/jwglxt/xtgl/login_slogin.html",
                portal.loginUrl());
        assertEquals("https://jwxt.example.edu.cn/jwglxt/", JwSchools.baseUrl(portal));
    }

    @Test
    public void rejectsInvalidAddress() {
        assertNull(JwSchools.normalizeCustom(null));
        assertNull(JwSchools.normalizeCustom(""));
        assertNull(JwSchools.normalizeCustom("http:///jwglxt"));
        assertNull(JwSchools.normalizeCustom("not a url"));
        // 单个单词会被补成 http://abc，但这不是能访问的学校域名。
        assertNull(JwSchools.normalizeCustom("abc"));
        assertNull(JwSchools.normalizeCustom("localhost"));
        assertNull(JwSchools.normalizeCustom("https://jwxt"));
    }

    @Test
    public void acceptsIpAddressAndPort() {
        JwSchools.School school = JwSchools.normalizeCustom("202.107.200.222");
        assertNotNull(school);
        assertEquals("202.107.200.222", school.name());
        assertEquals("http://202.107.200.222/jwglxt/xtgl/login_slogin.html",
                school.loginUrl());
    }

    @Test
    public void builtInSchoolsAreComplete() {
        assertFalse(JwSchools.builtIns().isEmpty());
        for (JwSchools.School school : JwSchools.builtIns()) {
            assertFalse(school.name().isEmpty());
            assertTrue(school.loginUrl().contains("/jwglxt/"));
            assertTrue(JwSchools.baseUrl(school).endsWith("/jwglxt/"));
        }
    }
}
