package com.pcopi.dormcamera;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * V0.2.0 read-only Photo Navigator.
 * Selects any image available through Android's document picker, reads GPS EXIF,
 * then hands the coordinate to an installed map/navigation app.
 * It never modifies the selected file.
 */
public final class PhotoNavigatorActivity extends AppCompatActivity {
    private static final int REQ_PICK_IMAGE = 2200;

    private ImageView imageView;
    private TextView fileText;
    private TextView statusText;
    private TextView gpsText;
    private TextView dateText;
    private PressFeedbackButton openMapButton;
    private PressFeedbackButton navigateButton;

    private double latitude;
    private double longitude;
    private boolean hasGps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        showEmptyState();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.setBackgroundColor(0xFF050506);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        PressFeedbackButton back = makeButton("←", 15f);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(42)));

        TextView title = new TextView(this);
        title.setText("นำทางจากภาพ");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(21f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView safe = new TextView(this);
        safe.setText("อ่านอย่างเดียว • ไม่แก้ไขภาพต้นฉบับ");
        safe.setTextColor(0xFF8FC9A3);
        safe.setTextSize(12f);
        safe.setGravity(Gravity.CENTER);
        safe.setPadding(0, dp(7), 0, dp(8));
        root.addView(safe, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(0xFF111216);
        imageView.setContentDescription("ภาพที่เลือกสำหรับอ่านตำแหน่ง GPS");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        imageParams.setMargins(0, 0, 0, dp(10));
        root.addView(imageView, imageParams);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), dp(10), dp(14), dp(10));
        info.setBackgroundResource(R.drawable.bg_control_panel);

        fileText = infoText(14f, 0xFFFFFFFF);
        statusText = infoText(13f, 0xFFD8D9DE);
        gpsText = infoText(13f, 0xFFEDEDF2);
        dateText = infoText(12f, 0xFFB9BBC2);
        info.addView(fileText);
        info.addView(statusText);
        info.addView(gpsText);
        info.addView(dateText);
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PressFeedbackButton choose = makeButton("🖼  เลือกภาพ", 15f);
        choose.setOnClickListener(v -> pickImage());
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        chooseParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(choose, chooseParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        openMapButton = makeButton("🗺  เปิดแผนที่", 14f);
        openMapButton.setOnClickListener(v -> openMap());
        actions.addView(openMapButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        navigateButton = makeButton("🧭  นำทาง", 14f);
        navigateButton.setOnClickListener(v -> navigate());
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        navParams.setMarginStart(dp(8));
        actions.addView(navigateButton, navParams);
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView privacy = new TextView(this);
        privacy.setText("หมายเหตุ: ถ้าแชร์ภาพแบบไฟล์ ข้อมูล GPS ใน EXIF อาจติดไปกับภาพด้วย");
        privacy.setTextColor(0xFF8F929A);
        privacy.setTextSize(11f);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(4), dp(9), dp(4), 0);
        root.addView(privacy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
            // Some providers do not offer persistable permission. Reading this selection still works.
        }
        readImage(uri);
    }

    private void readImage(Uri uri) {
        hasGps = false;
        setActionEnabled(false);
        imageView.setImageURI(null);
        imageView.setImageURI(uri);
        fileText.setText("ไฟล์: " + displayName(uri));
        statusText.setText("กำลังอ่านข้อมูลตำแหน่ง...");
        gpsText.setText("");
        dateText.setText("");

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("เปิดไฟล์ไม่ได้");
            ExifInterface exif = new ExifInterface(input);
            double[] latLong = exif.getLatLong();

            String date = firstNotBlank(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                    exif.getAttribute(ExifInterface.TAG_DATETIME));
            dateText.setText(date == null ? "วันที่ใน EXIF: ไม่พบข้อมูล" : "วันที่ใน EXIF: " + date);

            if (latLong == null || latLong.length < 2) {
                statusText.setText("ไม่พบ GPS ในภาพนี้");
                gpsText.setText("V0.2.1 จะเพิ่มความสามารถกำหนดตำแหน่งให้ภาพภายหลัง");
                setActionEnabled(false);
                return;
            }

            latitude = latLong[0];
            longitude = latLong[1];
            hasGps = isValidCoordinate(latitude, longitude);
            if (!hasGps) {
                statusText.setText("พบข้อมูล GPS แต่พิกัดไม่ถูกต้อง");
                gpsText.setText("");
                setActionEnabled(false);
                return;
            }

            statusText.setText("✓ พบตำแหน่ง GPS ในภาพ");
            gpsText.setText(String.format(Locale.US, "พิกัด: %.6f, %.6f", latitude, longitude));
            setActionEnabled(true);
        } catch (Exception e) {
            statusText.setText("อ่านข้อมูลภาพไม่สำเร็จ");
            gpsText.setText(e.getMessage() == null ? "กรุณาลองเลือกภาพอื่น" : e.getMessage());
            dateText.setText("");
            setActionEnabled(false);
        }
    }

    private void openMap() {
        if (!hasGps) return;
        String coord = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        Uri geo = Uri.parse("geo:" + coord + "?q=" + coord);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (ActivityNotFoundException e) {
            openWebMap(coord);
        }
    }

    private void navigate() {
        if (!hasGps) return;
        String coord = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        Intent maps = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
        maps.setPackage("com.google.android.apps.maps");
        try {
            startActivity(maps);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบ Google Maps จึงเปิดตำแหน่งในแผนที่แทน", Toast.LENGTH_SHORT).show();
            openMap();
        }
    }

    private void openWebMap(String coord) {
        try {
            Uri web = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + coord);
            startActivity(new Intent(Intent.ACTION_VIEW, web));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบแอปสำหรับเปิดแผนที่", Toast.LENGTH_LONG).show();
        }
    }

    private void showEmptyState() {
        imageView.setImageDrawable(null);
        fileText.setText("ยังไม่ได้เลือกภาพ");
        statusText.setText("กด “เลือกภาพ” เพื่ออ่านตำแหน่ง GPS จาก EXIF");
        gpsText.setText("รองรับภาพจากเครื่องและภาพที่รับมาจาก LINE แบบไฟล์");
        dateText.setText("");
        hasGps = false;
        setActionEnabled(false);
    }

    private void setActionEnabled(boolean enabled) {
        openMapButton.setEnabled(enabled);
        navigateButton.setEnabled(enabled);
        openMapButton.setAlpha(enabled ? 1f : 0.45f);
        navigateButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "ภาพที่เลือก" : last;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }

    @Nullable
    private String firstNotBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private TextView infoText(float size, int color) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private PressFeedbackButton makeButton(String text, float size) {
        PressFeedbackButton button = new PressFeedbackButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(size);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.bg_button_dark);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
