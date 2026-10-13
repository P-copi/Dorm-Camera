package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ScaleGestureDetector;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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

    private PreviewView previewView;
    private TextView statusText;
    private TextView zoomText;
    private Button captureButton;
    private Button videoButton;
    private Button switchButton;
    private Button zoomOutButton;
    private Button zoomResetButton;
    private Button zoomInButton;
    private Button folderButton;
    private Button explorerButton;

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
    private FolderStore folderStore;

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
        folderButton = findViewById(R.id.folderButton);
        explorerButton = findViewById(R.id.explorerButton);

        locationHelper = new LocationHelper(this);
        folderStore = new FolderStore(this);

        setCameraControlsEnabled(false);

        captureButton.setOnClickListener(v -> takePhoto());
        videoButton.setOnClickListener(v -> toggleVideoRecording());
        switchButton.setOnClickListener(v -> switchCamera());
        zoomOutButton.setOnClickListener(v -> multiplyZoom(0.8f));
        zoomResetButton.setOnClickListener(v -> setZoomRatio(1.0f));
        zoomInButton.setOnClickListener(v -> multiplyZoom(1.25f));
        folderButton.setOnClickListener(v -> showFolderMenu());
        explorerButton.setOnClickListener(v -> openExplorer());

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
        folderButton.setEnabled(enabled);
        explorerButton.setEnabled(enabled);
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
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
        if (cameraProvider == null || activeRecording != null) return;

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

        float safeRatio = Math.max(
                state.getMinZoomRatio(),
                Math.min(state.getMaxZoomRatio(), requestedRatio)
        );
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
        String folder = folderStore.getCurrentFolder();
        String relativePath = FolderStore.pictureRelativePath(folder);
        String displayPath = relativePath.substring(0, relativePath.length() - 1);

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
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);

            options = new ImageCapture.OutputFileOptions.Builder(
                    getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            ).setMetadata(metadata).build();
        } else {
            File dormDir = FolderStore.legacyPictureDir(folder);
            if (!dormDir.exists() && !dormDir.mkdirs()) {
                showError("สร้างโฟลเดอร์ภาพไม่สำเร็จ");
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
        statusText.setText("กำลังบันทึกภาพ • " + displayPath + (hasGps ? " • GPS ✓" : " • ไม่มี GPS"));
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

                        statusText.setText("บันทึกภาพแล้ว • " + displayPath + (hasGps ? " • GPS ✓" : ""));
                        Toast.makeText(
                                MainActivity.this,
                                "บันทึกภาพแล้ว\n" + displayPath + "\n" + fileName,
                                Toast.LENGTH_LONG
                        ).show();
                        statusText.postDelayed(() -> updateCameraStatus(), 1500L);
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
        String folder = folderStore.getCurrentFolder();
        String relativePath = FolderStore.videoRelativePath(folder);
        String displayPath = relativePath.substring(0, relativePath.length() - 1);

        currentCaptureLocation = prepareLocationForCapture();
        Location videoLocation = currentCaptureLocation;

        PendingRecording pendingRecording;
        currentLegacyVideoFile = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath);

            MediaStoreOutputOptions.Builder builder = new MediaStoreOutputOptions.Builder(
                    getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values);

            if (videoLocation != null) builder.setLocation(videoLocation);
            pendingRecording = videoCapture.getOutput().prepareRecording(this, builder.build());
        } else {
            File dormDir = FolderStore.legacyVideoDir(folder);
            if (!dormDir.exists() && !dormDir.mkdirs()) {
                showError("สร้างโฟลเดอร์วิดีโอไม่สำเร็จ");
                return;
            }
            currentLegacyVideoFile = new File(dormDir, fileName);
            FileOutputOptions.Builder builder = new FileOutputOptions.Builder(currentLegacyVideoFile);
            if (videoLocation != null) builder.setLocation(videoLocation);
            pendingRecording = videoCapture.getOutput().prepareRecording(this, builder.build());
        }

        if (hasAudioPermission()) {
            pendingRecording = pendingRecording.withAudioEnabled();
        }

        captureButton.setEnabled(false);
        switchButton.setEnabled(false);
        folderButton.setEnabled(false);
        explorerButton.setEnabled(false);
        videoButton.setEnabled(true);
        videoButton.setText("หยุดวิดีโอ");

        final boolean hasGps = videoLocation != null;
        activeRecording = pendingRecording.start(
                ContextCompat.getMainExecutor(this),
                event -> handleVideoEvent(event, fileName, displayPath, hasGps)
        );
    }

    private void handleVideoEvent(VideoRecordEvent event, String fileName, String displayPath, boolean hasGps) {
        if (event instanceof VideoRecordEvent.Start) {
            String side = lensFacing == CameraSelector.LENS_FACING_BACK ? "กล้องหลัง" : "กล้องหน้า";
            statusText.setText("กำลังบันทึกวิดีโอ • " + side + " • " + displayPath + (hasGps ? " • GPS ✓" : ""));
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
            folderButton.setEnabled(true);
            explorerButton.setEnabled(true);

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

            statusText.setText("บันทึกวิดีโอแล้ว • " + displayPath + (hasGps ? " • GPS ✓" : ""));
            Toast.makeText(
                    this,
                    "บันทึกวิดีโอแล้ว\n" + displayPath + "\n" + fileName,
                    Toast.LENGTH_LONG
            ).show();
            statusText.postDelayed(this::updateCameraStatus, 1500L);
        }
    }

    private void showFolderMenu() {
        if (activeRecording != null) {
            Toast.makeText(this, "หยุดวิดีโอก่อนจัดการโฟลเดอร์", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean locked = folderStore.isLocked();
        String current = FolderStore.displayName(folderStore.getCurrentFolder());
        String lockAction = locked ? "ปลดล็อกโฟลเดอร์" : "ล็อกโฟลเดอร์ปัจจุบัน";
        String[] choices = new String[]{
                "เลือกโฟลเดอร์",
                "สร้างโฟลเดอร์ใหม่",
                "เปลี่ยนชื่อโฟลเดอร์ปัจจุบัน",
                lockAction
        };

        new AlertDialog.Builder(this)
                .setTitle("โฟลเดอร์บันทึก: " + current + (locked ? " 🔒" : ""))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) showSelectFolderDialog();
                    else if (which == 1) showCreateFolderDialog();
                    else if (which == 2) showRenameFolderDialog();
                    else toggleFolderLock();
                })
                .setNegativeButton("ปิด", null)
                .show();
    }

    private void showSelectFolderDialog() {
        if (folderStore.isLocked()) {
            Toast.makeText(this, "โฟลเดอร์ถูกล็อกอยู่ กรุณาปลดล็อกก่อน", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> folders = folderStore.getKnownFolders();
        String[] labels = new String[folders.size()];
        int checked = 0;
        String current = folderStore.getCurrentFolder();
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = FolderStore.displayName(folders.get(i));
            if (folders.get(i).equals(current)) checked = i;
        }

        final int[] selected = new int[]{checked};
        new AlertDialog.Builder(this)
                .setTitle("เลือกโฟลเดอร์บันทึก")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> selected[0] = which)
                .setPositiveButton("ใช้โฟลเดอร์นี้", (dialog, which) -> {
                    folderStore.setCurrentFolder(folders.get(selected[0]));
                    updateCameraStatus();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showCreateFolderDialog() {
        if (folderStore.isLocked()) {
            Toast.makeText(this, "โฟลเดอร์ถูกล็อกอยู่ กรุณาปลดล็อกก่อน", Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("เช่น ห้อง101 หรือ งานวันที่13");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("สร้างโฟลเดอร์ใหม่")
                .setView(input)
                .setPositiveButton("สร้างและใช้", (dialog, which) -> {
                    String name = FolderStore.normalize(input.getText().toString());
                    if (name.isEmpty()) {
                        Toast.makeText(this, "กรุณาใส่ชื่อโฟลเดอร์", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    folderStore.addFolder(name);
                    folderStore.setCurrentFolder(name);
                    updateCameraStatus();
                    Toast.makeText(this, "ใช้โฟลเดอร์: " + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showRenameFolderDialog() {
        if (folderStore.isLocked()) {
            Toast.makeText(this, "โฟลเดอร์ถูกล็อกอยู่ กรุณาปลดล็อกก่อน", Toast.LENGTH_LONG).show();
            return;
        }

        String oldName = folderStore.getCurrentFolder();
        if (oldName.isEmpty()) {
            Toast.makeText(this, "โฟลเดอร์หลักเปลี่ยนชื่อไม่ได้ ให้สร้างโฟลเดอร์ใหม่แทน", Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(oldName);
        input.setSelection(input.length());

        new AlertDialog.Builder(this)
                .setTitle("เปลี่ยนชื่อโฟลเดอร์")
                .setView(input)
                .setPositiveButton("เปลี่ยนชื่อ", (dialog, which) -> {
                    String newName = FolderStore.normalize(input.getText().toString());
                    if (newName.isEmpty() || newName.equals(oldName)) return;
                    renameFolderOnStorage(oldName, newName);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void renameFolderOnStorage(@NonNull String oldName, @NonNull String newName) {
        folderButton.setEnabled(false);
        statusText.setText("กำลังเปลี่ยนชื่อโฟลเดอร์...");

        new Thread(() -> {
            boolean success;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                success = moveMediaStorePath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        FolderStore.pictureRelativePath(oldName),
                        FolderStore.pictureRelativePath(newName)
                );
                success = moveMediaStorePath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        FolderStore.videoRelativePath(oldName),
                        FolderStore.videoRelativePath(newName)
                ) && success;
            } else {
                success = renameLegacyDir(
                        FolderStore.legacyPictureDir(oldName),
                        FolderStore.legacyPictureDir(newName)
                );
                success = renameLegacyDir(
                        FolderStore.legacyVideoDir(oldName),
                        FolderStore.legacyVideoDir(newName)
                ) && success;
            }

            boolean finalSuccess = success;
            runOnUiThread(() -> {
                folderButton.setEnabled(true);
                if (finalSuccess) {
                    folderStore.renameFolder(oldName, newName);
                    updateCameraStatus();
                    Toast.makeText(this, "เปลี่ยนชื่อเป็น " + newName + " แล้ว", Toast.LENGTH_LONG).show();
                } else {
                    showError("เปลี่ยนชื่อโฟลเดอร์ไม่สำเร็จ");
                }
            });
        }).start();
    }

    private boolean moveMediaStorePath(Uri collection, String oldPath, String newPath) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, newPath);
            getContentResolver().update(
                    collection,
                    values,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{oldPath}
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean renameLegacyDir(File oldDir, File newDir) {
        if (!oldDir.exists()) return true;
        if (newDir.exists()) return false;
        File parent = newDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        return oldDir.renameTo(newDir);
    }

    private void toggleFolderLock() {
        boolean locked = !folderStore.isLocked();
        folderStore.setLocked(locked);
        updateCameraStatus();
        Toast.makeText(
                this,
                locked ? "ล็อกโฟลเดอร์บันทึกแล้ว" : "ปลดล็อกโฟลเดอร์แล้ว",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void openExplorer() {
        if (activeRecording != null) {
            Toast.makeText(this, "หยุดวิดีโอก่อนเปิด Explorer", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, ExplorerActivity.class));
    }

    private void updateCameraStatus() {
        if (statusText == null || folderStore == null) return;
        String side = lensFacing == CameraSelector.LENS_FACING_BACK ? "กล้องหลัง" : "กล้องหน้า";
        String folder = FolderStore.displayName(folderStore.getCurrentFolder());
        String lock = folderStore.isLocked() ? " 🔒" : "";
        statusText.setText(
                "Dorm Camera • " + side
                        + " • " + gpsStatus
                        + " • โฟลเดอร์: " + folder + lock
        );
    }

    private void showError(String message) {
        if (statusText != null) statusText.setText(message);
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
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCameraStatus();
        if (locationHelper != null && hasLocationPermission()) {
            Location location = locationHelper.getBestLocation();
            if (location == null || LocationHelper.isStale(location)) refreshLocation();
        }
    }

    @Override
    protected void onDestroy() {
        if (activeRecording != null) activeRecording.stop();
        super.onDestroy();
    }
}
