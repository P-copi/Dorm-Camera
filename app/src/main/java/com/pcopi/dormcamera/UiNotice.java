package com.pcopi.dormcamera;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

/** Small professional in-app notice used for warnings and reliability messages. */
public final class UiNotice {
    private UiNotice() {}

    public static void show(Activity activity, String message) {
        if (activity == null) return;
        TextView banner = activity.findViewById(R.id.warningBanner);
        if (banner == null) return;
        banner.animate().cancel();
        banner.setText(message);
        banner.setAlpha(0f);
        banner.setTranslationY(18f);
        banner.setScaleX(0.97f);
        banner.setScaleY(0.97f);
        banner.setVisibility(View.VISIBLE);
        banner.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(170L)
                .withEndAction(() -> banner.postDelayed(() -> banner.animate()
                        .alpha(0f)
                        .translationY(12f)
                        .setDuration(220L)
                        .withEndAction(() -> banner.setVisibility(View.GONE))
                        .start(), 1800L))
                .start();
    }
}
