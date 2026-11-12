package org.localroute.app.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 从项目的 GitHub Releases 读取最新版本；只做版本比较与信息整理，不下载文件。
 *
 * <p>仓库尚未发布新版本时返回 404，这里转换为 {@link NoReleaseException}，
 * 由界面显示「还没有发布可更新的版本」而不是网络错误。
 */
public final class UpdateChecker {

    /** 公开仓库的最新 Release；不需要登录，受 GitHub 匿名请求频率限制。 */
    public static final String RELEASES_API =
            "https://api.github.com/repos/Sek1ro-oka/AGRI-RUN/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/Sek1ro-oka/AGRI-RUN/releases";

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String APK_PREFIX = "route-workshop-";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_NOTES_CHARS = 700;

    private UpdateChecker() {
    }

    /** 没有找到任何 Release。 */
    public static final class NoReleaseException extends IOException {
        public NoReleaseException() {
            super("no release");
        }
    }

    /** 一次 Release 的展示与下载信息。 */
    public static final class Release {
        private final String versionName;
        private final String title;
        private final String notes;
        private final String pageUrl;
        @Nullable
        private final String apkUrl;
        @Nullable
        private final String apkName;
        private final long apkSize;

        Release(@NonNull String versionName, @NonNull String title, @NonNull String notes,
                @NonNull String pageUrl, @Nullable String apkUrl, @Nullable String apkName,
                long apkSize) {
            this.versionName = versionName;
            this.title = title;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.apkSize = apkSize;
        }

        @NonNull
        public String versionName() {
            return versionName;
        }

        @NonNull
        public String title() {
            return title;
        }

        @NonNull
        public String notes() {
            return notes;
        }

        @NonNull
        public String pageUrl() {
            return pageUrl;
        }

        @Nullable
        public String apkUrl() {
            return apkUrl;
        }

        @Nullable
        public String apkName() {
            return apkName;
        }

        public long apkSize() {
            return apkSize;
        }
    }

    @NonNull
    public static Release fetchLatest() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Legion-Android-Updater");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NoReleaseException();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            String body = readAll(connection.getInputStream());
            return parseRelease(body);
        } catch (JSONException error) {
            throw new IOException("更新信息格式异常", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @NonNull
    static Release parseRelease(@NonNull String body) throws JSONException {
        JSONObject json = new JSONObject(body);
        String tag = json.optString("tag_name", "").trim();
        if (tag.isEmpty()) {
            throw new JSONException("missing tag_name");
        }
        String version = tag.startsWith("v") || tag.startsWith("V")
                ? tag.substring(1) : tag;
        String title = json.optString("name", "").trim();
        if (title.isEmpty()) {
            title = "Legion " + version;
        }
        String notes = cleanNotes(json.optString("body", ""));
        String pageUrl = json.optString("html_url", RELEASES_PAGE);
        if (pageUrl.isEmpty()) {
            pageUrl = RELEASES_PAGE;
        }

        String apkUrl = null;
        String apkName = null;
        long apkSize = 0;
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            JSONObject best = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !isRouteWorkshopApk(asset)) {
                    continue;
                }
                String name = asset.optString("name", "");
                if (best == null || preferAsset(name, best.optString("name", ""))) {
                    best = asset;
                }
            }
            if (best != null) {
                apkUrl = best.optString("browser_download_url", "");
                apkName = best.optString("name", "");
                apkSize = best.optLong("size", 0);
                if (apkUrl.isEmpty()) {
                    apkUrl = null;
                    apkName = null;
                    apkSize = 0;
                }
            }
        }
        return new Release(version, title, notes, pageUrl, apkUrl, apkName, apkSize);
    }

    /** Release 里同时会有定位探针 APK，只接受主程序的附件。 */
    private static boolean isRouteWorkshopApk(@NonNull JSONObject asset) {
        String name = asset.optString("name", "");
        String type = asset.optString("content_type", "");
        boolean apk = APK_MIME.equalsIgnoreCase(type)
                || name.toLowerCase(Locale.ROOT).endsWith(".apk");
        return apk && name.toLowerCase(Locale.ROOT).startsWith(APK_PREFIX);
    }

    /** 附件里若同时有多份主程序包，优先包含 release 字样的那一份。 */
    private static boolean preferAsset(@NonNull String candidate, @NonNull String current) {
        boolean candidateRelease = candidate.toLowerCase(Locale.ROOT).contains("release");
        boolean currentRelease = current.toLowerCase(Locale.ROOT).contains("release");
        return candidateRelease && !currentRelease;
    }

    /** 语义化比较版本号；无法解析时按「不更新」处理，避免误报新版本。 */
    public static boolean isNewer(@Nullable String remote, @Nullable String local) {
        int[] remoteParts = parseVersion(remote);
        int[] localParts = parseVersion(local);
        if (remoteParts == null || localParts == null) {
            return false;
        }
        int length = Math.max(remoteParts.length, localParts.length);
        for (int i = 0; i < length; i++) {
            int remoteValue = i < remoteParts.length ? remoteParts[i] : 0;
            int localValue = i < localParts.length ? localParts[i] : 0;
            if (remoteValue != localValue) {
                return remoteValue > localValue;
            }
        }
        return false;
    }

    @Nullable
    static int[] parseVersion(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        int start = 0;
        while (start < text.length() && !Character.isDigit(text.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < text.length()
                && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
            end++;
        }
        if (start >= end) {
            return null;
        }
        String[] parts = text.substring(start, end).split("\\.");
        int[] values = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                values[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return values;
    }

    /** 把 Release 的 Markdown 压成适合对话框阅读的纯文本。 */
    @NonNull
    static String cleanNotes(@Nullable String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String previous = "";
        for (String rawLine : markdown.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("|")) {
                if (line.matches("^[-\\s|:]+$")) {
                    continue;
                }
                // 发布说明常用表格列改动；把一行表格压成「A · B · C」保留信息。
                StringBuilder row = new StringBuilder();
                for (String cell : line.split("\\|")) {
                    String value = cell.trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (row.length() > 0) {
                        row.append(" · ");
                    }
                    row.append(value);
                }
                line = row.toString();
            }
            if (line.matches("^[-\\s|:]+$")) {
                continue;
            }
            line = line.replaceAll("^#{1,6}\\s*", "")
                    .replace("**", "")
                    .replace("`", "")
                    .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                    .trim();
            if (line.isEmpty() || line.equals(previous)) {
                continue;
            }
            builder.append(line).append('\n');
            previous = line;
            if (builder.length() >= MAX_NOTES_CHARS) {
                break;
            }
        }
        String text = builder.toString().trim();
        if (text.length() > MAX_NOTES_CHARS) {
            text = text.substring(0, MAX_NOTES_CHARS).trim() + "…";
        }
        return text;
    }

    @NonNull
    private static String readAll(@NonNull InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
