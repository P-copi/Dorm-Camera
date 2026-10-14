package com.pcopi.dormcamera;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;

/** Keeps MainActivity's existing video labels compact inside a round video control. */
public final class VideoControlButton extends PressFeedbackButton {
    public VideoControlButton(Context context) {
        super(context);
    }

    public VideoControlButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoControlButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, TextView.BufferType type) {
        String raw = text == null ? "" : text.toString();
        if (raw.contains("กำลังหยุด") || raw.contains("■")) {
            super.setText("■", type);
            setContentDescription("หยุดวิดีโอ");
            setActivated(true);
        } else if (raw.contains("วิดีโอ") || raw.contains("●")) {
            super.setText("●", type);
            setContentDescription("เริ่มบันทึกวิดีโอ");
            setActivated(false);
        } else {
            super.setText(text, type);
            setActivated(false);
        }
        refreshDrawableState();
    }
}
