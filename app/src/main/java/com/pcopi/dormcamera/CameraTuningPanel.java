package com.pcopi.dormcamera;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.Camera;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ZoomState;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * Compact colorful UI controls for CameraX.
 * This panel changes actual exposure and zoom, not only visual labels.
 * The "night lights" presets are conservative AE bias, NOT OEM Night Extension
 * or saturation processing. Still/video GPS is left entirely untouched.
 */
public final class CameraTuningPanel {
    private final Activity activity;
    private final Button toggleButton;
    private final Button nightButton;
    private final LinearLayout sliders;
    private final SeekBar exposureBar;
    private final SeekBar zoomBar;
    private final TextView exposureValue;
    private final TextView zoomValue;
    private final Runnable turnOffFlash;
    private Camera camera;
    private boolean ready;
    private boolean recording;
    private boolean expanded;
    private int nightStyle;

    public CameraTuningPanel(Activity activity, Runnable turnOffFlash) {
        this.activity = activity;
        this.turnOffFlash = turnOffFlash;
        toggleButton = activity.findViewById(R.id.sliderToggleButton);
        nightButton = activity.findViewById(R.id.nightLightsButton);
        sliders = activity.findViewById(R.id.cameraSlidersPanel);
        exposureBar = activity.findViewById(R.id.exposureSeekBar);
        zoomBar = activity.findViewById(R.id.zoomSeekBar);
        exposureValue = activity.findViewById(R.id.exposureSliderValue);
        zoomValue = activity.findViewById(R.id.zoomSliderValue);

        exposureBar.setMax(1);
        zoomBar.setMax(1000);
        exposureBar.setEnabled(false);
        zoomBar.setEnabled(false);
        nightButton.setEnabled(false);
        toggleButton.setEnabled(false);

        toggleButton.setOnClickListener(v -> {
            expanded = !expanded;
            sliders.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggleButton.setText(expanded ? "🎚 แสง / ซูม  ▴" : "🎚 แสง / ซูม  ▾");
            toggleButton.setActivated(expanded);
            refreshFromCamera();
        });
        nightButton.setOnClickListener(v -> openNightLightingOptions());

        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user || camera == null || !ready || recording) return;
                ExposureState state = camera.getCameraInfo().getExposureState();
                int min = state.getExposureCompensationRange().getLower();
                int max = state.getExposureCompensationRange().getUpper();
                int index = Math.max(min, Math.min(max, min + progress));
                nightStyle = 0;
                renderNightButton();
                float ev = index * state.getExposureCompensationStep().floatValue();
                exposureValue.setText(String.format(Locale.US, "EV %+.1f", ev));
                camera.getCameraControl().setExposureCompensationIndex(index)
                        .addListener(() -> syncExposure(camera.getCameraInfo().getExposureState()),
                                ContextCompat.getMainExecutor(activity));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { refreshFromCamera(); }
        });

        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user || camera == null || !ready) return;
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) return;
                float ratio = state.getMinZoomRatio()
                        + (state.getMaxZoomRatio() - state.getMinZoomRatio())
                                * (progress / 1000f);
                camera.getCameraControl().setZoomRatio(ratio);
                zoomValue.setText(String.format(Locale.US, "%.1f×", ratio));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    public void bind(Camera camera) {
        this.camera = camera;
        this.ready = true;
        nightStyle = 0;
        renderNightButton();
        refreshFromCamera();
    }

    public void setCameraReady(boolean ready) {
        this.ready = ready;
        updateEnabled();
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
        updateEnabled();
    }

    public void syncExposure(ExposureState state) {
        if (state == null) return;
        int min = state.getExposureCompensationRange().getLower();
        int max = state.getExposureCompensationRange().getUpper();
        int current = state.getExposureCompensationIndex();
        float ev = current * state.getExposureCompensationStep().floatValue();
        exposureBar.setMax(Math.max(1, max - min));
        exposureBar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        exposureValue.setText(String.format(Locale.US, "EV %+.1f", ev));
        updateEnabled();
    }

    public void syncZoom(ZoomState state) {
        if (state == null) return;
        float min = state.getMinZoomRatio();
        float max = state.getMaxZoomRatio();
        int progress = max > min
                ? Math.round(1000f * (state.getZoomRatio() - min) / (max - min)) : 0;
        zoomBar.setProgress(Math.max(0, Math.min(1000, progress)));
        zoomValue.setText(String.format(Locale.US, "%.1f×", state.getZoomRatio()));
        updateEnabled();
    }

    private void refreshFromCamera() {
        if (camera == null) { updateEnabled(); return; }
        syncExposure(camera.getCameraInfo().getExposureState());
        syncZoom(camera.getCameraInfo().getZoomState().getValue());
    }

    private void updateEnabled() {
        toggleButton.setEnabled(ready);
        nightButton.setEnabled(ready && !recording);
        if (camera == null || !ready) {
            exposureBar.setEnabled(false);
            zoomBar.setEnabled(false);
            return;
        }
        ExposureState state = camera.getCameraInfo().getExposureState();
        exposureBar.setEnabled(!recording &&
                state.getExposureCompensationRange().getLower()
                    < state.getExposureCompensationRange().getUpper());
        ZoomState zoom = camera.getCameraInfo().getZoomState().getValue();
        zoomBar.setEnabled(zoom != null &&
                zoom.getMaxZoomRatio() > zoom.getMinZoomRatio());
    }

    private void openNightLightingOptions() {
        if (camera == null || !ready || recording) return;
        new AlertDialog.Builder(activity)
                .setTitle("🌙 แสงสีไฟยามค่ำ • ทดลอง")
                .setMessage("เลือกการชดเชยแสงเพื่อช่วยเก็บสีไฟประดับหรือเพิ่มแสงบรรยากาศ"
                        + "\nยังไม่ใช่ Night Mode แบบรวมหลายภาพ และไม่ได้เพิ่มความอิ่มสีของ JPEG")
                .setItems(new String[]{
                        "🌙 รักษาสีไฟ — ลดแสงประมาณ 0.4 EV",
                        "🌃 เพิ่มความสว่าง — เพิ่มแสงประมาณ 0.4 EV",
                        "↻ กลับแสงปกติ — EV 0"
                }, (dialog, which) -> setNightLightingStyle(which))
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void setNightLightingStyle(int style) {
        if (camera == null || !ready || recording) return;
        ExposureState state = camera.getCameraInfo().getExposureState();
        int min = state.getExposureCompensationRange().getLower();
        int max = state.getExposureCompensationRange().getUpper();
        float step = state.getExposureCompensationStep().floatValue();
        if (min >= max || step <= 0f) {
            Toast.makeText(activity, "กล้องนี้ไม่รองรับปรับชดเชยแสง", Toast.LENGTH_LONG).show();
            return;
        }
        float ev = style == 0 ? -0.4f : style == 1 ? 0.4f : 0f;
        int index = Math.max(min, Math.min(max, Math.round(ev / step)));
        if (style == 0 && index >= 0 && min < 0) index = -1;
        if (style == 1 && index <= 0 && max > 0) index = 1;
        if (style < 2) turnOffFlash.run();
        nightStyle = style == 2 ? 0 : style + 1;
        renderNightButton();
        camera.getCameraControl().setExposureCompensationIndex(index)
                .addListener(this::refreshFromCamera, ContextCompat.getMainExecutor(activity));
    }

    private void renderNightButton() {
        nightButton.setText(nightStyle == 1 ? "🌙 สีไฟ ✓"
                : nightStyle == 2 ? "🌃 เพิ่มแสง ✓" : "🌙 แสงสีไฟ");
        nightButton.setActivated(nightStyle != 0);
    }
}
