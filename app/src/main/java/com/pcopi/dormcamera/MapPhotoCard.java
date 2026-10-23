package com.pcopi.dormcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;

/** One visible JPEG containing a photo, location details and a scannable Maps QR. No EXIF. */
public final class MapPhotoCard {
    private MapPhotoCard() {}

    public static Uri create(Context context, Uri source, String name, double lat, double lon)
            throws Exception {
        if (!SmartGpsLink.valid(lat, lon)) throw new IOException("พิกัด GPS ไม่ถูกต้อง");
        // Decode the already-sanitized image; no EXIF or original GPS is copied.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("อ่านภาพไม่ได้");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > 1600)
            opts.inSampleSize *= 2;
        Bitmap photo;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            photo = BitmapFactory.decodeStream(in, null, opts);
        }
        if (photo == null) throw new IOException("แปลงภาพไม่ได้");
        int w = photo.getWidth();
        int h = photo.getHeight();
        int footer = Math.max(230, Math.round(w * 0.31f));
        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(w, h + footer, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(photo, 0, 0, null);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(22, 30, 40));
            canvas.drawRect(0, h, w, h + footer, p);
            int pad = Math.max(16, w / 45);
            int qrSize = footer - pad * 2;
            String url = SmartGpsLink.navigationUrl(lat, lon);
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix qr = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE,
                    qrSize, qrSize, hints);
            p.setColor(Color.WHITE);
            canvas.drawRect(w - footer + pad / 2f, h + pad / 2f,
                    w - pad / 2f, h + footer - pad / 2f, p);
            p.setColor(Color.BLACK);
            float qx = w - footer + pad;
            float qy = h + pad;
            for (int y = 0; y < qrSize; y++) {
                for (int x = 0; x < qrSize; x++) {
                    if (qr.get(x, y)) canvas.drawPoint(qx + x, qy + y, p);
                }
            }
            p.setColor(Color.WHITE);
            p.setTextSize(Math.max(18, Math.min(42, w / 27f)));
            p.setFakeBoldText(true);
            String title = name == null ? "สถานที่จากภาพ" : name.replace('\n', ' ').trim();
            int maxChars = Math.max(8, (int) ((w - footer - 2f * pad) / (p.getTextSize() * 0.58f)));
            if (title.length() > maxChars) title = title.substring(0, Math.max(1, maxChars - 1)) + "…";
            canvas.drawText(title, pad, h + footer * .29f, p);
            p.setFakeBoldText(false);
            p.setTextSize(Math.max(14, Math.min(31, w / 36f)));
            canvas.drawText(String.format(Locale.US, "%.6f, %.6f", lat, lon),
                    pad, h + footer * .51f, p);
            canvas.drawText("Google Maps • Scan QR", pad, h + footer * .72f, p);
            File dir = new File(context.getCacheDir(), "share_no_gps");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("สร้างพื้นที่แชร์ไม่ได้");
            File file = new File(dir, "DormCamera_MapQR_" + System.nanoTime() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 96, out))
                    throw new IOException("สร้าง JPEG ไม่สำเร็จ");
            }
            if (file.length() < 100) throw new IOException("ไฟล์ภาพไม่สมบูรณ์");
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
        } finally {
            photo.recycle();
            if (output != null) output.recycle();
        }
    }
}
