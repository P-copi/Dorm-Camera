package com.pcopi.dormcamera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomImageView extends AppCompatImageView {
    private final Matrix matrix = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float userScale = 1f;
    private float lastX;
    private float lastY;

    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float oldScale = userScale;
                float newScale = Math.max(1f, Math.min(5f, oldScale * detector.getScaleFactor()));
                float relative = newScale / oldScale;
                userScale = newScale;
                matrix.postScale(relative, relative, detector.getFocusX(), detector.getFocusY());
                constrain();
                setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (userScale > 1.05f) {
                    resetZoom();
                } else {
                    userScale = 2f;
                    matrix.postScale(2f, 2f, e.getX(), e.getY());
                    constrain();
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
    }

    public boolean isZoomed() {
        return userScale > 1.05f;
    }

    public void resetZoom() {
        Drawable d = getDrawable();
        if (d == null || getWidth() <= 0 || getHeight() <= 0) return;

        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        float vw = getWidth() - getPaddingLeft() - getPaddingRight();
        float vh = getHeight() - getPaddingTop() - getPaddingBottom();
        float fit = Math.min(vw / dw, vh / dh);
        float dx = getPaddingLeft() + (vw - dw * fit) / 2f;
        float dy = getPaddingTop() + (vh - dh * fit) / 2f;

        matrix.reset();
        matrix.postScale(fit, fit);
        matrix.postTranslate(dx, dy);
        userScale = 1f;
        setImageMatrix(matrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::resetZoom);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        if (!scaleDetector.isInProgress()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (userScale > 1f && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        constrain();
                        setImageMatrix(matrix);
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    break;
                default:
                    break;
            }
        }
        return true;
    }

    private void constrain() {
        Drawable d = getDrawable();
        if (d == null) return;

        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);

        float viewW = getWidth();
        float viewH = getHeight();
        float dx = 0f;
        float dy = 0f;

        if (rect.width() <= viewW) {
            dx = viewW / 2f - rect.centerX();
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < viewW) {
            dx = viewW - rect.right;
        }

        if (rect.height() <= viewH) {
            dy = viewH / 2f - rect.centerY();
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < viewH) {
            dy = viewH - rect.bottom;
        }

        matrix.postTranslate(dx, dy);
    }
}
