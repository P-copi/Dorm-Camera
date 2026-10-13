package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
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
    private static final int REQ_AUDIO = 1002;
    private static final int REQ_LOCATION = 1003;
    private static final String PUBLIC_FOLDER = "DormCamera";

    private PreviewView previewView;
    private TextView statusText;
    private TextView zoomText;
    private Button captureButton;
    private Button videoButton;
    private Button switchButton;
    private Button zoomOutButton;
    private Button zoomResetButton;
    private Button zoomInButton;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Camera activeCamera;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean startVideoAfterAudioPermission = false;
    private File currentLegacyVideoFile;

    private LocationHelper locationHelper;
    private String gpsStatus = "GPS: กำลังตรวจสอบ";
    private Location currentCaptureLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);
        zoomText = findViewById(R.id.zoomText);
        captureButton = findViewById(R.id.captureButton);
        videoButton = findViewById(R.id.videoButton);
        switchButton = findViewById(R.id.switchButton);
        zoomOutButton = findViewById(R.id.zoomOutButton);
        zoomResetButton = findViewById(R.id.zoomResetButton);
        zoomInButton = findViewById(R.id.zoomInButton);

        locationHelper = new LocationHelper(this);

        setCameraControlsEnabled(false);

        captureButton.setOnClickListener(v -> takePhoto());
        videoButton.setOnClickListener(v -> toggleVideoRecording());
        switchButton.setOnClickListener(v -> switchCamera());
        zoomOutButton.setOnClickListener(v -> multiplyZoom(0.8f));
        zoomResetButton.setOnClickListener(v -> setZoomRatio(1.0f));
        zoomInButton.setOnClickListener(v -> multiplyZoom(1.25f));

        scaleGestureDetector = new ScaleGestureDetector(
                this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        if (activeCamera == null) return false;
                        ZoomState state = activeCamera.getCameraInfo().getZoomState().getValue();
                        if (state == null) return false;
                        float target = state.getZoomRatio() * detector.getScaleFactor();
                        setZoomRatio(target);
                        return true;
                    }
                }
        );

        previewView.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });

        if (hasRequiredPermissions()) {
            startCamera();
            startLocationSetup();
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMISSIONS);
        }
    }

    private void setCameraControlsEnabled(boolean enabled) {
        captureButton.setEnabled(enabled);
        videoButton.setEnabled(enabled);
        switchButton.setEnabled(enabled);
        zoomOutButton.setEnabled(enabled);
        zoomResetButton.setEnabled(enabled);
        zoomInButton.setEnabled(enabled);
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

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationSetup() {
        if (hasLocationPermission()) {
            refreshLocation();
            return;
        }

        gpsStatus = "GPS: รออนุญาต";
        updateCameraStatus();
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQ_LOCATION
        );
    }

    private void refreshLocation() {
        if (!hasLocationPermission()) {
            gpsStatus = "GPS: ไม่อนุญาต";
            updateCameraStatus();
            return;
        }

        gpsStatus = "GPS: กำลังหาพิกัด";
        updateCameraStatus();

        locationHelper.refresh(new LocationHelper.Callback() {
            @Override
            public void onLocation(@NonNull Location location) {
                gpsStatus = location.hasAccuracy()
                        ? String.format(Locale.US, "GPS: พร้อม ±%.0f ม.", location.getAccuracy())
                        : "GPS: พร้อม";
                updateCameraStatus();
            }

            @Override
            public void onUnavailable(@NonNull String reason) {
                gpsStatus = "GPS: " + reason;
                updateCameraStatus();
            }
        });
    }

    private Location prepareLocationForCapture() {
        if (!hasLocationPermission()) {
            gpsStatus = "GPS: ไม่อนุญาต";
            return null;
        }

        Location location = locationHelper.getBestLocation();
        if (location == null || LocationHelper.isStale(location)) {
            refreshLocation();
            return null;
        }
        return location;
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
        if (activeRecording != null) return;

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

            QualitySelector qualitySelector = QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            );
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);

            if (activeCamera != null) {
                activeCamera.getCameraInfo().getZoomState().removeObservers(this);
            }

            cameraProvider.unbindAll();
            activeCamera = cameraProvider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture,
                    videoCapture
            );

            activeCamera.getCameraInfo().getZoomState().observe(this, this::updateZoomUi);
            setZoomRatio(1.0f);
            setCameraControlsEnabled(true);
            updateCameraStatus();
        } catch (Exception e) {
            showError("ผูกกล้อง/วิดีโอไม่สำเร็จ: " + e.getMessage());
        }
    }

    private void updateZoomUi(ZoomState state) {
        if (state == null) {
            zoomText.setText("ซูม --");
            return;
        }

        float zoom = state.getZoomRatio();
        float min = state.getMinZoomRatio();
        float max = state.getMaxZoomRatio();

        zoomText.setText(String.format(
                Locale.US,
                "ซูม %.1fx  •  ช่วง %.1f–%.1fx  •  บีบนิ้วบนภาพเพื่อซูม",
                zoom,
                min,
                max
        ));

        zoomOutButton.setEnabled(zoom > min + 0.01f);
        zoomInButton.setEnabled(zoom < max - 0.01f);
        zoomResetButton.setEnabled(Math.abs(zoom - 1.0f) > 0.01f);
    }

    private void multiplyZoom(float factor) {
        if (activeCamera == null) return;
        ZoomState state = activeCamera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        setZoomRatio(state.getZoomRatio() * factor);
    }

    private void setZoomRatio(float requestedRatio) {
        if (activeCamera == null) return;
        ZoomState state = activeCamera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;

        float min = state.getMinZoomRatio();
        float max = state.getMaxZoomRatio();
        float safeRatio = Math.max(min, Math.min(max, requestedRatio));
        activeCamera.getCameraControl().setZoomRatio(safeRatio);
    }

    private void switchCamera() {
        if (activeRecording != null) {
            Toast.makeText(this, "หยุดวิดีโอก่อนสลับกล้อง", Toast.LENGTH_SHORT).show();
            return;
        }
        setCameraControlsEnabled(false);
        zoomText.setText("กำลังสลับกล้อง...");
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        bindCameraUseCases();
    }

    private void takePhoto() {
        if (activeRecording != null) {
            Toast.makeText(this, "หยุดวิดีโอก่อนถ่ายภาพ", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageCapture == null) {
            Toast.makeText(this, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show();
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        String fileName = "IMG_" + stamp + ".jpg";

        currentCaptureLocation = prepareLocationForCapture();
        ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        metadata.setLocation(currentCaptureLocation);

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
            ).setMetadata(metadata).build();
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
            options = new ImageCapture.OutputFileOptions.Builder(legacyPhotoFile)
                    .setMetadata(metadata)
                    .build();
        }

        if (previewView.getDisplay() != null) {
            imageCapture.setTargetRotation(previewView.getDisplay().getRotation());
        }

        boolean hasGps = currentCaptureLocation != null;
        statusText.setText(
                "กำลังบันทึกภาพลง Pictures/DormCamera..."
                        + (hasGps ? " • GPS ✓" : " • ไม่มี GPS")
        );
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

                        statusText.setText(
                                "บันทึกภาพแล้ว • Pictures/DormCamera"
                                        + (hasGps ? " • GPS ✓" : " • ไม่มี GPS")
                        );
                        Toast.makeText(
                                MainActivity.this,
                                "บันทึกภาพแล้วใน Pictures/DormCamera\n"
                                        + fileName
                                        + (hasGps ? "\nGPS metadata: บันทึกแล้ว" : "\nGPS metadata: ไม่มีพิกัด"),
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

    private void toggleVideoRecording() {
        if (activeRecording != null) {
            videoButton.setEnabled(false);
            videoButton.setText("กำลังหยุด...");
            activeRecording.stop();
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(this, "วิดีโอยังไม่พร้อม", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasAudioPermission()) {
            startVideoAfterAudioPermission = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
            return;
        }

        startVideoRecording();
    }

    private void startVideoRecording() {
        if (videoCapture == null || activeRecording != null) return;

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        String fileName = "VID_" + stamp + ".mp4";

        currentCaptureLocation = prepareLocationForCapture();
        Location videoLocation = currentCaptureLocation;

        PendingRecording pendingRecording;
        currentLegacyVideoFile = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + PUBLIC_FOLDER
            );

            MediaStoreOutputOptions.Builder builder = new MediaStoreOutputOptions.Builder(
                    getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values);

            if (videoLocation != null) {
                builder.setLocation(videoLocation);
            }
            pendingRecording = videoCapture.getOutput().prepareRecording(this, builder.build());
        } else {
            File moviesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES
            );
            File dormDir = new File(moviesDir, PUBLIC_FOLDER);
            if (!dormDir.exists() && !dormDir.mkdirs()) {
                showError("สร้างโฟลเดอร์ Movies/DormCamera ไม่สำเร็จ");
                return;
            }
            currentLegacyVideoFile = new File(dormDir, fileName);
            FileOutputOptions.Builder builder = new FileOutputOptions.Builder(currentLegacyVideoFile);
            if (videoLocation != null) {
                builder.setLocation(videoLocation);
            }
            pendingRecording = videoCapture.getOutput().prepareRecording(this, builder.build());
        }

        if (hasAudioPermission()) {
            pendingRecording = pendingRecording.withAudioEnabled();
        } else {
            Toast.makeText(this, "บันทึกวิดีโอแบบไม่มีเสียง", Toast.LENGTH_SHORT).show();
        }

        captureButton.setEnabled(false);
        switchButton.setEnabled(false);
        videoButton.setEnabled(true);
        videoButton.setText("หยุดวิดีโอ");

        final boolean hasGps = videoLocation != null;
        activeRecording = pendingRecording.start(
                ContextCompat.getMainExecutor(this),
                event -> handleVideoEvent(event, fileName, hasGps)
        );
    }

    private void handleVideoEvent(VideoRecordEvent event, String fileName, boolean hasGps) {
        if (event instanceof VideoRecordEvent.Start) {
            String side = lensFacing == CameraSelector.LENS_FACING_BACK ? "กล้องหลัง" : "กล้องหน้า";
            statusText.setText(
                    "กำลังบันทึกวิดีโอ • "
                            + side
                            + " • 1080p/720p • Movies/DormCamera"
                            + (hasGps ? " • GPS ✓" : " • ไม่มี GPS")
            );
            return;
        }

        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
            File legacyVideoFile = currentLegacyVideoFile;
            currentLegacyVideoFile = null;

            if (activeRecording != null) {
                activeRecording.close();
                activeRecording = null;
            }

            videoButton.setEnabled(true);
            videoButton.setText("วิดีโอ");
            captureButton.setEnabled(true);
            switchButton.setEnabled(true);

            ZoomState state = activeCamera != null
                    ? activeCamera.getCameraInfo().getZoomState().getValue()
                    : null;
            updateZoomUi(state);

            if (finalizeEvent.hasError()) {
                showError("บันทึกวิดีโอไม่สำเร็จ (error " + finalizeEvent.getError() + ")");
                return;
            }

            if (legacyVideoFile != null) {
                MediaScannerConnection.scanFile(
                        this,
                        new String[]{legacyVideoFile.getAbsolutePath()},
                        new String[]{"video/mp4"},
                        null
                );
            }

            statusText.setText(
                    "บันทึกวิดีโอแล้ว • Movies/DormCamera"
                            + (hasGps ? " • GPS ✓" : " • ไม่มี GPS")
            );
            Toast.makeText(
                    this,
                    "บันทึกวิดีโอแล้วใน Movies/DormCamera\n"
                            + fileName
                            + (hasGps ? "\nGPS metadata: บันทึกแล้ว" : "\nGPS metadata: ไม่มีพิกัด"),
                    Toast.LENGTH_LONG
            ).show();
            statusText.postDelayed(this::updateCameraStatus, 1800L);
        }
    }

    private void updateCameraStatus() {
        if (statusText == null) return;
        String side = lensFacing == CameraSelector.LENS_FACING_BACK ? "กล้องหลัง" : "กล้องหน้า";
        statusText.setText(
                "Dorm Camera • " + side
                        + " • " + gpsStatus
                        + " • ภาพ: Pictures/DormCamera"
                        + " • วิดีโอ: Movies/DormCamera"
        );
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
                startLocationSetup();
            } else {
                showError("ต้องอนุญาตสิทธิ์กล้องและสิทธิ์บันทึกก่อนใช้งาน");
            }
            return;
        }

        if (requestCode == REQ_AUDIO && startVideoAfterAudioPermission) {
            startVideoAfterAudioPermission = false;
            if (!hasAudioPermission()) {
                Toast.makeText(
                        this,
                        "ไม่ได้อนุญาตไมโครโฟน — จะบันทึกวิดีโอแบบไม่มีเสียง",
                        Toast.LENGTH_LONG
                ).show();
            }
            startVideoRecording();
            return;
        }

        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                refreshLocation();
            } else {
                gpsStatus = "GPS: ไม่อนุญาต (ยังถ่ายได้)";
                updateCameraStatus();
                Toast.makeText(
                        this,
                        "ไม่ได้อนุญาตตำแหน่ง — ภาพและวิดีโอยังถ่ายได้ แต่จะไม่มี GPS metadata",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (locationHelper != null && hasLocationPermission()) {
            Location location = locationHelper.getBestLocation();
            if (location == null || LocationHelper.isStale(location)) {
                refreshLocation();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (activeRecording != null) {
            activeRecording.stop();
        }
        super.onDestroy();
    }
}
