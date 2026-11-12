package org.localroute.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class UpdateCheckerTest {

    private static final String RELEASE_JSON = "{\n"
            + "  \"tag_name\": \"v0.7.0\",\n"
            + "  \"name\": \"Legion 0.7.0\",\n"
            + "  \"body\": \"# Legion 0.7.0\\n\\n## 修了什么\\n"
            + "| 编号 | 问题 |\\n| --- | --- |\\n| A | **作业** |\\n\\n"
            + "新增 `检查更新`。\\n[详情](https://example.com/release)\\n\",\n"
            + "  \"html_url\": \"https://example.com/release\",\n"
            + "  \"assets\": [\n"
            + "    {\"name\": \"location-probe-0.1.4-debug.apk\","
            + "     \"content_type\": \"application/vnd.android.package-archive\","
            + "     \"browser_download_url\": \"https://example.com/probe.apk\","
            + "     \"size\": 1000},\n"
            + "    {\"name\": \"route-workshop-0.7.0-release.apk\","
            + "     \"content_type\": \"application/vnd.android.package-archive\","
            + "     \"browser_download_url\": \"https://example.com/app.apk\","
            + "     \"size\": 123456},\n"
            + "    {\"name\": \"source.zip\", \"content_type\": \"application/zip\","
            + "     \"browser_download_url\": \"https://example.com/source.zip\","
            + "     \"size\": 2}\n"
            + "  ]\n"
            + "}";

    @Test
    public void parsesReleaseAndPicksMainApk() throws JSONException {
        UpdateChecker.Release release = UpdateChecker.parseRelease(RELEASE_JSON);
        assertEquals("0.7.0", release.versionName());
        assertEquals("Legion 0.7.0", release.title());
        assertEquals("https://example.com/release", release.pageUrl());
        assertEquals("https://example.com/app.apk", release.apkUrl());
        assertEquals("route-workshop-0.7.0-release.apk", release.apkName());
        assertEquals(123456, release.apkSize());
    }

    @Test
    public void notesStripMarkdownTablesAndMarkers() throws JSONException {
        UpdateChecker.Release release = UpdateChecker.parseRelease(RELEASE_JSON);
        String notes = release.notes();
        assertTrue(notes.contains("Legion 0.7.0"));
        assertTrue(notes.contains("修了什么"));
        assertTrue(notes.contains("作业"));
        assertTrue(notes.contains("检查更新"));
        assertTrue(notes.contains("详情"));
        assertFalse(notes.contains("#"));
        assertFalse(notes.contains("**"));
        assertFalse(notes.contains("|"));
        assertFalse(notes.contains("`"));
    }

    @Test
    public void releaseWithoutMainApkStillParses() throws JSONException {
        UpdateChecker.Release release = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.7.0\",\"assets\":[]}");
        assertNull(release.apkUrl());
        assertNull(release.apkName());
        assertEquals(UpdateChecker.RELEASES_PAGE, release.pageUrl());
    }

    @Test
    public void isNewerComparesNumericSegments() {
        assertTrue(UpdateChecker.isNewer("v0.7.0", "0.6.0"));
        assertTrue(UpdateChecker.isNewer("0.6.1", "0.6.0"));
        assertTrue(UpdateChecker.isNewer("0.6.0.1", "0.6.0"));
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("v0.6.0", "0.6.0"));
        assertFalse(UpdateChecker.isNewer("v0.5.9", "0.6.0"));
        assertFalse(UpdateChecker.isNewer("latest", "0.6.0"));
        assertFalse(UpdateChecker.isNewer(null, "0.6.0"));
    }

    @Test
    public void parseVersionIgnoresPrefixAndSuffix() {
        int[] values = UpdateChecker.parseVersion("release-v1.2.3-beta");
        assertNotNull(values);
        assertEquals(3, values.length);
        assertEquals(1, values[0]);
        assertEquals(2, values[1]);
        assertEquals(3, values[2]);
        assertNull(UpdateChecker.parseVersion("draft"));
    }
}
