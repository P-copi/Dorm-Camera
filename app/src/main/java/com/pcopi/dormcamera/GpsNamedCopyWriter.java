package com.pcopi.dormcamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Writes GPS to a private temp file first, then publishes a user-named copy. */
public final class GpsNamedCopyWriter {
    private GpsNamedCopyWriter() {}

    @NonNull
    public static Uri createCopyWithGps(
            @NonNull Context context,
            @NonNull Uri source,
            @NonNull String sourceName,
            @NonNull String requestedName,
            double latitude,
            double longitude) throws IOException {

        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(source);
        if (mime == null || !mime.startsWith("image/")) mime = mimeFromName(sourceName);
        String finalName = buildOutputName(requestedName, sourceName, mime);

        File work = File.createTempFile("dorm_gps_named_", extensionForMime(mime), context.getCacheDir());
        Uri published = null;
        boolean ready = false;
        try {
            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = new FileOutputStream(work)) {
                if (in == null) throw new IOException("เปิดภาพต้นฉบับไม่ได้");
                copy(in, out);
            }
            if (!work.exists() || work.length() < 32L) throw new IOException("สร้างไฟล์ชั่วคราวไม่สำเร็จ");

            ExifInterface exif = new ExifInterface(work.getAbsolutePath());
            exif.setLatLong(latitude, longitude);
            exif.saveAttributes();

            ExifInterface verify = new ExifInterface(work.getAbsolutePath());
            double[] ll = verify.getLatLong();
            if (ll == null || ll.length < 2
                    || Math.abs(ll[0] - latitude) > 0.00001
                    || Math.abs(ll[1] - longitude) > 0.00001) {
                throw new IOException("ตรวจสอบ GPS หลังบันทึกไม่ผ่าน");
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, finalName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DormCamera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            published = resolver.insert(collection, values);
            if (published == null) throw new IOException("สร้างสำเนาใน Pictures/DormCamera ไม่ได้");

            try (InputStream in = new FileInputStream(work);
                 OutputStream out = resolver.openOutputStream(published, "w")) {
                if (out == null) throw new IOException("เปิดสำเนาสำหรับเขียนไม่ได้");
                copy(in, out);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(published, complete, null, null) <= 0) {
                    throw new IOException("ยืนยันสำเนาในคลังภาพไม่สำเร็จ");
                }
            }

            try (InputStream in = resolver.openInputStream(published)) {
                if (in == null) throw new IOException("เปิดสำเนาหลังบันทึกไม่ได้");
                double[] finalGps = new ExifInterface(in).getLatLong();
                if (finalGps == null || finalGps.length < 2) {
                    throw new IOException("สำเนาถูกสร้าง แต่ไม่พบ GPS ในไฟล์ปลายทาง");
                }
            }

            ready = true;
            return published;
        } finally {
            if (!ready && published != null) {
                try { resolver.delete(published, null, null); } catch (Exception ignored) {}
            }
            try { work.delete(); } catch (Exception ignored) {}
        }
    }

    private static String buildOutputName(String requestedName, String sourceName, String mime) {
        String cleaned = requestedName == null ? "" : requestedName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        String ext = extensionFromSource(sourceName, mime);
        if (cleaned.isEmpty()) {
            int dot = sourceName.lastIndexOf('.');
            cleaned = dot > 0 ? sourceName.substring(0, dot) : "DormCamera_GPS";
        }
        String lower = cleaned.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif")) {
            int dot = cleaned.lastIndexOf('.');
            cleaned = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        }
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        if (cleaned.isEmpty()) cleaned = "DormCamera_GPS";
        return cleaned + ext;
    }

    private static String extensionFromSource(String sourceName, String mime) {
        int dot = sourceName.lastIndexOf('.');
        if (dot > 0 && dot < sourceName.length() - 1) {
            String ext = sourceName.substring(dot).toLowerCase(Locale.US);
            if (ext.matches("\\.(jpg|jpeg|png|webp|heic|heif)")) return ext;
        }
        return extensionForMime(mime);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) if (read > 0) out.write(buffer, 0, read);
        out.flush();
    }

    private static String mimeFromName(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private static String extensionForMime(String mime) {
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("image/heic".equalsIgnoreCase(mime) || "image/heif".equalsIgnoreCase(mime)) return ".heic";
        return ".jpg";
    }
}