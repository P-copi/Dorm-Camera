package com.pcopi.dormcamera;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

/** Shutter button that refuses a capture only when storage is critically low. */
public final class SafeCaptureButton extends PressFeedbackButton {
    public SafeCaptureButton(Context context) { super(context); }
    public SafeCaptureButton(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public SafeCaptureButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean performClick() {
        if (!StorageGuard.checkBeforePhoto(getContext())) return false;
        return super.performClick();
    }
}
