package com.pcopi.dormcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Thin non-interactive 3x3 composition grid. */
public final class GridOverlayView extends View {
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GridOverlayView(Context context) {
        super(context);
        init();
    }

    public GridOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GridOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(false);
        setFocusable(false);
        float density = getResources().getDisplayMetrics().density;
        shadowPaint.setColor(0x55000000);
        shadowPaint.setStrokeWidth(Math.max(2f, 1.8f * density));
        linePaint.setColor(0x70FFFFFF);
        linePaint.setStrokeWidth(Math.max(1f, 0.7f * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float x1 = w / 3f;
        float x2 = w * 2f / 3f;
        float y1 = h / 3f;
        float y2 = h * 2f / 3f;

        drawGrid(canvas, x1, x2, y1, y2, shadowPaint);
        drawGrid(canvas, x1, x2, y1, y2, linePaint);
    }

    private void drawGrid(Canvas canvas, float x1, float x2, float y1, float y2, Paint paint) {
        canvas.drawLine(x1, 0f, x1, getHeight(), paint);
        canvas.drawLine(x2, 0f, x2, getHeight(), paint);
        canvas.drawLine(0f, y1, getWidth(), y1, paint);
        canvas.drawLine(0f, y2, getWidth(), y2, paint);
    }
}
