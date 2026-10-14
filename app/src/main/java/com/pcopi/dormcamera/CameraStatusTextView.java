package com.pcopi.dormcamera;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Keeps the compact camera/GPS status visible while video recording.
 * Recording state is presented by the dedicated REC capsule instead of a long status sentence.
 */
public final class CameraStatusTextView extends AppCompatTextView {
    public CameraStatusTextView(Context context) {
        super(context);
    }

    public CameraStatusTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CameraStatusTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, TextView.BufferType type) {
        String raw = text == null ? "" : text.toString();

        // MainActivity used to replace the compact camera/GPS chip with a long sentence
        // while recording. Keep the existing compact status instead; REC + timer is shown
        // independently over the preview.
        if (raw.startsWith("กำลังบันทึก •")) {
            return;
        }

        // Keep the completion acknowledgement short; MainActivity restores normal status shortly after.
        if (raw.startsWith("บันทึกวิดีโอแล้ว →")) {
            super.setText("บันทึกวิดีโอแล้ว  ✓", type);
            return;
        }

        super.setText(text, type);
    }
}
