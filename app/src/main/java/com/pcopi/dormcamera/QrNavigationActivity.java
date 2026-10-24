package com.pcopi.dormcamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent scanner: works with either camera lens and saved LINE screenshots. */
public final class QrNavigationActivity extends AppCompatActivity {
    private PreviewView preview;
    private final AtomicBoolean handling = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private int lens = CameraSelector.LENS_FACING_BACK;
    private ActivityResultLauncher<String> gallery;
    private ActivityResultLauncher<String> cameraPermission;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        gallery = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) scanGallery(uri);
        });
        cameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startCamera();
                    else Toast.makeText(this, "กรุณาอนุญาตกล้อง หรือเลือกภาพจากเครื่อง", Toast.LENGTH_LONG).show();
                });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(0xFF090C12);
        TextView heading = new TextView(this);
        heading.setText("สแกน QR นำทาง • Dorm Camera");
        heading.setTextSize(20f);
        heading.setTextColor(-1);
        root.addView(heading);
        preview = new PreviewView(this);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));
        button(root, "📷 สลับกล้องหน้า / หลัง", () -> {
            lens = lens == CameraSelector.LENS_FACING_BACK ?
                    CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            handling.set(false);
            startCamera();
        });
        button(root, "🖼 อ่าน QR จากภาพ / LINE / แกลเลอรี", () -> gallery.launch("image/*"));
        button(root, "← กลับ", this::finish);
        setContentView(root);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void button(LinearLayout root, String label, Runnable action) {
        PressFeedbackButton b = new PressFeedbackButton(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(-1);
        b.setTextSize(15f);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> action.run());
        root.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                provider = future.get();
                provider.unbindAll();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analyzer = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analyzer.setAnalyzer(worker, image -> {
                    try {
                        if (!handling.get()) {
                            String value = readCameraQr(image);
                            if (value != null && handling.compareAndSet(false, true))
                                runOnUiThread(() -> showResult(value));
                        }
                    } catch (Exception ignored) {
                    } finally { image.close(); }
                });
                provider.bindToLifecycle(this, new CameraSelector.Builder()
                        .requireLensFacing(lens).build(), p, analyzer);
            } catch (Exception e) {
                Toast.makeText(this, "เปิดกล้องสแกนไม่ได้: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private String readCameraQr(ImageProxy image) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buf = yPlane.getBuffer();
        int w = image.getWidth(), h = image.getHeight();
        int stride = yPlane.getRowStride(), pixelStride = yPlane.getPixelStride();
        byte[] luminance = new byte[w * h];
        int start = buf.position();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) luminance[y * w + x] =
                    buf.get(start + y * stride + x * pixelStride);
        }
        return decode(new BinaryBitmap(new HybridBinarizer(
                new PlanarYUVLuminanceSource(luminance, w, h, 0, 0, w, h, false))));
    }

    private void scanGallery(Uri uri) {
        handling.set(true);
        worker.execute(() -> {
            String value = null;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = BitmapFactory.decodeStream(stream, null, opts);
                if (b != null) {
                    int[] pixels = new int[b.getWidth() * b.getHeight()];
                    b.getPixels(pixels, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                    value = decode(new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(b.getWidth(), b.getHeight(), pixels))));
                    b.recycle();
                }
            } catch (Exception ignored) {}
            String result = value;
            runOnUiThread(() -> {
                if (result == null) {
                    handling.set(false);
                    Toast.makeText(this, "ไม่พบ QR ที่อ่านได้ในภาพ", Toast.LENGTH_LONG).show();
                } else showResult(result);
            });
        });
    }

    private String decode(BinaryBitmap bitmap) {
        try {
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException | ChecksumException | FormatException e) {
            return null;
        }
    }

    private void showResult(String raw) {
        SmartGpsLink.Parsed place = SmartGpsLink.parse(raw);
        if (place == null) {
            new AlertDialog.Builder(this).setTitle("อ่าน QR ได้ แต่ไม่ใช่พิกัด Google Maps")
                    .setMessage("เนื้อหา: " + raw.substring(0, Math.min(300, raw.length())))
                    .setPositiveButton("สแกนอีกครั้ง", (d, w) -> handling.set(false))
                    .setOnCancelListener(d -> handling.set(false)).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("พบพิกัดนำทาง")
                .setMessage("พิกัด: " + place.latitude + ", " + place.longitude
                        + "\n\nตรวจสอบปลายทางก่อนเปิด Google Maps")
                .setPositiveButton("นำทาง Google Maps", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(place.navigationUrl)));
                    } catch (Exception e) {
                        Toast.makeText(this, "ไม่พบแอปเปิดแผนที่", Toast.LENGTH_LONG).show();
                    }
                    handling.set(false);
                })
                .setNegativeButton("สแกนอีกครั้ง", (d, w) -> handling.set(false))
                .setOnCancelListener(d -> handling.set(false)).show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() {
        if (provider != null) provider.unbindAll();
        worker.shutdown();
        super.onDestroy();
    }
}
