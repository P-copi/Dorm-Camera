package com.pcopi.dormcamera;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists GPS destinations received from LINE without requiring a photo. */
public final class SavedGpsPlaces {
    private static final String PREFS = "smart_gps_link_locations_v1";
    private static final String ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;
    private SavedGpsPlaces() {}

    public static final class Entry {
        @NonNull public final String id;
        @NonNull public final String title;
        public final double lat;
        public final double lon;
        public final long addedAt;

        Entry(String id, String title, double lat, double lon, long addedAt) {
            this.id = id;
            this.title = title;
            this.lat = lat;
            this.lon = lon;
            this.addedAt = addedAt;
        }
    }

    @NonNull
    public static synchronized List<Entry> all(@NonNull Context context) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ENTRIES, "[]");
        List<Entry> output = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(stored);
            for (int i = 0; i < values.length() && output.size() < MAX_ENTRIES; i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                double lat = value.optDouble("lat", Double.NaN);
                double lon = value.optDouble("lon", Double.NaN);
                if (!SmartGpsLink.valid(lat, lon)) continue;
                String id = value.optString("id", "");
                if (id.isEmpty()) continue;
                output.add(new Entry(id,
                        safeName(value.optString("name", "ตำแหน่งจาก LINE")),
                        lat, lon, value.optLong("date", 0)));
            }
        } catch (Exception ignored) {}
        return Collections.unmodifiableList(output);
    }

    @NonNull
    public static synchronized Entry save(@NonNull Context context, String title,
            double lat, double lon) {
        if (!SmartGpsLink.valid(lat, lon)) {
            throw new IllegalArgumentException("GPS ไม่ถูกต้อง");
        }
        String cleanName = safeName(title);
        List<Entry> current = new ArrayList<>(all(context));
        // Prefer updating an existing place rather than creating duplicates.
        String existingId = null;
        for (Entry item : current) {
            if (Math.abs(item.lat - lat) < 0.00003
                    && Math.abs(item.lon - lon) < 0.00003) {
                existingId = item.id;
                break;
            }
        }
        if (existingId != null) {
            String replaceId = existingId;
            current.removeIf(x -> x.id.equals(replaceId));
        }
        long now = System.currentTimeMillis();
        Entry entry = new Entry(existingId == null
                ? now + "_" + Long.toHexString(System.nanoTime()) : existingId,
                cleanName, lat, lon, now);
        current.add(0, entry);
        while (current.size() > MAX_ENTRIES) current.remove(current.size() - 1);
        write(context, current);
        return entry;
    }

    public static synchronized void delete(@NonNull Context context, @NonNull String id) {
        List<Entry> current = new ArrayList<>(all(context));
        current.removeIf(x -> x.id.equals(id));
        write(context, current);
    }

    private static void write(Context context, List<Entry> entries) {
        JSONArray values = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("name", entry.title);
                item.put("lat", entry.lat);
                item.put("lon", entry.lon);
                item.put("date", entry.addedAt);
                values.put(item);
            } catch (Exception ignored) {}
        }
        SharedPreferences settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!settings.edit().putString(ENTRIES, values.toString()).commit()) {
            throw new IllegalStateException("บันทึกตำแหน่งลงเครื่องไม่สำเร็จ");
        }
    }

    private static String safeName(String title) {
        String clean = title == null ? "" : title.replace('\n', ' ')
                .replace('\r', ' ').trim();
        if (clean.isEmpty()) clean = "ตำแหน่งจาก LINE";
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }
}
