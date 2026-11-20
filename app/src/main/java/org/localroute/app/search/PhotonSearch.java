package org.localroute.app.search;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.core.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Photon（OSM）地点搜索：不需要 API Key，坐标是 WGS-84。
 *
 * <p>只做查询与 JSON 解析；网络调用由界面放到后台线程执行。公共示例服务有频率限制，
 * 这里限制返回条数并设置明确的 User-Agent，不用于批量抓取。
 */
public final class PhotonSearch {

    private static final String ENDPOINT = "https://photon.komoot.io/api/";
    private static final String USER_AGENT = "Legion-VirtualLocation/0.9 (Android; personal app)";
    private static final int LIMIT = 4;
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private PhotonSearch() {
    }

    /** 一条搜索结果。 */
    public static final class Place {
        private final String name;
        private final String address;
        private final double latitude;
        private final double longitude;

        Place(String name, String address, double latitude, double longitude) {
            this.name = name;
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String name() {
            return name;
        }

        public String address() {
            return address;
        }

        public double latitude() {
            return latitude;
        }

        public double longitude() {
            return longitude;
        }

        public GeoPoint point() {
            return new GeoPoint(latitude, longitude);
        }
    }

    public static List<Place> search(String query) throws IOException, JSONException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        URL url = new URL(ENDPOINT + "?limit=" + LIMIT + "&q=" + encoded);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream()) {
                return parse(readAll(input));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 解析 Photon 的 GeoJSON；缺名字、缺坐标或坐标越界的条目直接跳过。 */
    public static List<Place> parse(String body) throws JSONException {
        List<Place> places = new ArrayList<>();
        JSONObject root = new JSONObject(body);
        JSONArray features = root.optJSONArray("features");
        if (features == null) {
            return places;
        }
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) {
                continue;
            }
            JSONObject geometry = feature.optJSONObject("geometry");
            JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coordinates == null || coordinates.length() < 2) {
                continue;
            }
            double longitude = coordinates.optDouble(0, Double.NaN);
            double latitude = coordinates.optDouble(1, Double.NaN);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90d || latitude > 90d
                    || longitude < -180d || longitude > 180d) {
                continue;
            }
            JSONObject properties = feature.optJSONObject("properties");
            if (properties == null) {
                continue;
            }
            String name = resolveName(properties);
            if (name.isEmpty()) {
                continue;
            }
            places.add(new Place(name, resolveAddress(properties), latitude, longitude));
        }
        return places;
    }

    private static String resolveName(JSONObject properties) {
        String name = text(properties, "name");
        if (!name.isEmpty()) {
            return name;
        }
        String street = joinStreet(properties);
        if (!street.isEmpty()) {
            return street;
        }
        String district = text(properties, "district");
        return district.isEmpty() ? text(properties, "city") : district;
    }

    private static String resolveAddress(JSONObject properties) {
        List<String> parts = new ArrayList<>();
        addPart(parts, joinStreet(properties));
        addPart(parts, text(properties, "district"));
        addPart(parts, text(properties, "city"));
        addPart(parts, text(properties, "state"));
        return String.join(" · ", parts);
    }

    private static String joinStreet(JSONObject properties) {
        String street = text(properties, "street");
        String number = text(properties, "housenumber");
        if (street.isEmpty()) {
            return number;
        }
        if (number.isEmpty()) {
            return street;
        }
        return String.format(Locale.US, "%s%s", street, number);
    }

    private static void addPart(List<String> parts, String value) {
        if (!value.isEmpty() && !parts.contains(value)) {
            parts.add(value);
        }
    }

    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? "" : value;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (output.size() > MAX_RESPONSE_BYTES) {
                throw new IOException("response too large");
            }
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
