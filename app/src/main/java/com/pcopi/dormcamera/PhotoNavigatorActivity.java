package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
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
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * V0.2.1 Photo Navigator + GPS editor.
 *
 * Core safety rule: selected originals are read-only. When the user assigns or edits GPS,
 * the app first creates a copy in Pictures/DormCamera and writes only GPS EXIF to that copy.
 */
public final class PhotoNavigatorActivity extends AppCompatActivity {
    private static final int REQ_PICK_IMAGE = 2200;
    private static final int REQ_LOCATION_PERMISSION = 2201;

    private ImageView imageView;
    private TextView fileText;
    private TextView statusText;
    private TextView gpsText;
    private TextView dateText;
    private PressFeedbackButton editGpsButton;
    private PressFeedbackButton openMapButton;
    private PressFeedbackButton navigateButton;

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
        title.setText("นำทางจากภาพ");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(21f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView safe = new TextView(this);
        safe.setText("V0.2.1 • อ่านต้นฉบับอย่างเดียว • เพิ่ม GPS ลงในสำเนา");
        safe.setTextColor(0xFF8FC9A3);
        safe.setTextSize(12f);
        safe.setGravity(Gravity.CENTER);
        safe.setPadding(0, dp(7), 0, dp(8));
        root.addView(safe, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(0xFF111216);
        imageView.setContentDescription("ภาพที่เลือกสำหรับอ่านหรือกำหนดตำแหน่ง GPS");
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

        editGpsButton = makeButton("📍  เพิ่ม / แก้ไข GPS", 14f);
        editGpsButton.setOnClickListener(v -> showGpsSourceDialog());
        LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        gpsParams.setMargins(0, 0, 0, dp(8));
        root.addView(editGpsButton, gpsParams);

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
        privacy.setText("GPS ที่เพิ่มเอง = ตำแหน่งที่ผู้ใช้กำหนดให้ภาพ ไม่ใช่หลักฐานว่าภาพถูกถ่าย ณ จุดนั้น");
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

    private void readImage(@NonNull Uri uri) {
        selectedUri = uri;
        hasGps = false;
        setMapActionsEnabled(false);
        setEditGpsEnabled(true);
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
                gpsText.setText("กด “เพิ่ม / แก้ไข GPS” เพื่อกำหนดตำแหน่งให้สำเนาภาพ");
                setMapActionsEnabled(false);
                updateEditButtonLabel();
                return;
            }

            latitude = latLong[0];
            longitude = latLong[1];
            hasGps = isValidCoordinate(latitude, longitude);
            if (!hasGps) {
                statusText.setText("พบข้อมูล GPS แต่พิกัดไม่ถูกต้อง");
                gpsText.setText("สามารถกำหนด GPS ใหม่ให้สำเนาภาพได้");
                setMapActionsEnabled(false);
                updateEditButtonLabel();
                return;
            }

            statusText.setText("✓ พบตำแหน่ง GPS ในภาพ");
            gpsText.setText(String.format(Locale.US, "พิกัด: %.6f, %.6f", latitude, longitude));
            setMapActionsEnabled(true);
            updateEditButtonLabel();
        } catch (Exception e) {
            statusText.setText("อ่านข้อมูลภาพไม่สำเร็จ");
            gpsText.setText(e.getMessage() == null ? "กรุณาลองเลือกภาพอื่น" : e.getMessage());
            dateText.setText("");
            setMapActionsEnabled(false);
            updateEditButtonLabel();
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
                "✍️ ป้อนพิกัด Latitude / Longitude"
        };
        new AlertDialog.Builder(this)
                .setTitle(hasGps ? "แก้ไข GPS ให้สำเนาภาพ" : "เพิ่ม GPS ให้สำเนาภาพ")
                .setMessage("ไฟล์ต้นฉบับจะไม่ถูกแก้ไข")
                .setItems(options, (dialog, which) -> {
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
            confirmGpsCopy(best.getLatitude(), best.getLongitude(),
                    LocationHelper.isStale(best) ? "ตำแหน่งล่าสุดของเครื่อง" : "ตำแหน่งปัจจุบันของเครื่อง");
            return;
        }

        helper.refresh(new LocationHelper.Callback() {
            private boolean handled;

            @Override
            public void onLocation(@NonNull Location location) {
                if (handled) return;
                handled = true;
                confirmGpsCopy(location.getLatitude(), location.getLongitude(), "ตำแหน่งปัจจุบันของเครื่อง");
            }

            @Override
            public void onUnavailable(@NonNull String reason) {
                if (handled) return;
                handled = true;
                statusText.setText("อ่านตำแหน่งปัจจุบันไม่ได้");
                Toast.makeText(PhotoNavigatorActivity.this,
                        "ตำแหน่งไม่พร้อม: " + reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION_PERMISSION || !waitingForLocationPermission) return;
        waitingForLocationPermission = false;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (granted) chooseCurrentLocation();
        else Toast.makeText(this, "ต้องอนุญาตตำแหน่งก่อนใช้ตำแหน่งปัจจุบัน", Toast.LENGTH_LONG).show();
    }

    private void showPlaceSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("เช่น ดอนเจดีย์ สุพรรณบุรี");
        input.setPadding(dp(12), dp(4), dp(12), dp(4));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ค้นหาชื่อสถานที่")
                .setMessage("ค้นหาสถานที่แล้วเลือกพิกัดที่ต้องการ")
                .setView(input)
                .setPositiveButton("ค้นหา", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) {
                input.setError("กรุณาพิมพ์ชื่อสถานที่");
                return;
            }
            dialog.dismiss();
            geocodePlace(query);
        }));
        dialog.show();
    }

