package com.pcopi.dormcamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;

/** Guards photo/video capture from critically low free storage. */
public final class StorageGuard {
    private static final long MIB = 1024L * 1024L;
    private static final long PHOTO_CRITICAL = 64L * MIB;
    private static final long VIDEO_CRITICAL = 512L * MIB;
    private static final long LOW_WARNING = 1024L * MIB;

    private StorageGuard() {}

    public static boolean checkBeforePhoto(Context context) {
        return check(context, PHOTO_CRITICAL, "ถ่ายภาพ");
    }

    public static boolean checkBeforeVideo(Context context) {
        return check(context, VIDEO_CRITICAL, "บันทึกวิดีโอ");
    }

    private static boolean check(Context context, long criticalBytes, String action) {
        long free = getAvailableBytes();
        if (free <= 0L) return true;

        Activity activity = findActivity(context);
        String freeText = Formatter.formatFileSize(context, free);
        if (free < criticalBytes) {
            if (activity != null) {
                UiNotice.show(activity, "พื้นที่ไม่เพียงพอสำหรับ" + action + "  •  เหลือ " + freeText);
            }
            return false;
        }
        if (free < LOW_WARNING && activity != null) {
            UiNotice.show(activity, "พื้นที่จัดเก็บเหลือน้อย  •  เหลือ " + freeText);
        }
        return true;
    }

    private static long getAvailableBytes() {
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
