package com.pcopi.dormcamera;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Maps URLs which work without installing Dorm Camera.
 * Incoming URLs are decoded locally; no network call or short-link redirect is needed.
 * In particular we NEVER register a generic Google Maps HTTPS app-link handler.
 */
public final class SmartGpsLink {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern COORD = Pattern.compile(
            "^\\s*([-+]?\\d{1,2}(?:\\.\\d+)?)\\s*[,，]\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*$");
    private static final Pattern COORD_IN_TEXT = Pattern.compile(
            "(?<![\\d.])([-+]?\\d{1,2}(?:\\.\\d+)?)\\s*[,，]\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)(?![\\d.])");
    private static final Pattern LABEL = Pattern.compile(
            "(?:^|\\n)\\s*(?:📍\\s*)?(?:สถานที่|ชื่อสถานที่|place|location)\\s*[:：]\\s*([^\\n\\r]+)",
            Pattern.CASE_INSENSITIVE);

    private SmartGpsLink() {}

    public static final class Parsed {
        public final double latitude;
        public final double longitude;
        @NonNull public final String title;
        @NonNull public final String navigationUrl;
        @Nullable public final String receivedUrl;

        Parsed(double lat, double lon, String title, @Nullable String receivedUrl) {
            latitude = lat;
            longitude = lon;
            this.title = safeTitle(title);
            navigationUrl = navigationUrl(lat, lon);
            this.receivedUrl = receivedUrl;
        }
    }

    @NonNull
    public static String navigationUrl(double lat, double lon) {
        if (!valid(lat, lon)) throw new IllegalArgumentException("Invalid GPS coordinates");
        String destination = String.format(Locale.US, "%.7f%%2C%.7f", lat, lon);
        return "https://www.google.com/maps/dir/?api=1&destination="
                + destination + "&travelmode=driving&dir_action=navigate";
    }

    @NonNull
    public static String shareText(String name, double lat, double lon) {
        return "📍 สถานที่: " + safeTitle(name)
                + "\nพิกัด: " + String.format(Locale.US, "%.7f,%.7f", lat, lon)
                + "\n🧭 เปิด Google Maps / นำทาง:\n" + navigationUrl(lat, lon)
                + "\n\nผู้รับเปิดลิงก์นี้เพื่อดูเส้นทางได้ทันที"
                + "\nหากมี Dorm Camera สามารถแชร์ข้อความนี้เข้าแอปเพื่อบันทึกสถานที่ได้";
    }

    @Nullable
    public static Parsed parse(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String title = extractTitle(text);
        Matcher matches = URL.matcher(text);
        while (matches.find()) {
            String url = stripPunctuation(matches.group());
            try {
                Uri uri = Uri.parse(url);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        && !"http".equalsIgnoreCase(uri.getScheme())) continue;
                String host = uri.getHost();
                if (host == null) continue;
                String lowerHost = host.toLowerCase(Locale.US);
                if (!("google.com".equals(lowerHost)
                        || lowerHost.endsWith(".google.com")
                        || "google.co.th".equals(lowerHost)
                        || lowerHost.endsWith(".google.co.th"))) continue;
                // Short links such as maps.app.goo.gl require a network redirect;
                // do not guess coordinates from the short URL.
                String path = uri.getPath();
                if (path == null) path = "";
                if (!path.startsWith("/maps") && !"maps.google.com".equals(lowerHost)) continue;

                String[] parameters = {"destination", "query", "q"};
                for (String key : parameters) {
                    String value = uri.getQueryParameter(key);
                    double[] coord = readCoords(value);
                    if (coord != null) return new Parsed(coord[0], coord[1], title, url);
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed URLs, continue with explicit coordinates.
            }
        }

        // The outgoing LINE message intentionally includes the raw coordinates,
        // so it remains importable even when the Maps link is reformatted.
        Matcher coord = COORD_IN_TEXT.matcher(text);
        while (coord.find()) {
            double[] point = readCoords(coord.group());
            if (point != null) return new Parsed(point[0], point[1], title, null);
        }
        return null;
    }

    @Nullable
    private static double[] readCoords(@Nullable String raw) {
        if (raw == null) return null;
        Matcher m = COORD.matcher(raw.trim());
        if (!m.matches()) return null;
        try {
            double lat = Double.parseDouble(m.group(1));
            double lon = Double.parseDouble(m.group(2));
            return valid(lat, lon) ? new double[]{lat, lon} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NonNull
    private static String extractTitle(String text) {
        Matcher m = LABEL.matcher(text);
        if (m.find()) return safeTitle(m.group(1));
        String[] lines = text.trim().split("\\r?\\n");
        if (lines.length > 0) {
            String line = lines[0].trim();
            if (!line.startsWith("http") && !line.contains("พิกัด:")
                    && !line.startsWith("🧭") && !line.isEmpty()) return safeTitle(line);
        }
        return "ตำแหน่งจาก LINE";
    }

    @NonNull
    private static String safeTitle(String name) {
        if (name == null) return "ตำแหน่งจาก LINE";
        String single = name.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.isEmpty()) return "ตำแหน่งจาก LINE";
        return single.length() <= 80 ? single : single.substring(0, 80);
    }

    private static String stripPunctuation(String url) {
        while (!url.isEmpty() && (url.endsWith(".") || url.endsWith(",")
                || url.endsWith(")") || url.endsWith("]")
                || url.endsWith("。") || url.endsWith("，"))) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
}
