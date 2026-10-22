package com.pcopi.dormcamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Optional user-requested fallback: save prepared image/ZIP in Downloads/DormCamera. */
public final class ShareToDownloads {
    private ShareToDownloads() {}

    @NonNull
    public static Uri save(@NonNull Context context, @NonNull Uri preparedUri,
            @NonNull String targetName, @NonNull String mime) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("สำรองลง Downloads รองรับ Android 10 ขึ้นไป");
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, targetName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/DormCamera/");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri destination = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (destination == null) throw new IOException("สร้างไฟล์ใน Downloads ไม่สำเร็จ");

        boolean success = false;
        try {
            try (InputStream in = resolver.openInputStream(preparedUri);
                 OutputStream out = resolver.openOutputStream(destination, "w")) {
                if (in == null || out == null) {
                    throw new IOException("อ่านไฟล์สำหรับสำรองไม่ได้");
                }
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n > 0) out.write(buffer, 0, n);
                }
                out.flush();
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, publish, null, null) <= 0) {
                throw new IOException("ยืนยันไฟล์ใน Downloads ไม่สำเร็จ");
            }
            success = true;
            return destination;
        } finally {
            if (!success) {
                try { resolver.delete(destination, null, null); } catch (Exception ignored) {}
            }
        }
    }

    @NonNull
    public static String cleanName(@NonNull String raw) {
        String name = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "DormCamera";
        if (name.length() > 80) name = name.substring(0, 80).trim();
        return name;
    }
}
