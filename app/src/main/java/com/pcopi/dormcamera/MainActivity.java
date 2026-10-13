package com.pcopi.dormcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 1001;

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA
            );
        }
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

        File baseDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (baseDir == null) {
            baseDir = getFilesDir();
        }
        File dormDir = new File(baseDir, "DormCamera");
        if (!dormDir.exists() && !dormDir.mkdirs()) {
            showError("สร้างโฟลเดอร์ภาพไม่สำเร็จ");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        File photoFile = new File(dormDir, "IMG_" + stamp + ".jpg");

        imageCapture.setTargetRotation(previewView.getDisplay().getRotation());
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        statusText.setText("กำลังบันทึกภาพ...");
        captureButton.setEnabled(false);

        imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        captureButton.setEnabled(true);
                        updateCameraStatus();
                        Toast.makeText(
                                MainActivity.this,
                                "บันทึกแล้ว: " + photoFile.getName(),
                                Toast.LENGTH_SHORT
                        ).show();
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
        statusText.setText("Dorm Camera • " + side + " • โหมดเร็ว");
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
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                showError("ต้องอนุญาตสิทธิ์กล้องก่อนใช้งาน");
            }
        }
    }
}
