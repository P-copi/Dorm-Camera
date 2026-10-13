package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 1001;
    private static final String PUBLIC_FOLDER = "DormCamera";

    private PreviewView previewView;
    private TextView statusText;
    private Button captureButton;
    private Button switchButton;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);
        switchButton = findViewById(R.id.switchButton);

        captureButton.setEnabled(false);
        switchButton.setEnabled(false);

        captureButton.setOnClickListener(v -> takePhoto());
        switchButton.setOnClickListener(v -> switchCamera());

        if (hasRequiredPermissions()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMISSIONS);
        }
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startCamera() {
        statusText.setText("กำลังเปิดกล้อง...");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        Executor mainExecutor = ContextCompat.getMainExecutor(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                showError("เปิดกล้องไม่สำเร็จ: " + e.getMessage());
            }
        }, mainExecutor);
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            if (!cameraProvider.hasCamera(selector)) {
                int fallback = lensFacing == CameraSelector.LENS_FACING_BACK
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK;
                CameraSelector fallbackSelector = new CameraSelector.Builder()
                        .requireLensFacing(fallback)
                        .build();
                if (!cameraProvider.hasCamera(fallbackSelector)) {
                    showError("อุปกรณ์นี้ไม่มีกล้องที่รองรับ");
                    return;
                }
                lensFacing = fallback;
                selector = fallbackSelector;
            }

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

            captureButton.setEnabled(true);
            switchButton.setEnabled(true);
            updateCameraStatus();
        } catch (Exception e) {
            showError("ผูกกล้องไม่สำเร็จ: " + e.getMessage());
        }
    }

    private void switchCamera() {
        captureButton.setEnabled(false);
        switchButton.setEnabled(false);
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        bindCameraUseCases();
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show();
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        String fileName = "IMG_" + stamp + ".jpg";

        final File legacyPhotoFile;
        ImageCapture.OutputFileOptions options;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            legacyPhotoFile = null;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + PUBLIC_FOLDER
            );

            options = new ImageCapture.OutputFileOptions.Builder(
                    getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            ).build();
        } else {
            File picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
            );
            File dormDir = new File(picturesDir, PUBLIC_FOLDER);
            if (!dormDir.exists() && !dormDir.mkdirs()) {
                showError("สร้างโฟลเดอร์ Pictures/DormCamera ไม่สำเร็จ");
                return;
            }
            legacyPhotoFile = new File(dormDir, fileName);
            options = new ImageCapture.OutputFileOptions.Builder(legacyPhotoFile).build();
        }

        if (previewView.getDisplay() != null) {
            imageCapture.setTargetRotation(previewView.getDisplay().getRotation());
        }
        statusText.setText("กำลังบันทึกลง Pictures/DormCamera...");
        captureButton.setEnabled(false);

        imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        captureButton.setEnabled(true);

                        if (legacyPhotoFile != null) {
                            MediaScannerConnection.scanFile(
                                    MainActivity.this,
                                    new String[]{legacyPhotoFile.getAbsolutePath()},
                                    new String[]{"image/jpeg"},
                                    null
                            );
                        }

                        statusText.setText("บันทึกแล้ว • Pictures/DormCamera");
                        Toast.makeText(
                                MainActivity.this,
                                "บันทึกแล้วใน Pictures/DormCamera\n" + fileName,
                                Toast.LENGTH_LONG
                        ).show();
                        statusText.postDelayed(() -> updateCameraStatus(), 1800L);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        captureButton.setEnabled(true);
                        showError("ถ่ายภาพไม่สำเร็จ: " + exception.getMessage());
                    }
                }
        );
    }

    private void updateCameraStatus() {
        String side = lensFacing == CameraSelector.LENS_FACING_BACK ? "กล้องหลัง" : "กล้องหน้า";
        statusText.setText("Dorm Camera • " + side + " • บันทึก: Pictures/DormCamera");
    }

    private void showError(String message) {
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                startCamera();
            } else {
                showError("ต้องอนุญาตสิทธิ์กล้องและสิทธิ์บันทึกภาพก่อนใช้งาน");
            }
        }
    }
}
