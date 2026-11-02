package com.pcopi.dormcamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.EditText;
import android.net.Uri;
import android.os.Bundle;
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
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
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
            ensureCameraPermission();
        });
        button(root, "🖼 อ่าน QR จากภาพ / LINE / แกลเลอรี", () -> gallery.launch("image/*"));
        button(root, "← กลับ", this::finish);
        setContentView(root);
        Uri incomingImage = getIntent() == null ? null
                : getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        // A QR photo shared into Dorm Camera can be scanned directly without
        // asking for camera permission or saving the file a second time.
        if (incomingImage != null) {
            scanGallery(incomingImage);
        } else {
            ensureCameraPermission();
        }
    }

    private void ensureCameraPermission() {
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
        return QrImageDecoder.decode(new BinaryBitmap(new HybridBinarizer(
                new PlanarYUVLuminanceSource(luminance, w, h, 0, 0, w, h, false))));
    }

    private void scanGallery(Uri uri) {
        handling.set(true);
        worker.execute(() -> {
            String value = null;
            String error = null;
            try {
                value = QrImageDecoder.decodeUri(this, uri);
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            String result = value;
            String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    handling.set(false);
                    Toast.makeText(this, failure == null
                            ? "ไม่พบ QR ที่อ่านได้ในภาพ • ลองเลือกภาพที่ชัดกว่า"
                            : "อ่าน QR ไม่สำเร็จ: " + failure,
                            Toast.LENGTH_LONG).show();
                } else showResult(result);
            });
        });
    }

    private void showResult(String raw) {
        SmartGpsLink.Parsed place = SmartGpsLink.parse(raw);
        if (place == null) {
            new AlertDialog.Builder(this).setTitle("อ่าน QR ได้ แต่ไม่ใช่พิกัด Google Maps")
                    .setMessage("เนื้อหา: " + raw.substring(0, Math.min(300, raw.length())))
                    .setPositiveButton("สแกนอีกครั้ง", (d, w) -> resumeScanner())
                    .setOnCancelListener(d -> resumeScanner()).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("✓ พบพิกัดนำทางจาก QR")
                .setMessage("สถานที่: " + place.title
                        + "\nพิกัด: " + place.latitude + ", " + place.longitude
                        + "\n\nตรวจสอบปลายทางก่อนเปิด Google Maps")
                .setPositiveButton("นำทาง Google Maps", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(place.navigationUrl)));
                    } catch (Exception e) {
                        Toast.makeText(this, "ไม่พบแอปเปิดแผนที่", Toast.LENGTH_LONG).show();
                    }
                    resumeScanner();
                })
                .setNeutralButton("บันทึกสถานที่", (d, w) -> saveQrLocation(place))
                .setNegativeButton("สแกนอีกครั้ง", (d, w) -> resumeScanner())
                .setOnCancelListener(d -> resumeScanner()).show();
    }

    private void saveQrLocation(SmartGpsLink.Parsed place) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("ชื่อสถานที่");
        input.setText("ตำแหน่งจาก LINE".equals(place.title)
                ? "สถานที่จาก QR" : place.title);
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("📍 บันทึกพิกัดจาก QR")
                .setMessage("เก็บในเมนู สถานที่ของฉัน โดยไม่ต้องมีภาพต้นฉบับ")
                .setView(input)
                .setPositiveButton("บันทึก", null)
                .setNegativeButton("ยกเลิก", (d, w) -> resumeScanner())
                .setOnCancelListener(d -> resumeScanner())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("กรุณาตั้งชื่อสถานที่");
                        return;
                    }
                    try {
                        SavedGpsPlaces.save(this, name, place.latitude, place.longitude);
                        dialog.dismiss();
                        resumeScanner();
                        Toast.makeText(this, "✓ บันทึก GPS ในสถานที่ของฉันแล้ว", Toast.LENGTH_LONG).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "บันทึกพิกัดไม่สำเร็จ: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void resumeScanner() {
        handling.set(false);
        if (provider == null && ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() {
        if (provider != null) provider.unbindAll();
        worker.shutdown();
        super.onDestroy();
    }
}
