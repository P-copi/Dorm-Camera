package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** V0.2.3 fixed GPS source chooser for Samsung/Android dialogs. */
public final class PhotoNavigatorFixedActivity extends AppCompatActivity {
    private static final int REQ_PICK_IMAGE = 2220;
    private static final int REQ_LOCATION_PERMISSION = 2221;

    private ImageView imageView;
    private TextView fileText;
    private TextView statusText;
    private TextView gpsText;
    private TextView dateText;
    private PressFeedbackButton editGpsButton;
    private PressFeedbackButton mapButton;
    private PressFeedbackButton navButton;

    @Nullable private Uri selectedUri;
    private double latitude;
    private double longitude;
    private boolean hasGps;
    private boolean waitingForLocationPermission;

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
        title.setText("นำทาง / GPS จากภาพ");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top);

        TextView safe = new TextView(this);
        safe.setText("V0.2.3 • ต้นฉบับไม่ถูกแก้ไข • GPS บันทึกลงสำเนาใหม่");
        safe.setTextColor(0xFF8FC9A3);
        safe.setTextSize(12f);
        safe.setGravity(Gravity.CENTER);
        safe.setPadding(0, dp(7), 0, dp(8));
        root.addView(safe);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(0xFF111216);
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
        root.addView(info);

        PressFeedbackButton choose = makeButton("🖼  เลือกภาพ", 15f);
        choose.setOnClickListener(v -> pickImage());
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        chooseParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(choose, chooseParams);

        editGpsButton = makeButton("📍  เพิ่ม / แก้ไข GPS", 14f);
        editGpsButton.setOnClickListener(v -> showGpsSourceDialog());
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        editParams.setMargins(0, 0, 0, dp(8));
        root.addView(editGpsButton, editParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mapButton = makeButton("🗺  เปิดแผนที่", 14f);
        mapButton.setOnClickListener(v -> openMap());
        navButton = makeButton("🧭  นำทาง", 14f);
        navButton.setOnClickListener(v -> navigate());
        actions.addView(mapButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        navParams.setMarginStart(dp(8));
        actions.addView(navButton, navParams);
        root.addView(actions);

        TextView note = new TextView(this);
        note.setText("สำเนาที่เพิ่ม GPS จะอยู่ใน Pictures/DormCamera");
        note.setTextColor(0xFF8F929A);
        note.setTextSize(11f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(9), dp(4), 0);
        root.addView(note);

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
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}
        readImage(uri);
    }

    private void readImage(@NonNull Uri uri) {
        selectedUri = uri;
        imageView.setImageURI(null);
        imageView.setImageURI(uri);
        fileText.setText("ไฟล์: " + displayName(uri));
        statusText.setText("กำลังอ่าน GPS...");
        gpsText.setText("");
        dateText.setText("");
        hasGps = false;
        setMapEnabled(false);
        setEditEnabled(true);

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("เปิดไฟล์ไม่ได้");
            ExifInterface exif = new ExifInterface(input);
            double[] ll = exif.getLatLong();
            String date = firstNotBlank(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                    exif.getAttribute(ExifInterface.TAG_DATETIME));
            dateText.setText(date == null ? "วันที่ใน EXIF: ไม่พบข้อมูล" : "วันที่ใน EXIF: " + date);

            if (ll != null && ll.length >= 2 && isValidCoordinate(ll[0], ll[1])) {
                latitude = ll[0];
                longitude = ll[1];
                hasGps = true;
                statusText.setText("✓ พบ GPS ในภาพ");
                gpsText.setText(String.format(Locale.US, "พิกัด: %.6f, %.6f", latitude, longitude));
                setMapEnabled(true);
                editGpsButton.setText("📍  แก้ไข GPS (สร้างสำเนา)");
            } else {
                statusText.setText("ไม่พบ GPS ในภาพนี้");
                gpsText.setText("กด “เพิ่ม GPS ให้ภาพ” แล้วเลือกวิธีกำหนดตำแหน่ง");
                editGpsButton.setText("📍  เพิ่ม GPS ให้ภาพ");
            }
        } catch (Exception e) {
            statusText.setText("อ่านข้อมูลภาพไม่สำเร็จ");
            gpsText.setText(messageOf(e));
            editGpsButton.setText("📍  เพิ่ม / แก้ไข GPS");
        }
    }

    private void showGpsSourceDialog() {
        if (selectedUri == null) {
            Toast.makeText(this, "กรุณาเลือกภาพก่อน", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {
                "📍 ใช้ตำแหน่งปัจจุบันของเครื่อง",
                "🔎 ค้นหาชื่อสถานที่",
                "✍️ ป้อน Latitude / Longitude"
        };
        new AlertDialog.Builder(this)
                .setTitle(hasGps
                        ? "แก้ไข GPS ให้สำเนา • เลือกวิธี"
                        : "เพิ่ม GPS ให้สำเนา • เลือกวิธี")
                .setItems(options, (d, which) -> {
                    if (which == 0) chooseCurrentLocation();
                    else if (which == 1) showPlaceSearchDialog();
                    else showManualCoordinateDialog();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void chooseCurrentLocation() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            waitingForLocationPermission = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION_PERMISSION);
            return;
        }
        statusText.setText("กำลังอ่านตำแหน่งปัจจุบัน...");
        LocationHelper helper = new LocationHelper(this);
        Location best = helper.getBestLocation();
        if (best != null) {
            confirmGpsCopy(best.getLatitude(), best.getLongitude(), "ตำแหน่งจากเครื่อง");
            return;
        }
        helper.refresh(new LocationHelper.Callback() {
            private boolean done;
            @Override public void onLocation(@NonNull Location location) {
                if (done) return;
                done = true;
                confirmGpsCopy(location.getLatitude(), location.getLongitude(), "ตำแหน่งจากเครื่อง");
            }
            @Override public void onUnavailable(@NonNull String reason) {
                if (done) return;
                done = true;
                statusText.setText("อ่านตำแหน่งไม่ได้");
                Toast.makeText(PhotoNavigatorFixedActivity.this,
                        "ตำแหน่งไม่พร้อม: " + reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION_PERMISSION || !waitingForLocationPermission) return;
        waitingForLocationPermission = false;
        for (int result : results) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                chooseCurrentLocation();
                return;
            }
        }
        Toast.makeText(this, "ต้องอนุญาตตำแหน่งก่อน", Toast.LENGTH_LONG).show();
    }

    private void showPlaceSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("เช่น ดอนเจดีย์ สุพรรณบุรี");
        input.setPadding(dp(12), dp(4), dp(12), dp(4));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ค้นหาชื่อสถานที่")
                .setView(input)
                .setPositiveButton("ค้นหา", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) {
                input.setError("กรุณาพิมพ์ชื่อสถานที่");
                return;
            }
            dialog.dismiss();
            geocodePlace(q);
        }));
        dialog.show();
    }

    private void geocodePlace(@NonNull String query) {
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "เครื่องนี้ค้นหาชื่อสถานที่ไม่ได้ กรุณาป้อนพิกัด", Toast.LENGTH_LONG).show();
            showManualCoordinateDialog();
            return;
        }
        statusText.setText("กำลังค้นหา “" + query + "”...");
        new Thread(() -> {
            List<Address> found = new ArrayList<>();
            String error = null;
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocationName(query, 5);
                if (list != null) found.addAll(list);
            } catch (Exception e) {
                error = messageOf(e);
            }
            String finalError = error;
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    statusText.setText("ไม่พบสถานที่");
                    Toast.makeText(this,
                            finalError == null ? "ลองระบุอำเภอหรือจังหวัดเพิ่ม" : finalError,
                            Toast.LENGTH_LONG).show();
                } else {
                    showPlaceResults(query, found);
                }
            });
        }, "gps-place-search").start();
    }

    private void showPlaceResults(String query, List<Address> found) {
        CharSequence[] labels = new CharSequence[found.size()];
        for (int i = 0; i < found.size(); i++) {
            Address a = found.get(i);
            String name = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : query;
            labels[i] = String.format(Locale.US, "%s\n%.6f, %.6f",
                    name, a.getLatitude(), a.getLongitude());
        }
        new AlertDialog.Builder(this)
                .setTitle("เลือกตำแหน่ง")
                .setItems(labels, (d, which) -> {
                    Address a = found.get(which);
                    confirmGpsCopy(a.getLatitude(), a.getLongitude(), labels[which].toString());
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showManualCoordinateDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), 0, dp(18), 0);
        EditText lat = coordinateInput("Latitude เช่น 14.850000");
        EditText lon = coordinateInput("Longitude เช่น 100.100000");
        if (hasGps) {
            lat.setText(String.format(Locale.US, "%.7f", latitude));
            lon.setText(String.format(Locale.US, "%.7f", longitude));
        }
        panel.addView(lat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        panel.addView(lon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ป้อนพิกัด GPS")
                .setView(panel)
                .setPositiveButton("ต่อไป", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double la = parseCoordinate(lat.getText().toString());
            Double lo = parseCoordinate(lon.getText().toString());
            if (la == null || la < -90 || la > 90) {
                lat.setError("Latitude ต้องอยู่ระหว่าง -90 ถึง 90");
                return;
            }
            if (lo == null || lo < -180 || lo > 180) {
                lon.setError("Longitude ต้องอยู่ระหว่าง -180 ถึง 180");
                return;
            }
            dialog.dismiss();
            confirmGpsCopy(la, lo, "พิกัดที่ป้อนเอง");
        }));
        dialog.show();
    }

    private void confirmGpsCopy(double lat, double lon, String source) {
        if (!isValidCoordinate(lat, lon)) return;
        String message = source + "\n\n"
                + String.format(Locale.US, "%.7f, %.7f", lat, lon)
                + "\n\nจะสร้างสำเนาใหม่ใน Pictures/DormCamera";
        new AlertDialog.Builder(this)
                .setTitle("สร้างสำเนาพร้อม GPS")
                .setMessage(message)
                .setPositiveButton("สร้างสำเนา", (d, w) -> saveGpsCopy(lat, lon))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void saveGpsCopy(double lat, double lon) {
        Uri source = selectedUri;
        if (source == null) return;
        String sourceName = displayName(source);
        statusText.setText("กำลังสร้างสำเนาและตรวจสอบ GPS...");
        setEditEnabled(false);
        setMapEnabled(false);

        new Thread(() -> {
            Uri copy = null;
            String error = null;
            try {
                copy = GpsCopyWriter.createCopyWithGps(this, source, sourceName, lat, lon);
            } catch (Exception e) {
                error = messageOf(e);
            }
            Uri finalCopy = copy;
            String finalError = error;
            runOnUiThread(() -> {
                setEditEnabled(true);
                if (finalCopy == null) {
                    statusText.setText("สร้างสำเนาไม่สำเร็จ");
                    gpsText.setText(finalError == null ? "กรุณาลองภาพ JPG อีกครั้ง" : finalError);
                    setMapEnabled(hasGps);
                    return;
                }
                Toast.makeText(this, "สร้างสำเนาพร้อม GPS สำเร็จ", Toast.LENGTH_LONG).show();
                readImage(finalCopy);
            });
        }, "gps-copy-v023").start();
    }

    private void openMap() {
        if (!hasGps) return;
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
        if (!hasGps) return;
        String coord = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
        intent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openMap();
        }
    }

    private void showEmptyState() {
        selectedUri = null;
        imageView.setImageDrawable(null);
        fileText.setText("ยังไม่ได้เลือกภาพ");
        statusText.setText("เลือกภาพเพื่ออ่าน GPS หรือเพิ่มตำแหน่ง");
        gpsText.setText("ภาพต้นฉบับจะไม่ถูกแก้ไข");
        dateText.setText("");
        hasGps = false;
        setEditEnabled(false);
        setMapEnabled(false);
    }

    private void setEditEnabled(boolean enabled) {
        editGpsButton.setEnabled(enabled);
        editGpsButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setMapEnabled(boolean enabled) {
        mapButton.setEnabled(enabled);
        navButton.setEnabled(enabled);
        mapButton.setAlpha(enabled ? 1f : 0.45f);
        navButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = cursor.getString(i);
                    if (n != null && !n.trim().isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "photo.jpg" : last;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    @Nullable
    private Double parseCoordinate(String value) {
        try { return Double.parseDouble(value.trim().replace(',', '.')); }
        catch (Exception e) { return null; }
    }

    @Nullable
    private String firstNotBlank(@Nullable String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    private EditText coordinateInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private TextView infoText(float size, int color) {
        TextView v = new TextView(this);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }

    private PressFeedbackButton makeButton(String text, float size) {
        PressFeedbackButton b = new PressFeedbackButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(size);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackgroundResource(R.drawable.bg_button_dark);
        b.setBackgroundTintList(null);
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
