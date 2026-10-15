package com.pcopi.dormcamera;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * Compact camera-toolbar control for the Android media volume.
 * Gives a visible numeric level and slider so users never need to guess the hardware-key state.
 */
public final class SystemVolumeButton extends PressFeedbackButton {
    private AudioManager audioManager;

    public SystemVolumeButton(Context context) {
        super(context);
        init();
    }

    public SystemVolumeButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SystemVolumeButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        setOnClickListener(v -> showVolumeDialog());
        updateIcon();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context context = getContext();
        if (context instanceof Activity) {
            ((Activity) context).setVolumeControlStream(AudioManager.STREAM_MUSIC);
        }
        updateIcon();
    }

    private void showVolumeDialog() {
        if (audioManager == null) return;

        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(10), dp(22), dp(6));

        TextView levelText = new TextView(getContext());
        levelText.setGravity(Gravity.CENTER);
        levelText.setTextSize(16f);
        levelText.setTextColor(0xFF202124);
        levelText.setText("ระดับเสียง " + current + " / " + max);
        panel.addView(levelText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        ));

        SeekBar seekBar = new SeekBar(getContext());
        seekBar.setMax(max);
        seekBar.setProgress(current);
        panel.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
        ));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || audioManager == null) return;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                levelText.setText("ระดับเสียง " + progress + " / " + max);
                updateIcon();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("🔉  ระดับเสียง")
                .setView(panel)
                .setPositiveButton("ปิด", null)
                .create();
        dialog.setOnDismissListener(d -> updateIcon());
        dialog.show();
    }

    private void updateIcon() {
        if (audioManager == null) return;
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        if (current == 0) {
            setText("🔇");
        } else if (current * 2 < max) {
            setText("🔉");
        } else {
            setText("🔊");
        }
        setContentDescription("ปรับระดับเสียง ปัจจุบัน " + current + " จาก " + max);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
