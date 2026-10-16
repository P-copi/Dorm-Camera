package com.pcopi.dormcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Creates a temporary share copy with location metadata removed. */
public final class ShareImageSanitizer {
    private ShareImageSanitizer() {}

    @NonNull
    public static Uri createGpsFreeShareUri(
            @NonNull Context context,
            @NonNull Uri source,
            @NonNull String displayName) throws IOException {

        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(source);
        String extension = extensionFrom(displayName, mime);
        String base = cleanBaseName(displayName);

        File dir = new File(context.getCacheDir(), "share_no_gps");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("สร้างพื้นที่ชั่วคราวสำหรับแชร์ไม่ได้");
        }
        cleanupOldFiles(dir);

        File out = new File(dir, base + "_NoGPS" + extension);
        if (out.exists() && !out.delete()) {
            throw new IOException("เตรียมไฟล์แชร์ไม่ได้");
        }

        try (InputStream in = resolver.openInputStream(source);
             OutputStream output = new FileOutputStream(out)) {
            if (in == null) throw new IOException("เปิดภาพต้นฉบับไม่ได้");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
        }

        if (!out.exists() || out.length() < 32L) {
            throw new IOException("สร้างภาพสำหรับแชร์ไม่สำเร็จ");
        }

        try {
            ExifInterface exif = new ExifInterface(out.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null);
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null);
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null);
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null);
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null);
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null);
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null);
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null);
            exif.saveAttributes();

            ExifInterface verify = new ExifInterface(out.getAbsolutePath());
            if (verify.getLatLong() != null) {
                throw new IOException("ตรวจสอบการลบ GPS ไม่ผ่าน");
            }
        } catch (IOException e) {
            try { out.delete(); } catch (Exception ignored) {}
            throw new IOException("ไม่สามารถสร้างภาพแบบไม่มี GPS ได้: " + messageOf(e), e);
        }

        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                out);
    }

    private static void cleanupOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                try { file.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private static String cleanBaseName(String name) {
        String value = name == null ? "DormCamera" : name.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) value = "DormCamera";
        if (value.length() > 70) value = value.substring(0, 70).trim();
        return value;
    }

    private static String extensionFrom(String name, String mime) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".jpg")) return ".jpg";
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".heic")) return ".heic";
        if (lower.endsWith(".heif")) return ".heif";
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("image/heic".equalsIgnoreCase(mime) || "image/heif".equalsIgnoreCase(mime)) return ".heic";
        return ".jpg";
    }

    private static String messageOf(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
