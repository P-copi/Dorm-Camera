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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Robust GPS-copy writer for Photo Navigator.
 *
 * The selected original is always read-only. GPS is written and verified on a
 * private temporary file first; only then are the completed bytes published to
 * Pictures/DormCamera. This avoids direct EXIF writes through MediaStore FDs.
 */
public final class GpsCopyWriter {
    private GpsCopyWriter() {}

    @NonNull
    public static Uri createCopyWithGps(
            @NonNull Context context,
            @NonNull Uri source,
            @NonNull String sourceName,
            double latitude,
            double longitude) throws IOException {

        ContentResolver resolver = context.getContentResolver();
        String sourceMime = resolver.getType(source);
        if (sourceMime == null || !sourceMime.startsWith("image/")) {
            sourceMime = mimeFromName(sourceName);
        }

        File work = File.createTempFile("dorm_gps_", extensionForMime(sourceMime), context.getCacheDir());
        Uri published = null;
        boolean publishedReady = false;
        try {
            copyUriToFile(resolver, source, work);

            ExifInterface exif = new ExifInterface(work.getAbsolutePath());
            exif.setLatLong(latitude, longitude);
            exif.saveAttributes();

            ExifInterface verify = new ExifInterface(work.getAbsolutePath());
            double[] gps = verify.getLatLong();
            if (gps == null || gps.length < 2
                    || Math.abs(gps[0] - latitude) > 0.00001
                    || Math.abs(gps[1] - longitude) > 0.00001) {
                throw new IOException("ตรวจสอบ GPS หลังบันทึกไม่ผ่าน");
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, gpsCopyName(sourceName, sourceMime));
            values.put(MediaStore.Images.Media.MIME_TYPE, sourceMime);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DormCamera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            published = resolver.insert(collection, values);
            if (published == null) throw new IOException("สร้างไฟล์สำเนาใน Pictures ไม่ได้");

            try (InputStream in = new FileInputStream(work);
                 OutputStream out = resolver.openOutputStream(published, "w")) {
                if (out == null) throw new IOException("เปิดไฟล์สำเนาสำหรับเขียนไม่ได้");
                copy(in, out);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                int rows = resolver.update(published, ready, null, null);
                if (rows <= 0) throw new IOException("ยืนยันไฟล์สำเนาในคลังภาพไม่สำเร็จ");
            }

            try (InputStream in = resolver.openInputStream(published)) {
                if (in == null) throw new IOException("เปิดสำเนาที่สร้างแล้วเพื่อตรวจสอบไม่ได้");
                ExifInterface finalExif = new ExifInterface(in);
                double[] finalGps = finalExif.getLatLong();
                if (finalGps == null || finalGps.length < 2) {
                    throw new IOException("สำเนาถูกสร้าง แต่ไม่พบ GPS ในไฟล์ปลายทาง");
                }
            }

            publishedReady = true;
            return published;
        } finally {
            if (!publishedReady && published != null) {
                try { resolver.delete(published, null, null); } catch (Exception ignored) {}
            }
            try { work.delete(); } catch (Exception ignored) {}
        }
    }

    private static void copyUriToFile(ContentResolver resolver, Uri source, File target) throws IOException {
        try (InputStream in = resolver.openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("เปิดภาพต้นฉบับไม่ได้");
            copy(in, out);
        }
        if (!target.exists() || target.length() < 32L) {
            throw new IOException("คัดลอกภาพต้นฉบับไปไฟล์ชั่วคราวไม่สำเร็จ");
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read > 0) out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static String gpsCopyName(String sourceName, String mime) {
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        String ext = dot > 0 ? sourceName.substring(dot) : extensionForMime(mime);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return base + "_GPS_" + stamp + ext;
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
