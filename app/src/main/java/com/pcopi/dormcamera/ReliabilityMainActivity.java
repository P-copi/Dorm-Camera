package com.pcopi.dormcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.TextView;

import java.util.Locale;

/**
 * Reliability wrapper around the proven camera activity.
 * Short volume press keeps normal media-volume control, while a long press captures one photo.
 * V0.1.25 moves the gesture feedback to the always-visible camera status chip so Samsung/CameraX
 * preview composition cannot hide it.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private static final long LONG_PRESS_MS = ViewConfiguration.getLongPressTimeout();
    private static final long PROGRESS_TICK_MS = 80L;
    private static final long SHORT_STATUS_MS = 950L;
    private static final String PREF_VOLUME_HELP_125 = "volume_help_0125_shown";

    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private boolean stoppedRecordingForInterruption;
    private int activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean volumeLongPressTriggered;
    private long volumeGestureStartedAt;
    private int lastProgressStep = -1;
    private Runnable volumeLongPressRunnable;
    private Runnable volumeProgressRunnable;
    private Runnable statusRestoreRunnable;
    private String statusBeforeGesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOneTimeVolumeHelp();
    }

    private void showOneTimeVolumeHelp() {
        SharedPreferences prefs = getSharedPreferences("camera_settings", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_VOLUME_HELP_125, false)) return;
        prefs.edit().putBoolean(PREF_VOLUME_HELP_125, true).apply();
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.postDelayed(() -> UiNotice.show(
                    this,
                    "V0.1.25 • แตะ Volume = ปรับเสียง • ค้าง = ถ่ายภาพ"
            ), 650L);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!isVolume) return super.dispatchKeyEvent(event);

        Chronometer timer = findViewById(R.id.recordingTimer);
        boolean recording = timer != null && timer.getVisibility() == View.VISIBLE;
        if (recording) {
            resetVolumeGesture(false);
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) beginVolumeGesture(code);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            finishVolumeGesture(code);
            return true;
        }

        return true;
    }

    private void beginVolumeGesture(int keyCode) {
        cancelGestureCallbacks();
        cancelStatusRestore();
        activeVolumeKey = keyCode;
        volumeLongPressTriggered = false;
        volumeGestureStartedAt = SystemClock.uptimeMillis();
        lastProgressStep = -1;

        TextView status = findViewById(R.id.statusText);
        statusBeforeGesture = status == null ? null : String.valueOf(status.getText());
        updateHoldProgress(0);

        volumeProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeVolumeKey != keyCode || volumeLongPressTriggered) return;
                long elapsed = Math.max(0L, SystemClock.uptimeMillis() - volumeGestureStartedAt);
                int step = (int) Math.min(3L, (elapsed * 4L) / Math.max(1L, LONG_PRESS_MS));
                updateHoldProgress(step);
                volumeHandler.postDelayed(this, PROGRESS_TICK_MS);
            }
        };
        volumeHandler.post(volumeProgressRunnable);

        volumeLongPressRunnable = () -> {
            if (activeVolumeKey != keyCode || volumeLongPressTriggered) return;
            Button shutter = findViewById(R.id.captureButton);
            if (shutter == null || !shutter.isEnabled()) {
                showStatusTemporarily("กล้องยังไม่พร้อม", 700L);
                return;
            }

            volumeLongPressTriggered = true;
            cancelProgressCallback();
            showStatus("ค้างเพื่อถ่าย  ■■■■■  100%");
            shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

            shutter.animate().cancel();
            shutter.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(60L)
                    .withEndAction(() -> shutter.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(90L)
                            .start())
                    .start();

            // MainActivity immediately replaces the status with its normal capture/saved messages.
            shutter.performClick();
        };

        volumeHandler.postDelayed(volumeLongPressRunnable, LONG_PRESS_MS);
    }

    private void updateHoldProgress(int step) {
        if (step == lastProgressStep) return;
        lastProgressStep = step;
        int filled = Math.max(1, Math.min(4, step + 1));
        int percent = filled * 20;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 5; i++) bar.append(i < filled ? '■' : '□');
        showStatus("ค้างเพื่อถ่าย  " + bar + "  " + percent + "%");
    }

    private void finishVolumeGesture(int keyCode) {
        if (activeVolumeKey != keyCode) return;
        cancelGestureCallbacks();

        if (!volumeLongPressTriggered) {
            adjustSystemVolume(keyCode);
        }

        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
        lastProgressStep = -1;
    }

    private void adjustSystemVolume(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            restoreStatusSoon();
            return;
        }

        int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
        );

        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        String symbol = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "+" : "−";
        showStatus(String.format(Locale.US, "🔊  เสียง %s   ระดับ %d/%d", symbol, current, max));
        restoreStatusSoon();
    }

    private void showStatus(String text) {
        TextView status = findViewById(R.id.statusText);
        if (status == null) return;
        status.animate().cancel();
        status.setAlpha(1f);
        status.setText(text);
    }

    private void showStatusTemporarily(String text, long durationMs) {
        showStatus(text);
        cancelStatusRestore();
        statusRestoreRunnable = this::restoreStatus;
        volumeHandler.postDelayed(statusRestoreRunnable, durationMs);
    }

    private void restoreStatusSoon() {
        cancelStatusRestore();
        statusRestoreRunnable = this::restoreStatus;
        volumeHandler.postDelayed(statusRestoreRunnable, SHORT_STATUS_MS);
    }

    private void restoreStatus() {
        statusRestoreRunnable = null;
        TextView status = findViewById(R.id.statusText);
        if (status != null && statusBeforeGesture != null && !statusBeforeGesture.isEmpty()) {
            status.setText(statusBeforeGesture);
        }
        statusBeforeGesture = null;
    }

    private void cancelStatusRestore() {
        if (statusRestoreRunnable != null) {
            volumeHandler.removeCallbacks(statusRestoreRunnable);
            statusRestoreRunnable = null;
        }
    }

    private void cancelProgressCallback() {
        if (volumeProgressRunnable != null) {
            volumeHandler.removeCallbacks(volumeProgressRunnable);
            volumeProgressRunnable = null;
        }
    }

    private void cancelGestureCallbacks() {
        if (volumeLongPressRunnable != null) {
            volumeHandler.removeCallbacks(volumeLongPressRunnable);
            volumeLongPressRunnable = null;
        }
        cancelProgressCallback();
    }

    private void resetVolumeGesture(boolean restoreStatus) {
        cancelGestureCallbacks();
        cancelStatusRestore();
        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
        lastProgressStep = -1;
        if (restoreStatus) restoreStatus();
        else statusBeforeGesture = null;
    }

    @Override
    protected void onPause() {
        resetVolumeGesture(false);

        Chronometer timer = findViewById(R.id.recordingTimer);
        Button video = findViewById(R.id.videoButton);
        boolean recording = timer != null && timer.getVisibility() == View.VISIBLE;
        if (recording && video != null && video.isEnabled()) {
            stoppedRecordingForInterruption = true;
            video.performClick();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stoppedRecordingForInterruption) {
            stoppedRecordingForInterruption = false;
            View root = findViewById(android.R.id.content);
            if (root != null) {
                root.postDelayed(() -> UiNotice.show(this,
                        "วิดีโอถูกหยุดและบันทึกอย่างปลอดภัยหลังแอปถูกขัดจังหวะ"), 300L);
            }
        }
    }

    @Override
    protected void onDestroy() {
        resetVolumeGesture(false);
        volumeHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
