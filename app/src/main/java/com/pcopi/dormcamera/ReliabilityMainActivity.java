package com.pcopi.dormcamera;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Reliability wrapper around the proven camera activity.
 * Short volume press keeps normal media-volume control, while a long press captures one photo.
 * V0.1.24 adds a compact on-screen indicator so the short/long gesture is obvious.
 * Video recording still finalizes safely when the app loses focus.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private static final long LONG_PRESS_MS = ViewConfiguration.getLongPressTimeout();
    private static final long PROGRESS_TICK_MS = 80L;

    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private boolean stoppedRecordingForInterruption;
    private int activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean volumeLongPressTriggered;
    private long volumeGestureStartedAt;
    private int lastProgressStep = -1;
    private Runnable volumeLongPressRunnable;
    private Runnable volumeProgressRunnable;
    private Runnable volumeHintHideRunnable;
    private TextView volumeGestureHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installVolumeGestureHint();
    }

    private void installVolumeGestureHint() {
        FrameLayout previewContainer = findViewById(R.id.previewContainer);
        if (previewContainer == null) return;

        volumeGestureHint = new TextView(this);
        volumeGestureHint.setGravity(Gravity.CENTER);
        volumeGestureHint.setTextColor(0xFFF7F7FA);
        volumeGestureHint.setTextSize(12f);
        volumeGestureHint.setSingleLine(true);
        volumeGestureHint.setPadding(dp(14), 0, dp(14), 0);
        volumeGestureHint.setBackgroundResource(R.drawable.bg_info_chip);
        volumeGestureHint.setElevation(dp(8));
        volumeGestureHint.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        params.topMargin = dp(48);
        previewContainer.addView(volumeGestureHint, params);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!isVolume) return super.dispatchKeyEvent(event);

        Chronometer timer = findViewById(R.id.recordingTimer);
        boolean recording = timer != null && timer.getVisibility() == View.VISIBLE;
        if (recording) {
            // While recording, never trigger a photo. Keep normal Android volume behaviour.
            hideVolumeHintNow();
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
        cancelVolumeGestureCallbacks();
        activeVolumeKey = keyCode;
        volumeLongPressTriggered = false;
        volumeGestureStartedAt = SystemClock.uptimeMillis();
        lastProgressStep = -1;

        updateHoldProgress(0);
        volumeProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeVolumeKey != keyCode || volumeLongPressTriggered) return;
                long elapsed = Math.max(0L, SystemClock.uptimeMillis() - volumeGestureStartedAt);
                int step = (int) Math.min(4L, (elapsed * 5L) / Math.max(1L, LONG_PRESS_MS));
                updateHoldProgress(step);
                volumeHandler.postDelayed(this, PROGRESS_TICK_MS);
            }
        };
        volumeHandler.post(volumeProgressRunnable);

        volumeLongPressRunnable = () -> {
            if (activeVolumeKey != keyCode || volumeLongPressTriggered) return;
            Button shutter = findViewById(R.id.captureButton);
            if (shutter == null || !shutter.isEnabled()) {
                showTemporaryHint("กล้องยังไม่พร้อม", 650L);
                return;
            }

            volumeLongPressTriggered = true;
            cancelProgressCallback();
            showTemporaryHint("✓  ถ่ายแล้ว", 650L);

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
            shutter.performClick();
        };

        volumeHandler.postDelayed(volumeLongPressRunnable, LONG_PRESS_MS);
    }

    private void updateHoldProgress(int step) {
        if (volumeGestureHint == null || step == lastProgressStep) return;
        lastProgressStep = step;

        int filled = Math.max(0, Math.min(5, step + 1));
        int percent = Math.min(80, Math.max(20, filled * 20));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 5; i++) bar.append(i < filled ? '■' : '□');
        showPersistentHint("ค้างเพื่อถ่าย  " + bar + "  " + percent + "%");
    }

    private void finishVolumeGesture(int keyCode) {
        if (activeVolumeKey != keyCode) return;
        cancelVolumeGestureCallbacks();

        if (!volumeLongPressTriggered) {
            adjustSystemVolume(keyCode);
        }

        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
        lastProgressStep = -1;
    }

    private void adjustSystemVolume(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

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
        showTemporaryHint(
                String.format(Locale.US, "เสียง %s   ระดับ %d/%d", symbol, current, max),
                850L
        );
    }

    private void showPersistentHint(String text) {
        if (volumeGestureHint == null) return;
        if (volumeHintHideRunnable != null) {
            volumeHandler.removeCallbacks(volumeHintHideRunnable);
            volumeHintHideRunnable = null;
        }
        volumeGestureHint.animate().cancel();
        volumeGestureHint.setText(text);
        if (volumeGestureHint.getVisibility() != View.VISIBLE) {
            volumeGestureHint.setAlpha(0f);
            volumeGestureHint.setScaleX(0.97f);
            volumeGestureHint.setScaleY(0.97f);
            volumeGestureHint.setVisibility(View.VISIBLE);
            volumeGestureHint.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .start();
        }
    }

    private void showTemporaryHint(String text, long durationMs) {
        showPersistentHint(text);
        if (volumeGestureHint == null) return;
        volumeHintHideRunnable = () -> {
            if (volumeGestureHint == null) return;
            volumeGestureHint.animate().cancel();
            volumeGestureHint.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction(() -> volumeGestureHint.setVisibility(View.GONE))
                    .start();
        };
        volumeHandler.postDelayed(volumeHintHideRunnable, durationMs);
    }

    private void hideVolumeHintNow() {
        if (volumeGestureHint == null) return;
        if (volumeHintHideRunnable != null) {
            volumeHandler.removeCallbacks(volumeHintHideRunnable);
            volumeHintHideRunnable = null;
        }
        volumeGestureHint.animate().cancel();
        volumeGestureHint.setVisibility(View.GONE);
        volumeGestureHint.setAlpha(1f);
    }

    private void cancelProgressCallback() {
        if (volumeProgressRunnable != null) {
            volumeHandler.removeCallbacks(volumeProgressRunnable);
            volumeProgressRunnable = null;
        }
    }

    private void cancelVolumeGestureCallbacks() {
        if (volumeLongPressRunnable != null) {
            volumeHandler.removeCallbacks(volumeLongPressRunnable);
            volumeLongPressRunnable = null;
        }
        cancelProgressCallback();
    }

    private void resetVolumeGesture() {
        cancelVolumeGestureCallbacks();
        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
        lastProgressStep = -1;
        hideVolumeHintNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        resetVolumeGesture();

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
        resetVolumeGesture();
        volumeHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
