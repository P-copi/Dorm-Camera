package com.pcopi.dormcamera;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Field;

/**
 * Reliability wrapper around the proven camera activity.
 * V0.2.0 keeps every proven camera behavior and adds only a separate personal-tools entry point.
 */
public final class ReliabilityMainActivity extends MainActivity {
    private static final String PREF_VOLUME_HELP_127 = "volume_help_0127_shown";
    private static final String PREF_SHUTTER_SOUND = "shutter_sound_enabled";

    private boolean stoppedRecordingForInterruption;
    private ShutterClickPlayer shutterClickPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Keep MainActivity's existing on/off preference and button UI, but replace only
        // its fixed-volume MediaActionSound engine with our variable-volume click.
        detachBuiltInShutterSound();
        shutterClickPlayer = new ShutterClickPlayer(this);

        SystemVolumeButton volumeButton = installSystemVolumeButton();
        if (volumeButton != null) {
            volumeButton.setPreviewSound(() -> {
                if (isShutterSoundEnabled() && shutterClickPlayer != null) {
                    shutterClickPlayer.playCurrentLevel();
                }
            });
        }
        installVariableShutterHook();
        installToolsOverlay();
        showOneTimeVolumeHelp();
    }

    private void detachBuiltInShutterSound() {
        try {
            Field field = MainActivity.class.getDeclaredField("shutterSound");
            field.setAccessible(true);
            Object current = field.get(this);
            if (current instanceof MediaActionSound) {
                try {
                    ((MediaActionSound) current).release();
                } catch (Exception ignored) {
                }
            }
            field.set(this, null);
        } catch (Exception ignored) {
            // If a future refactor renames the field, camera capture still works;
            // the build remains safe and only the variable-volume replacement is skipped.
        }
    }

    private boolean isShutterSoundEnabled() {
        SharedPreferences prefs = getSharedPreferences("camera_settings", MODE_PRIVATE);
        return prefs.getBoolean(PREF_SHUTTER_SOUND, true);
    }

    private void installVariableShutterHook() {
        Button capture = findViewById(R.id.captureButton);
        if (capture == null) return;

        capture.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && v.isEnabled()
                    && isShutterSoundEnabled()
                    && shutterClickPlayer != null) {
                shutterClickPlayer.playCurrentLevel();
            }
            // Returning false preserves the proven SafeCaptureButton click/storage behavior.
            return false;
        });
    }

    private SystemVolumeButton installSystemVolumeButton() {
        View grid = findViewById(R.id.gridButton);
        if (grid == null || !(grid.getParent() instanceof LinearLayout)) return null;

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
        return volumeButton;
    }

    private void installToolsOverlay() {
        View container = findViewById(R.id.previewContainer);
        if (!(container instanceof FrameLayout)) return;

        PressFeedbackButton tools = new PressFeedbackButton(this);
        tools.setText("เครื่องมือ");
        tools.setTextAllCaps(false);
        tools.setTextColor(0xFFFFFFFF);
        tools.setTextSize(11f);
        tools.setMinWidth(0);
        tools.setMinHeight(0);
        tools.setPadding(dp(8), 0, dp(8), 0);
        tools.setBackgroundResource(R.drawable.bg_button_dark);
        tools.setBackgroundTintList(null);
        tools.setStateListAnimator(null);
        tools.setContentDescription("เปิดเมนูเครื่องมือส่วนตัว");
        tools.setOnClickListener(v -> startActivity(new Intent(this, ToolsActivity.class)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(82), dp(36), Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(10), dp(10), 0);
        ((FrameLayout) container).addView(tools, params);
    }

    private void showOneTimeVolumeHelp() {
        SharedPreferences prefs = getSharedPreferences("camera_settings", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_VOLUME_HELP_127, false)) return;
        prefs.edit().putBoolean(PREF_VOLUME_HELP_127, true).apply();
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.postDelayed(() -> UiNotice.show(
                    this,
                    "V0.1.27 • เลื่อนระดับแล้วปล่อย เพื่อฟังเสียงชัตเตอร์ทดสอบ"
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

    @Override
    protected void onDestroy() {
        if (shutterClickPlayer != null) {
            shutterClickPlayer.release();
            shutterClickPlayer = null;
        }
        super.onDestroy();
    }
}
