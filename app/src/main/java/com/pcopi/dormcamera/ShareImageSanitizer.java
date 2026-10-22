package com.pcopi.dormcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Produces a fresh JPEG containing pixels only: no EXIF, GPS, or location metadata.
 * This is independent of whether LINE strips metadata after sending.
 */
public final class ShareImageSanitizer {
    private ShareImageSanitizer() {}

    @NonNull
    public static Uri createGpsFreeShareUri(
            @NonNull Context context, @NonNull Uri source,
            @NonNull String displayName) throws IOException {
        File dir = new File(context.getCacheDir(), "share_no_gps");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("สร้างพื้นที่ชั่วคราวสำหรับแชร์ไม่ได้");
        }
        File[] oldFiles = dir.listFiles();
        if (oldFiles != null) {
            long cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L;
            for (File file : oldFiles) {
                if (file.isFile() && file.lastModified() < cutoff) file.delete();
            }
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("เปิดภาพต้นฉบับไม่ได้");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("อ่านรูปแบบภาพไม่ได้ กรุณาลองภาพ JPG/PNG/HEIC");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3000) {
            options.inSampleSize *= 2;
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {}

        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("เปิดภาพเพื่อแสดงไม่ได้");
            decoded = BitmapFactory.decodeStream(in, null, options);
        } catch (OutOfMemoryError oom) {
            throw new IOException("ภาพใหญ่เกินไป กรุณาลองภาพความละเอียดต่ำลง", oom);
        }
        if (decoded == null) throw new IOException("แปลงภาพเป็น JPG ไม่สำเร็จ");

        Bitmap picture = decoded;
        File output = new File(dir, "DormCamera_NoGPS_" + System.nanoTime() + ".jpg");
        try {
            Matrix matrix = orientationMatrix(orientation);
            if (!matrix.isIdentity()) {
                try {
                    picture = Bitmap.createBitmap(decoded, 0, 0,
                            decoded.getWidth(), decoded.getHeight(), matrix, true);
                } catch (OutOfMemoryError oom) {
                    throw new IOException("หมุนภาพไม่สำเร็จเพราะหน่วยความจำไม่เพียงพอ", oom);
                }
            }

            try (FileOutputStream out = new FileOutputStream(output)) {
                if (!picture.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                    throw new IOException("เข้ารหัสภาพ JPG ไม่สำเร็จ");
                }
                out.flush();
            }

            if (output.length() < 100L) throw new IOException("ไฟล์ JPG ที่สร้างไม่สมบูรณ์");
            ExifInterface verify = new ExifInterface(output.getAbsolutePath());
            if (verify.getLatLong() != null) {
                throw new IOException("ตรวจไม่ผ่าน: ภาพยังมีข้อมูล GPS");
            }
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", output);
        } catch (IOException | RuntimeException e) {
            output.delete();
            throw e;
        } finally {
            if (picture != decoded) picture.recycle();
            decoded.recycle();
        }
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            default:
                break;
        }
        return matrix;
    }
}
