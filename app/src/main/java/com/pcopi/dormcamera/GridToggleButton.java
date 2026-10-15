package com.pcopi.dormcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Compact toolbar control for the 3x3 grid. */
public final class GridToggleButton extends PressFeedbackButton {
    private static final String PREFS = "camera_settings";
    private static final String KEY_GRID = "grid_enabled";
    private boolean gridEnabled;

    public GridToggleButton(Context context) { super(context); }
    public GridToggleButton(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public GridToggleButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        gridEnabled = prefs.getBoolean(KEY_GRID, false);
        applyState();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        gridEnabled = !gridEnabled;
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GRID, gridEnabled).apply();
        applyState();
        return true;
    }

    private void applyState() {
        View overlay = getRootView().findViewById(R.id.gridOverlay);
        if (overlay != null) overlay.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
        setActivated(gridEnabled);
        setText("▦");
        setContentDescription(gridEnabled ? "ปิดเส้นกริด" : "เปิดเส้นกริด");
        refreshDrawableState();
    }
}
