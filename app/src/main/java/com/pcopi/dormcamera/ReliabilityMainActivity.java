package com.pcopi.dormcamera;

import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;

/**
 * Reliability wrapper around the proven camera activity.
 * V0.1.26 restores normal Android hardware-volume behavior and adds an explicit
 * toolbar volume slider, while preserving safe video finalization on interruption.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private static final String PREF_VOLUME_HELP_126 = "volume_help_0126_shown";
    private boolean stoppedRecordingForInterruption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        installSystemVolumeButton();
        showOneTimeVolumeHelp();
    }

    private void installSystemVolumeButton() {
        View grid = findViewById(R.id.gridButton);
        if (grid == null || !(grid.getParent() instanceof LinearLayout)) return;

        LinearLayout row = (LinearLayout) grid.getParent();
        SystemVolumeButton volumeButton = new SystemVolumeButton(this);
        volumeButton.setMinWidth(0);
        volumeButton.setMinHeight(0);
        volumeButton.setPadding(0, 0, 0, 0);
        volumeButton.setTextSize(18f);
        volumeButton.setTextColor(0xFFFFFFFF);
        volumeButton.setBackgroundResource(R.drawable.bg_button_dark);
        volumeButton.setBackgroundTintList(null);
        volumeButton.setStateListAnimator(null);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(40));
        params.setMarginStart(dp(6));
        int index = row.indexOfChild(grid);
        row.addView(volumeButton, Math.max(0, index), params);
    }

    private void showOneTimeVolumeHelp() {
        SharedPreferences prefs = getSharedPreferences("camera_settings", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_VOLUME_HELP_126, false)) return;
        prefs.edit().putBoolean(PREF_VOLUME_HELP_126, true).apply();
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.postDelayed(() -> UiNotice.show(
                    this,
                    "V0.1.26 • ปุ่มข้างเครื่องปรับเสียงตามปกติ • แตะ 🔉 เพื่อดูระดับเสียง"
            ), 650L);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
