package com.pcopi.dormcamera;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;

/**
 * Reliability wrapper around the proven V0.1.21 camera activity.
 * Adds physical volume shutter and safely stops/finalizes video when the app loses focus.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private boolean stoppedRecordingForInterruption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (isVolume && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Chronometer timer = findViewById(R.id.recordingTimer);
            boolean recording = timer != null && timer.getVisibility() == View.VISIBLE;
            Button shutter = findViewById(R.id.captureButton);
            if (!recording && shutter != null && shutter.isEnabled()) {
                shutter.animate().cancel();
                shutter.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60L)
                        .withEndAction(() -> shutter.animate().scaleX(1f).scaleY(1f).setDuration(90L).start())
                        .start();
                shutter.performClick();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
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
}
