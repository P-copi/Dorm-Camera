package com.pcopi.dormcamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.location.Location;
import android.media.MediaScannerConnection;
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

/**
 * Never publish a captured JPEG until the photo has verified GPS EXIF.
 * CameraX first writes to an app-owned private JPEG; the verified bytes are
 * copied without recompression into the selected Pictures/DormCamera folder.
 */
public final class CapturePhotoSaver {
    private CapturePhotoSaver() {}

    public static final class Result {
        @NonNull public final Uri uri;
        public final double latitude;
        public final double longitude;

        Result(@NonNull Uri uri, double latitude, double longitude) {
            this.uri = uri;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    @NonNull
    public static Result publishVerified(@NonNull Context context, @NonNull File captured,
            @NonNull String fileName, @NonNull String relativeFolder,
            @NonNull File legacyFolder, @NonNull Location captureLocation) throws IOException {
        if (!captured.isFile() || captured.length() < 100) {
            throw new IOException("CameraX ยังไม่ได้สร้างภาพ JPEG ที่สมบูรณ์");
        }
        if (!valid(captureLocation.getLatitude(), captureLocation.getLongitude())) {
            throw new IOException("พิกัดตำแหน่งก่อนถ่ายไม่ถูกต้อง");
        }

        // Read actual JPEG EXIF, not redacted MediaStore bytes.
        ExifInterface exif = new ExifInterface(captured.getAbsolutePath());
        double[] existing = exif.getLatLong();
        if (!matches(existing, captureLocation)) {
            exif.setGpsInfo(captureLocation);
            exif.saveAttributes();
        }
        double[] finalLocation = new ExifInterface(captured.getAbsolutePath()).getLatLong();
        if (!matches(finalLocation, captureLocation)) {
            throw new IOException("ตรวจ EXIF หลังฝัง GPS ไม่ผ่าน ภาพจะยังไม่ถูกส่งไปคลัง");
        }

        ContentResolver resolver = context.getContentResolver();
        Uri saved = null;
        boolean committed = false;
        File legacyTarget = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri collection = MediaStore.Images.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY);
                saved = resolver.insert(collection, values);
                if (saved == null) throw new IOException("สร้างรายการภาพในคลังไม่ได้");
                try (InputStream in = new FileInputStream(captured);
                     OutputStream out = resolver.openOutputStream(saved, "w")) {
                    if (out == null) throw new IOException("เปิดไฟล์คลังภาพเพื่อบันทึกไม่ได้");
                    copy(in, out);
                }
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(saved, finish, null, null) <= 0) {
                    throw new IOException("ยืนยันไฟล์ JPEG ในคลังไม่สำเร็จ");
                }
                committed = true;
            } else {
                if (!legacyFolder.isDirectory() && !legacyFolder.mkdirs()) {
                    throw new IOException("สร้างโฟลเดอร์ภาพไม่ได้");
                }
                legacyTarget = new File(legacyFolder, fileName);
                try (InputStream in = new FileInputStream(captured);
                     OutputStream out = new FileOutputStream(legacyTarget)) {
                    copy(in, out);
                }
                saved = Uri.fromFile(legacyTarget);
                committed = true;
                MediaScannerConnection.scanFile(context,
                        new String[]{legacyTarget.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);
            }
            return new Result(saved, finalLocation[0], finalLocation[1]);
        } finally {
            if (!committed) {
                if (saved != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { resolver.delete(saved, null, null); } catch (Exception ignored) {}
                }
                if (legacyTarget != null) legacyTarget.delete();
            }
        }
    }

    private static boolean matches(double[] loc, Location expected) {
        return loc != null && loc.length >= 2
                && valid(loc[0], loc[1])
                && Math.abs(loc[0] - expected.getLatitude()) <= 0.00003
                && Math.abs(loc[1] - expected.getLongitude()) <= 0.00003;
    }

    private static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) out.write(buffer, 0, read);
        }
        out.flush();
    }
}
