package com.pcopi.dormcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Bounded-memory QR import for large Samsung photos and LINE images.
 * Decodes in a worker thread, honors EXIF orientation, then tries the
 * image as a whole and likely QR-card footer regions.
 */
public final class QrImageDecoder {
    private static final int MAX_EDGE_PX = 2600;

    private QrImageDecoder() {}

    @Nullable
    public static String decodeUri(@NonNull Context context, @NonNull Uri uri)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("ไม่สามารถอ่านภาพจาก LINE / แกลเลอรี");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("ไฟล์ที่เลือกไม่ใช่รูปภาพที่รองรับ");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE_PX) {
            options.inSampleSize *= 2;
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {}

        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("เปิดภาพ QR ไม่สำเร็จ");
            decoded = BitmapFactory.decodeStream(in, null, options);
        } catch (OutOfMemoryError oom) {
            throw new IOException("รูปมีขนาดใหญ่เกินหน่วยความจำสำหรับอ่าน QR", oom);
        }
        if (decoded == null) throw new IOException("แปลงภาพเป็นข้อมูล QR ไม่สำเร็จ");

        Bitmap picture = decoded;
        try {
            Matrix matrix = matrixForOrientation(orientation);
            if (!matrix.isIdentity()) {
                try {
                    picture = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(),
                            decoded.getHeight(), matrix, true);
                } catch (OutOfMemoryError oom) {
                    throw new IOException("ไม่สามารถหมุนภาพเพื่ออ่าน QR ได้", oom);
                }
            }
            return decodeBitmap(picture);
        } finally {
            if (picture != decoded) picture.recycle();
            decoded.recycle();
        }
    }

    @Nullable
    private static String decodeBitmap(@NonNull Bitmap bitmap) throws IOException {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels;
        try {
            pixels = new int[Math.multiplyExact(width, height)];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        } catch (OutOfMemoryError oom) {
            throw new IOException("หน่วยความจำไม่เพียงพอสำหรับตรวจ QR", oom);
        } catch (ArithmeticException ex) {
            throw new IOException("ขนาดภาพ QR ไม่ถูกต้อง", ex);
        }
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap full = new BinaryBitmap(new HybridBinarizer(source));
        String result = decode(full);
        if (result != null) return result;

        // Dorm Camera's QR card always places its QR in the lower footer.
        if (height > 180) {
            result = decodeRegion(source, 0, height / 2, width, height - height / 2);
            if (result != null) return result;
        }
        if (width > 180 && height > 180) {
            result = decodeRegion(source, width / 2, height / 2,
                    width - width / 2, height - height / 2);
            if (result != null) return result;
        }
        // Other apps may place the QR at the right side rather than the bottom.
        if (width > 180) {
            result = decodeRegion(source, width / 2, 0, width - width / 2, height);
            if (result != null) return result;
        }
        return null;
    }

    @Nullable
    private static String decodeRegion(@NonNull RGBLuminanceSource source,
            int left, int top, int width, int height) {
        try {
            return decode(new BinaryBitmap(new HybridBinarizer(
                    source.crop(left, top, width, height))));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    public static String decode(@NonNull BinaryBitmap bitmap) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        String result = decodeOnce(bitmap, hints);
        if (result != null) return result;
        try {
            if (bitmap.isRotateSupported()) {
                result = decodeOnce(bitmap.rotateCounterClockwise(), hints);
            }
        } catch (Exception ignored) {}
        return result;
    }

    @Nullable
    private static String decodeOnce(BinaryBitmap image, Map<DecodeHintType, Object> hints) {
        MultiFormatReader reader = new MultiFormatReader();
        try {
            Result result = reader.decode(image, hints);
            return result == null ? null : result.getText();
        } catch (Exception ignored) {
            return null;
        } finally {
            reader.reset();
        }
    }

    @NonNull
    private static Matrix matrixForOrientation(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f); break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f); break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f); matrix.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f); matrix.postScale(-1f, 1f); break;
            default: break;
        }
        return matrix;
    }
}
