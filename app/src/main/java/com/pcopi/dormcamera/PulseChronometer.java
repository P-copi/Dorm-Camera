package com.pcopi.dormcamera;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Chronometer;

import androidx.annotation.Nullable;

/** Chronometer with a subtle REC pulse while recording. */
public final class PulseChronometer extends Chronometer {
    private ObjectAnimator pulseAnimator;

    public PulseChronometer(Context context) {
        super(context);
    }

    public PulseChronometer(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PulseChronometer(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void start() {
        super.start();
        stopPulse();
        pulseAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.58f, 1f);
        pulseAnimator.setDuration(1150L);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.RESTART);
        pulseAnimator.start();
    }

    @Override
    public void stop() {
        stopPulse();
        setAlpha(1f);
        super.stop();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }
}
