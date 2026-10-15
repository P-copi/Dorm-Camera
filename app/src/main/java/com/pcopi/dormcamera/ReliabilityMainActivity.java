package com.pcopi.dormcamera;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.Chronometer;

/**
 * Reliability wrapper around the proven camera activity.
 * Short volume press keeps normal media-volume control, while a long press captures one photo.
 * Video recording still finalizes safely when the app loses focus.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private boolean stoppedRecordingForInterruption;
    private int activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean volumeLongPressTriggered;
    private Runnable volumeLongPressRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        cancelPendingVolumeLongPress();
        activeVolumeKey = keyCode;
        volumeLongPressTriggered = false;

        volumeLongPressRunnable = () -> {
            if (activeVolumeKey != keyCode || volumeLongPressTriggered) return;
            Button shutter = findViewById(R.id.captureButton);
            if (shutter == null || !shutter.isEnabled()) return;

            volumeLongPressTriggered = true;
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

        volumeHandler.postDelayed(volumeLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void finishVolumeGesture(int keyCode) {
        if (activeVolumeKey != keyCode) return;
        cancelPendingVolumeLongPress();

        if (!volumeLongPressTriggered) {
            adjustSystemVolume(keyCode);
        }

        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
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
    }

    private void cancelPendingVolumeLongPress() {
        if (volumeLongPressRunnable != null) {
            volumeHandler.removeCallbacks(volumeLongPressRunnable);
            volumeLongPressRunnable = null;
        }
    }

    private void resetVolumeGesture() {
        cancelPendingVolumeLongPress();
        activeVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeLongPressTriggered = false;
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
        super.onDestroy();
    }
}
