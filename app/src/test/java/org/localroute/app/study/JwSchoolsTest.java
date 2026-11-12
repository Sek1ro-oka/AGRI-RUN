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