    private void geocodePlace(@NonNull String query) {
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "เครื่องนี้ไม่มีบริการค้นหาพิกัดจากชื่อสถานที่", Toast.LENGTH_LONG).show();
            showManualCoordinateDialog();
            return;
        }
        statusText.setText("กำลังค้นหา “" + query + "”...");

        new Thread(() -> {
            List<Address> results = new ArrayList<>();
            String error = null;
            try {
                Geocoder geocoder = new Geocoder(PhotoNavigatorActivity.this, Locale.getDefault());
                List<Address> found = geocoder.getFromLocationName(query, 5);
                if (found != null) results.addAll(found);
            } catch (Exception e) {
                error = e.getMessage();
            }

            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (results.isEmpty()) {
                    statusText.setText("ไม่พบสถานที่จากคำค้น");
                    Toast.makeText(this,
                            finalError == null || finalError.trim().isEmpty()
                                    ? "ไม่พบ “" + query + "” ลองระบุจังหวัดหรืออำเภอเพิ่ม"
                                    : "ค้นหาไม่สำเร็จ: " + finalError,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showGeocodeResults(query, results);
            });
        }, "photo-navigator-geocoder").start();
    }

    private void showGeocodeResults(@NonNull String query, @NonNull List<Address> results) {
        CharSequence[] labels = new CharSequence[results.size()];
        for (int i = 0; i < results.size(); i++) {
            Address address = results.get(i);
            String name = address.getMaxAddressLineIndex() >= 0
                    ? address.getAddressLine(0)
                    : firstNotBlank(address.getFeatureName(), address.getLocality(), address.getAdminArea());
            if (name == null) name = query;
            labels[i] = String.format(Locale.US, "%s\n%.6f, %.6f",
                    name, address.getLatitude(), address.getLongitude());
        }

        new AlertDialog.Builder(this)
                .setTitle("เลือกตำแหน่ง")
                .setItems(labels, (dialog, which) -> {
                    Address chosen = results.get(which);
                    String source = chosen.getMaxAddressLineIndex() >= 0
                            ? chosen.getAddressLine(0)
                            : query;
                    confirmGpsCopy(chosen.getLatitude(), chosen.getLongitude(), source);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showManualCoordinateDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(4), dp(18), 0);

        EditText latInput = coordinateInput("Latitude  เช่น 14.850000");
        EditText lonInput = coordinateInput("Longitude เช่น 100.100000");
        if (hasGps) {
            latInput.setText(String.format(Locale.US, "%.7f", latitude));
            lonInput.setText(String.format(Locale.US, "%.7f", longitude));
        }
        panel.addView(latInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        panel.addView(lonInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ป้อนพิกัด GPS")
                .setView(panel)
                .setPositiveButton("ต่อไป", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double lat = parseCoordinate(latInput.getText().toString());
            Double lon = parseCoordinate(lonInput.getText().toString());
            if (lat == null || lat < -90.0 || lat > 90.0) {
                latInput.setError("Latitude ต้องอยู่ระหว่าง -90 ถึง 90");
                return;
            }
            if (lon == null || lon < -180.0 || lon > 180.0) {
                lonInput.setError("Longitude ต้องอยู่ระหว่าง -180 ถึง 180");
                return;
            }
            dialog.dismiss();
            confirmGpsCopy(lat, lon, "พิกัดที่ป้อนเอง");
        }));
        dialog.show();
    }

    private void confirmGpsCopy(double lat, double lon, @NonNull String source) {
        if (!isValidCoordinate(lat, lon)) {
            Toast.makeText(this, "พิกัดไม่ถูกต้อง", Toast.LENGTH_LONG).show();
            return;
        }
        String message = source + "\n\n"
                + String.format(Locale.US, "%.7f, %.7f", lat, lon)
                + "\n\nแอปจะสร้างสำเนาใหม่และฝัง GPS ลงใน EXIF\nไฟล์ต้นฉบับจะไม่ถูกแก้ไข";
        new AlertDialog.Builder(this)
                .setTitle("บันทึก GPS ลงสำเนาภาพ")
                .setMessage(message)
                .setPositiveButton("สร้างสำเนา", (dialog, which) -> saveGpsCopy(lat, lon))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void saveGpsCopy(double lat, double lon) {
        Uri source = selectedUri;
        if (source == null) return;

        statusText.setText("กำลังสร้างสำเนาและฝัง GPS...");
        setEditGpsEnabled(false);
        setMapActionsEnabled(false);

        new Thread(() -> {
            Uri copy = null;
            String error = null;
            try {
                copy = createCopyWithGps(source, lat, lon);
            } catch (Exception e) {
                error = e.getMessage();
            }

            Uri finalCopy = copy;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setEditGpsEnabled(true);
                if (finalCopy == null) {
                    statusText.setText("สร้างสำเนาพร้อม GPS ไม่สำเร็จ");
                    gpsText.setText(finalError == null || finalError.trim().isEmpty()
                            ? "รูปแบบไฟล์หรือพื้นที่จัดเก็บอาจไม่รองรับการเขียน EXIF"
                            : finalError);
                    setMapActionsEnabled(hasGps);
                    return;
                }
                Toast.makeText(this, "สร้างสำเนาพร้อม GPS แล้ว", Toast.LENGTH_SHORT).show();
                readImage(finalCopy);
            });
        }, "photo-navigator-gps-writer").start();
    }

    @NonNull
    private Uri createCopyWithGps(@NonNull Uri source, double lat, double lon) throws IOException {
        ContentResolver resolver = getContentResolver();
        String sourceName = displayName(source);
        String mime = resolver.getType(source);
        if (mime == null || !mime.startsWith("image/")) mime = mimeFromName(sourceName);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, gpsCopyName(sourceName, mime));
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/DormCamera");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri target = resolver.insert(collection, values);
        if (target == null) throw new IOException("สร้างไฟล์สำเนาไม่ได้");

        boolean success = false;
        try {
            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = resolver.openOutputStream(target, "w")) {
                if (in == null || out == null) throw new IOException("เปิดไฟล์สำหรับคัดลอกไม่ได้");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) out.write(buffer, 0, read);
                }
                out.flush();
            }

            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(target, "rw")) {
                if (pfd == null) throw new IOException("เปิดสำเนาเพื่อเขียน EXIF ไม่ได้");
                ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                exif.setLatLong(lat, lon);
                exif.saveAttributes();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(target, ready, null, null);
            }
            success = true;
            return target;
        } finally {
            if (!success) {
                try {
                    resolver.delete(target, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void openMap() {
        if (!hasGps) return;
        openCoordinateInMap(latitude, longitude);
    }

    private void openCoordinateInMap(double lat, double lon) {
        String coord = String.format(Locale.US, "%.7f,%.7f", lat, lon);
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
        selectedUri = null;
        imageView.setImageDrawable(null);
        fileText.setText("ยังไม่ได้เลือกภาพ");
        statusText.setText("กด “เลือกภาพ” เพื่ออ่าน GPS หรือกำหนดตำแหน่งให้ภาพ");
        gpsText.setText("รองรับภาพจากเครื่องและภาพที่รับมาจาก LINE แบบไฟล์");
        dateText.setText("");
        hasGps = false;
        setEditGpsEnabled(false);
        setMapActionsEnabled(false);
        updateEditButtonLabel();
    }

    private void setMapActionsEnabled(boolean enabled) {
        openMapButton.setEnabled(enabled);
        navigateButton.setEnabled(enabled);
        openMapButton.setAlpha(enabled ? 1f : 0.45f);
        navigateButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setEditGpsEnabled(boolean enabled) {
        editGpsButton.setEnabled(enabled);
        editGpsButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void updateEditButtonLabel() {
        if (editGpsButton == null) return;
        editGpsButton.setText(hasGps ? "📍  แก้ไข GPS (สร้างสำเนา)" : "📍  เพิ่ม GPS ให้ภาพ");
    }

    private String displayName(@NonNull Uri uri) {
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

    private String gpsCopyName(@NonNull String sourceName, @NonNull String mime) {
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        String ext = dot > 0 ? sourceName.substring(dot) : extensionForMime(mime);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return base + "_GPS_" + stamp + ext;
    }

    private String mimeFromName(@NonNull String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private String extensionForMime(@NonNull String mime) {
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("image/heic".equalsIgnoreCase(mime) || "image/heif".equalsIgnoreCase(mime)) return ".heic";
        return ".jpg";
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }

    @Nullable
    private Double parseCoordinate(@Nullable String value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private String firstNotBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
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
