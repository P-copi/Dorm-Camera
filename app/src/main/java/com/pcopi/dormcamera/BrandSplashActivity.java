package com.pcopi.dormcamera;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/** Fresh launcher component used to force Samsung/One UI to refresh the Dorm Camera icon. */
public final class BrandSplashActivity extends AppCompatActivity {
    private static final long HOLD_MS = 820L;
    private static final long FADE_MS = 180L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View root = findViewById(R.id.splashRoot);
        root.setAlpha(0f);
        root.setScaleX(0.985f);
        root.setScaleY(0.985f);
        root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .start();

        handler.postDelayed(() -> {
            if (isFinishing() || launched) return;
            root.animate()
                    .alpha(0f)
                    .setDuration(FADE_MS)
                    .withEndAction(this::openCamera)
                    .start();
        }, HOLD_MS);
    }

    private void openCamera() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, ReliabilityMainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
