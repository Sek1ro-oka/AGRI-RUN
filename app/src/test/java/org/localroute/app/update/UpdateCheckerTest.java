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
            + "     \"digest\": \"sha256:2c305d0cc54a68abf216100f56afd3ae10c3d11c9cca957cf0327d7c35dbbf98\","
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
        assertEquals("2c305d0cc54a68abf216100f56afd3ae10c3d11c9cca957cf0327d7c35dbbf98",
                release.apkSha256());
        assertEquals(123456, release.apkSize());
    }

    @Test
    public void releaseWithoutDigestHasNoChecksum() throws JSONException {
        UpdateChecker.Release release = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.7.0\",\"assets\":[{\"name\":"
                        + "\"route-workshop-0.7.0-release.apk\","
                        + "\"browser_download_url\":\"https://example.com/app.apk\"}]}");
        assertNull(release.apkSha256());
    }

    @Test
    public void mirrorMetadataRewritesPageUrl() throws JSONException {
        String mirrored = "https://gh-proxy.com/" + UpdateChecker.RELEASES_API;
        UpdateChecker.Release release = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.7.0\",\"html_url\":"
                        + "\"https://github.com/o/r/releases/tag/v0.7.0\"}", mirrored);
        assertEquals("https://gh-proxy.com/https://github.com/o/r/releases/tag/v0.7.0",
                release.pageUrl());
        assertEquals(mirrored, release.source());
    }

    @Test
    public void apkSourcesAddMirrorsForGitHubOnly() throws JSONException {
        UpdateChecker.Release local = UpdateChecker.parseRelease(RELEASE_JSON);
        assertEquals(1, UpdateChecker.apkSources(local).size());

        UpdateChecker.Release github = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.7.0\",\"assets\":[{\"name\":"
                        + "\"route-workshop-0.7.0-release.apk\","
                        + "\"browser_download_url\":\"https://github.com/o/r/releases/"
                        + "download/v0.7.0/route-workshop-0.7.0-release.apk\"}]}");
        java.util.List<String> sources = UpdateChecker.apkSources(github);
        assertTrue(sources.size() > 1);
        assertEquals("https://github.com/o/r/releases/download/v0.7.0/"
                + "route-workshop-0.7.0-release.apk", sources.get(0));
        for (int i = 1; i < sources.size(); i++) {
            assertTrue(sources.get(i).startsWith("https://"));
            assertTrue(sources.get(i).endsWith(sources.get(0)));
        }
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
