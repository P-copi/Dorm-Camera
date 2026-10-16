package com.pcopi.dormcamera;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives images, files, coordinates, and map links shared from LINE/other apps. */
public final class ReceiveShareActivity extends AppCompatActivity {
    private static final int REQ_MAP_PICKER = 2810;
    private static final Pattern COORD_PATTERN = Pattern.compile(
            "(?<![\\d.])([-+]?\\d{1,2}(?:\\.\\d+)?)\\s*[,，]\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)(?![\\d.])");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private ImageView imageView;
    private TextView titleText;
    private TextView statusText;
    private TextView detailText;
    private PressFeedbackButton mapButton;
    private PressFeedbackButton navButton;
    private PressFeedbackButton addGpsButton;
    private PressFeedbackButton linkButton;

    @Nullable private Uri sharedImageUri;
    @Nullable private String sharedLink;
    private double latitude;
    private double longitude;
    private boolean hasCoordinate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIncomingIntent(getIntent());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.setBackgroundColor(0xFF050506);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        PressFeedbackButton close = button("←", 15f);
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(42)));
        TextView heading = text("รับจาก LINE / แอปอื่น", 20f, 0xFFFFFFFF);
        heading.setPadding(dp(12), 0, 0, 0);
        top.addView(heading, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top);

        TextView hint = text("ตรวจ GPS แล้วเปิดแผนที่หรือนำทางได้ทันที", 12f, 0xFF8FC9A3);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(0xFF111216);
        root.addView(imageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), dp(11), dp(14), dp(11));
        info.setBackgroundResource(R.drawable.bg_control_panel);
        titleText = text("กำลังอ่านข้อมูล...", 15f, 0xFFFFFFFF);
        statusText = text("", 14f, 0xFFD8D9DE);
        detailText = text("", 12f, 0xFFB9BBC2);
        detailText.setPadding(0, dp(5), 0, 0);
        info.addView(titleText);
        info.addView(statusText);
        info.addView(detailText);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(info, infoParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mapButton = button("🗺 แผนที่", 13f);
        navButton = button("🧭 นำทาง", 13f);
        mapButton.setOnClickListener(v -> openMap());
        navButton.setOnClickListener(v -> navigate());
        row.addView(mapButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        navParams.setMarginStart(dp(8));
        row.addView(navButton, navParams);
        root.addView(row);

        addGpsButton = button("📍 เลือกตำแหน่งจากแผนที่ / เพิ่ม GPS", 14f);
        addGpsButton.setOnClickListener(v -> openMapPicker());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        addParams.setMargins(0, dp(8), 0, 0);
        root.addView(addGpsButton, addParams);

        linkButton = button("🔗 เปิดลิงก์แผนที่", 14f);
        linkButton.setOnClickListener(v -> openSharedLink());
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        linkParams.setMargins(0, dp(8), 0, 0);
        root.addView(linkButton, linkParams);

        setContentView(root);
        setCoordinateActions(false);
        addGpsButton.setVisibility(View.GONE);
        linkButton.setVisibility(View.GONE);
    }

    private void handleIncomingIntent(@Nullable Intent intent) {
        if (intent == null) {
            showUnsupported("ไม่พบข้อมูลที่แชร์มา");
            return;
        }

        Uri stream = extractStreamUri(intent);
        if (stream != null) {
            handleSharedImage(stream);
            return;
        }

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if ((text == null || text.length() == 0) && intent.getClipData() != null
                && intent.getClipData().getItemCount() > 0) {
            text = intent.getClipData().getItemAt(0).coerceToText(this);
        }
        if (text != null && text.length() > 0) {
            handleSharedText(text.toString());
            return;
        }

        showUnsupported("ยังไม่รองรับข้อมูลชนิดนี้");
    }

    @Nullable
    private Uri extractStreamUri(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (uri != null) return uri;
            } else {
                Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (value instanceof Uri) return (Uri) value;
            }
        } catch (Exception ignored) {}

        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            Uri uri = clip.getItemAt(0).getUri();
            if (uri != null) return uri;
        }
        return intent.getData();
    }

    private void handleSharedImage(@NonNull Uri uri) {
        sharedImageUri = uri;
        sharedLink = null;
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageURI(null);
        imageView.setImageURI(uri);
        String name = displayName(uri);
        titleText.setText(name);
        statusText.setText("กำลังตรวจ GPS ในภาพ...");
        detailText.setText("");
        linkButton.setVisibility(View.GONE);
        addGpsButton.setVisibility(View.GONE);
        setCoordinateActions(false);

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("เปิดไฟล์ไม่ได้");
            ExifInterface exif = new ExifInterface(in);
            double[] ll = exif.getLatLong();
            if (ll != null && ll.length >= 2 && valid(ll[0], ll[1])) {
                latitude = ll[0];
                longitude = ll[1];
                hasCoordinate = true;
                statusText.setText("✓ พบ GPS ในไฟล์นี้");
                detailText.setText(String.format(Locale.US,
                        "📍 %.7f, %.7f\nกด “นำทาง” ได้ทันที", latitude, longitude));
                setCoordinateActions(true);
            } else {
                hasCoordinate = false;
                statusText.setText("ไม่พบ GPS ในภาพนี้");
                detailText.setText("ถ้าเป็นภาพที่ส่งใน LINE แบบรูปภาพปกติ EXIF/GPS อาจถูกลบแล้ว\n"
                        + "สามารถเลือกตำแหน่งจากแผนที่เพื่อสร้างสำเนาพร้อม GPS ได้");
                addGpsButton.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            hasCoordinate = false;
            statusText.setText("อ่านข้อมูลภาพไม่สำเร็จ");
            detailText.setText(messageOf(e));
            addGpsButton.setVisibility(View.VISIBLE);
        }
    }

    private void handleSharedText(@NonNull String value) {
        sharedImageUri = null;
        imageView.setVisibility(View.GONE);
        titleText.setText("ข้อความ / ตำแหน่งที่แชร์มา");
        String trimmed = value.trim();
        detailText.setText(trimmed.length() > 800 ? trimmed.substring(0, 800) + "…" : trimmed);
        addGpsButton.setVisibility(View.GONE);

        sharedLink = findUrl(trimmed);
        Matcher matcher = COORD_PATTERN.matcher(trimmed);
        hasCoordinate = false;
        while (matcher.find()) {
            try {
                double lat = Double.parseDouble(matcher.group(1));
                double lon = Double.parseDouble(matcher.group(2));
                if (valid(lat, lon)) {
                    latitude = lat;
                    longitude = lon;
                    hasCoordinate = true;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (hasCoordinate) {
            statusText.setText(String.format(Locale.US,
                    "✓ พบพิกัด %.7f, %.7f", latitude, longitude));
            setCoordinateActions(true);
        } else {
            statusText.setText(sharedLink != null
                    ? "พบลิงก์แผนที่ แต่ยังอ่านพิกัดตรง ๆ ไม่ได้"
                    : "ไม่พบพิกัดที่แอปอ่านได้");
            setCoordinateActions(false);
        }
        linkButton.setVisibility(sharedLink == null ? View.GONE : View.VISIBLE);
    }

    private void showUnsupported(String message) {
        sharedImageUri = null;
        imageView.setVisibility(View.GONE);
        titleText.setText("รับข้อมูลไม่สำเร็จ");
        statusText.setText(message);
        detailText.setText("ลองแชร์เป็นไฟล์ภาพ หรือแชร์ข้อความพิกัด / ลิงก์แผนที่อีกครั้ง");
        setCoordinateActions(false);
        addGpsButton.setVisibility(View.GONE);
        linkButton.setVisibility(View.GONE);
    }

    private void setCoordinateActions(boolean enabled) {
        mapButton.setEnabled(enabled);
        navButton.setEnabled(enabled);
        mapButton.setAlpha(enabled ? 1f : 0.42f);
        navButton.setAlpha(enabled ? 1f : 0.42f);
    }

    private void openMap() {
        if (!hasCoordinate) return;
        String coord = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + coord + "?q=" + coord)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + coord)));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "ไม่พบแอปแผนที่", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void navigate() {
        if (!hasCoordinate) return;
        String coord = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
        nav.setPackage("com.google.android.apps.maps");
        try { startActivity(nav); }
        catch (ActivityNotFoundException e) { openMap(); }
    }

    private void openSharedLink() {
        if (sharedLink == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(sharedLink)));
        } catch (Exception e) {
            Toast.makeText(this, "เปิดลิงก์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
        }
    }

    private void openMapPicker() {
        if (sharedImageUri == null) return;
        Intent intent = new Intent(this, MapPickerActivity.class);
        startActivityForResult(intent, REQ_MAP_PICKER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MAP_PICKER || resultCode != RESULT_OK || data == null) return;
        double lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN);
        double lon = data.getDoubleExtra(MapPickerActivity.EXTRA_LON, Double.NaN);
        String label = data.getStringExtra(MapPickerActivity.EXTRA_LABEL);
        if (!valid(lat, lon)) return;
        askCopyName(lat, lon, label == null ? "ตำแหน่งจากแผนที่" : label);
    }

    private void askCopyName(double lat, double lon, String label) {
        Uri source = sharedImageUri;
        if (source == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("ชื่อภาพ / สถานที่");
        input.setText(suggestedName(source, label));
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("สร้างสำเนาพร้อม GPS")
                .setMessage(String.format(Locale.US, "%s\n%.7f, %.7f", label, lat, lon))
                .setView(input)
                .setPositiveButton("สร้างสำเนา", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("กรุณาใส่ชื่อภาพ");
                return;
            }
            dialog.dismiss();
            createGpsCopy(source, name, lat, lon);
        }));
        dialog.show();
    }

    private void createGpsCopy(Uri source, String name, double lat, double lon) {
        statusText.setText("กำลังสร้างสำเนาพร้อม GPS...");
        addGpsButton.setEnabled(false);
        new Thread(() -> {
            Uri copy = null;
            String error = null;
            try {
                copy = GpsNamedCopyWriter.createCopyWithGps(
                        this, source, displayName(source), name, lat, lon);
            } catch (Exception e) {
                error = messageOf(e);
            }
            Uri finalCopy = copy;
            String finalError = error;
            runOnUiThread(() -> {
                addGpsButton.setEnabled(true);
                if (finalCopy == null) {
                    statusText.setText("สร้างสำเนาไม่สำเร็จ");
                    detailText.setText(finalError == null ? "กรุณาลองอีกครั้ง" : finalError);
                    return;
                }
                Toast.makeText(this, "สร้างสำเนาพร้อม GPS สำเร็จ", Toast.LENGTH_LONG).show();
                handleSharedImage(finalCopy);
            });
        }, "receive-share-gps-copy").start();
    }

    private String suggestedName(Uri uri, String label) {
        String clean = label == null ? "" : label.trim();
        if (!clean.isEmpty() && !clean.equals("ตำแหน่งจากแผนที่")) {
            String first = clean.split(",")[0].trim();
            if (first.length() > 45) first = first.substring(0, 45).trim();
            if (!first.isEmpty()) return first;
        }
        String name = displayName(uri);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + "_GPS";
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String value = cursor.getString(i);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "shared_photo.jpg" : last;
    }

    @Nullable
    private String findUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        String url = matcher.group();
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".") || url.endsWith(",")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private PressFeedbackButton button(String value, float size) {
        PressFeedbackButton button = new PressFeedbackButton(this);
        button.setText(value);
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

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
