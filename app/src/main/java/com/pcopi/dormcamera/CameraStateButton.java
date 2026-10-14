package com.pcopi.dormcamera;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Adds a subtle selected state only to controls that represent a persistent camera setting.
 * Existing click behaviour remains owned by MainActivity.
 */
public final class CameraStateButton extends PressFeedbackButton {
    public CameraStateButton(Context context) {
        super(context);
    }

    public CameraStateButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CameraStateButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, TextView.BufferType type) {
        super.setText(text, type);
        updateActivatedState(text);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateActivatedState(getText());
    }

    private void updateActivatedState(CharSequence text) {
        String raw = text == null ? "" : text.toString().trim();
        boolean active = false;

        if (getId() == R.id.flashButton) {
            active = raw.contains("Auto") || raw.contains("เปิด");
        } else if (getId() == R.id.exposureResetButton) {
            String normalized = raw.replace(" ", "").replace("+", "");
            active = normalized.startsWith("EV")
                    && !normalized.equals("EV0")
                    && !normalized.equals("EV0.0")
                    && !normalized.equals("EV0.00")
                    && !normalized.equals("EV-0")
                    && !normalized.equals("EV-0.0")
                    && !normalized.equals("EV-0.00");
        }

        setActivated(active);
        refreshDrawableState();
    }
}
