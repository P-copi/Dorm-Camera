package com.pcopi.dormcamera;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
    private static final int REQ_PICK_DOWNLOADED_FILE = 2811;
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
    private PressFeedbackButton chooseFileButton;
    private PressFeedbackButton savePlaceButton;
    private PressFeedbackButton pasteLinkButton;
    private PressFeedbackButton forwardLinkButton;
    private String receivedPlaceTitle = "ตำแหน่งจาก LINE";
    private PressFeedbackButton retryFileButton;
    @Nullable private Uri lastIncomingUri;
    private int receiveSequence;

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
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

        TextView hint = text("รับลิงก์ Google Maps, ข้อความ, JPG หรือ ZIP จาก LINE", 12f, 0xFF8FC9A3);
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

        chooseFileButton = button("📂 เลือก JPG / ZIP จากเครื่อง (ไม่ผ่านการแชร์ LINE)", 13f);
        chooseFileButton.setOnClickListener(v -> pickDownloadedFile());
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        chooseParams.setMargins(0, dp(8), 0, 0);
        root.addView(chooseFileButton, chooseParams);

        savePlaceButton = button("💾 บันทึกพิกัดไว้ในสถานที่ของฉัน", 14f);
        savePlaceButton.setOnClickListener(v -> saveReceivedPlace());
        LinearLayout.LayoutParams placeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        placeParams.setMargins(0, dp(7), 0, 0);
        root.addView(savePlaceButton, placeParams);
        savePlaceButton.setVisibility(View.GONE);

        forwardLinkButton = button("↗ ส่งต่อลิงก์นำทาง (ไม่ส่งรูป)", 13f);
        forwardLinkButton.setOnClickListener(v -> forwardGpsLink());
        LinearLayout.LayoutParams forwardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        forwardParams.setMargins(0, dp(6), 0, 0);
        root.addView(forwardLinkButton, forwardParams);
        forwardLinkButton.setVisibility(View.GONE);

        pasteLinkButton = button("📋 วางลิงก์ GPS จากคลิปบอร์ด", 13f);
        pasteLinkButton.setOnClickListener(v -> pasteSharedGpsLink());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        pasteParams.setMargins(0, dp(6), 0, 0);
        root.addView(pasteLinkButton, pasteParams);

        retryFileButton = button("↻ ลองอ่านไฟล์เดิมอีกครั้ง", 13f);
        retryFileButton.setOnClickListener(v -> {
            if (lastIncomingUri != null) handleSharedImage(lastIncomingUri);
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        retryParams.setMargins(0, dp(6), 0, 0);
        root.addView(retryFileButton, retryParams);
        retryFileButton.setVisibility(View.GONE);

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
            String scheme = stream.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                handleSharedText(stream.toString());
                return;
            }
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

        showReadyState();
    }

    private void showReadyState() {
        ++receiveSequence;
        lastIncomingUri = null;
        retryFileButton.setVisibility(View.GONE);
        sharedImageUri = null;
        sharedLink = null;
        hasCoordinate = false;
        imageView.setVisibility(View.GONE);
        savePlaceButton.setVisibility(View.GONE);
        forwardLinkButton.setVisibility(View.GONE);
        titleText.setText("พร้อมรับ GPS Link จาก LINE / Keep Memo");
        statusText.setText("เลือก JPG หรือ ZIP ที่ดาวน์โหลดแล้ว");
        detailText.setText("กดปุ่มเลือกไฟล์ด้านล่าง หรือเปิดแชร์เข้า Dorm Camera "
                + "เมื่อ LINE อนุญาตให้ส่งไฟล์แล้ว");
        addGpsButton.setVisibility(View.GONE);
        linkButton.setVisibility(View.GONE);
        setCoordinateActions(false);
    }

    @Nullable
    private Uri extractStreamUri(Intent intent) {
        // LINE can send one document with SEND_MULTIPLE/EXTRA_STREAM ArrayList.
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            Uri many = firstStreamFromList(intent);
            if (many != null) return many;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (single != null) return single;
            } else {
                Object single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (single instanceof Uri) return (Uri) single;
            }
        } catch (Exception ignored) {}
        Uri many = firstStreamFromList(intent);
        if (many != null) return many;

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) return uri;
            }
        }
        // Android ACTION_VIEW from LINE Documents / Samsung My Files.
        return intent.getData();
    }

    @Nullable
    private Uri firstStreamFromList(Intent intent) {
        try {
            java.util.ArrayList<Uri> many;
            if (Build.VERSION.SDK_INT >= 33) {
                many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
            if (many != null && !many.isEmpty()) return many.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    private void pickDownloadedFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Do not restrict to image/*: LINE may label the JPEG as application/octet-stream.
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_DOWNLOADED_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบตัวเลือกไฟล์ในเครื่อง", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * LINE may supply a temporary URI whose content is only available once its
     * transfer/cache is ready. Never decode or inspect that remote URI on the UI
     * thread: copy first, then analyze only our own app-owned snapshot.
     */
    private void handleSharedImage(@NonNull Uri uri) {
        lastIncomingUri = uri;
        savePlaceButton.setVisibility(View.GONE);
        forwardLinkButton.setVisibility(View.GONE);
        final int sequence = ++receiveSequence;
        hasCoordinate = false;
        sharedImageUri = null;
        sharedLink = null;
        setCoordinateActions(false);
        addGpsButton.setVisibility(View.GONE);
        linkButton.setVisibility(View.GONE);
        retryFileButton.setVisibility(View.GONE);
        imageView.setVisibility(View.GONE);
        titleText.setText("รับไฟล์จาก LINE / เครื่อง");
        statusText.setText("กำลังอ่านไฟล์ต้นทาง...");
        detailText.setText("ระบบจะคัดลอกไฟล์เข้าพื้นที่ของ Dorm Camera ก่อนตรวจ GPS\n"
                + "ถ้า LINE ยังไม่เปิดไฟล์ให้ใช้งาน ระบบจะลองใหม่แบบจำกัดครั้ง");

        new Thread(() -> {
            IncomingFileCache.Result received = null;
            Uri image = null;
            double[] position = null;
            Exception lastError = null;
            boolean fromZip = false;
            int completedAttempts = 0;
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (sequence != receiveSequence || Thread.currentThread().isInterrupted()) return;
                final int currentAttempt = attempt;
                runOnUiThread(() -> {
                    if (sequence == receiveSequence && !isFinishing()) {
                        statusText.setText("กำลังรับไฟล์ • ครั้งที่ " + currentAttempt + "/3");
                    }
                });
                try {
                    received = IncomingFileCache.snapshot(this, uri);
                    if (!received.zip && received.originalName.toLowerCase(Locale.US)
                            .endsWith(".zip")) {
                        throw new IOException("ZIP ไม่สมบูรณ์หรือไฟล์ยังดาวน์โหลดไม่ครบ");
                    }
                    fromZip = received.zip;
                    image = received.zip
                            ? ShareGpsArchive.extractFirstImage(this, received.uri)
                            : received.uri;
                    try (InputStream input = getContentResolver().openInputStream(image)) {
                        if (input == null) throw new IOException("เปิดภาพที่รับไว้ไม่ได้");
                        position = new ExifInterface(input).getLatLong();
                    }
                    completedAttempts = attempt;
                    lastError = null;
                    break;
                } catch (Exception ex) {
                    lastError = ex;
                    if (ex instanceof SecurityException || attempt == 3) break;
                    try { Thread.sleep(attempt == 1 ? 500L : 1400L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            IncomingFileCache.Result finalReceived = received;
            Uri finalImage = image;
            double[] gps = position;
            Exception failure = lastError;
            boolean zip = fromZip;
            int attempts = completedAttempts;
            runOnUiThread(() -> {
                if (sequence != receiveSequence || isFinishing() || isDestroyed()) return;
                if (failure != null || finalImage == null || finalReceived == null) {
                    hasCoordinate = false;
                    sharedImageUri = null;
                    titleText.setText("LINE ส่งไฟล์มาแล้ว แต่เปิดอ่านไม่สำเร็จ");
                    statusText.setText(failure instanceof SecurityException
                            ? "ไม่มีสิทธิ์อ่านไฟล์ที่ LINE ส่งมา"
                            : "ไฟล์ยังไม่พร้อม หรืออ่านข้อมูลไม่สำเร็จ");
                    String kind = failure == null ? "ไม่ทราบ" : failure.getClass().getSimpleName();
                    detailText.setText("ลองอ่านใหม่ได้ด้วยปุ่มด้านล่าง\n"
                            + "หาก LINE ยังแจ้ง “ไม่สามารถแชร์ได้” ก่อนเปิดแอป ให้ลอง Keep Memo "
                            + "หรือเลือกไฟล์ที่บันทึกในเครื่อง\n"
                            + "รายละเอียด: " + kind + " • "
                            + (failure == null ? "ไม่ทราบสาเหตุ" : messageOf(failure)));
                    imageView.setVisibility(View.GONE);
                    addGpsButton.setVisibility(View.GONE);
                    retryFileButton.setVisibility(View.VISIBLE);
                    setCoordinateActions(false);
                    return;
                }
                sharedImageUri = finalImage;
                sharedLink = null;
                retryFileButton.setVisibility(View.GONE);
                String label = finalReceived.originalName;
                titleText.setText(label);
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageURI(null);
                imageView.setImageURI(finalImage);
                String metadata = (zip ? "ไฟล์ ZIP → ภาพ GPS" : "ไฟล์ภาพ")
                        + " • ขนาดต้นทาง "
                        + String.format(Locale.US, "%.2f MB", finalReceived.bytes / 1048576.0)
                        + " • อ่านสำเร็จครั้งที่ " + attempts;
                if (gps != null && gps.length >= 2 && valid(gps[0], gps[1])) {
                    latitude = gps[0];
                    longitude = gps[1];
                    hasCoordinate = true;
                    statusText.setText("✓ พบ GPS พร้อมนำทาง");
                    detailText.setText(String.format(Locale.US,
                            "📍 %.7f, %.7f\n%s\nอ่านจากไฟล์สำเนาใน Dorm Camera",
                            latitude, longitude, metadata));
                    addGpsButton.setVisibility(View.GONE);
                    setCoordinateActions(true);
                } else {
                    hasCoordinate = false;
                    statusText.setText("เปิดไฟล์ได้ แต่ไม่พบ GPS");
                    detailText.setText(metadata + "\nGPS อาจไม่มีในไฟล์ หรือแหล่งไฟล์อาจปกปิด EXIF\n"
                            + "สามารถเพิ่มตำแหน่งด้วยแผนที่และสร้างสำเนาใหม่ได้");
                    addGpsButton.setVisibility(View.VISIBLE);
                    setCoordinateActions(false);
                }
            });
        }, "dorm-receive-snapshot-v0213").start();
    }

    private void handleSharedText(@NonNull String value) {
        ++receiveSequence;
        lastIncomingUri = null;
        retryFileButton.setVisibility(View.GONE);
        sharedImageUri = null;
        imageView.setVisibility(View.GONE);
        addGpsButton.setVisibility(View.GONE);

        String trimmed = value.trim();
        SmartGpsLink.Parsed parsed = SmartGpsLink.parse(trimmed);
        if (parsed != null) {
            latitude = parsed.latitude;
            longitude = parsed.longitude;
            receivedPlaceTitle = parsed.title;
            sharedLink = parsed.navigationUrl;
            hasCoordinate = true;
            titleText.setText("📍 " + receivedPlaceTitle);
            statusText.setText("✓ พบ GPS จากลิงก์ • พร้อมนำทาง");
            detailText.setText(String.format(Locale.US,
                    "พิกัด: %.7f, %.7f\nแตะนำทางเพื่อเปิด Google Maps\n"
                    + "หรือบันทึกชื่อสถานที่โดยไม่ต้องมีภาพ\n%s",
                    latitude, longitude, parsed.navigationUrl));
            setCoordinateActions(true);
            savePlaceButton.setVisibility(View.VISIBLE);
            forwardLinkButton.setVisibility(View.VISIBLE);
            linkButton.setVisibility(View.GONE);
        } else {
            receivedPlaceTitle = "ตำแหน่งจาก LINE";
            sharedLink = null;
            hasCoordinate = false;
            titleText.setText("ยังอ่านพิกัดจากลิงก์ไม่ได้");
            statusText.setText("กรุณาส่งพิกัดหรือลิงก์ Maps แบบเต็ม");
            detailText.setText("ลิงก์สั้น maps.app.goo.gl ไม่บอกพิกัดโดยตรง\n"
                    + "แนะนำให้ใช้ Smart GPS Link จาก Dorm Camera\n\nข้อมูลที่รับ:\n"
                    + (trimmed.length() > 600 ? trimmed.substring(0, 600) + "…" : trimmed));
            setCoordinateActions(false);
            savePlaceButton.setVisibility(View.GONE);
            forwardLinkButton.setVisibility(View.GONE);
            linkButton.setVisibility(View.GONE);
        }
    }

    private void pasteSharedGpsLink() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "ยังไม่มีลิงก์ในคลิปบอร์ด", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (clip != null && clip.length() > 0) handleSharedText(clip.toString());
    }

    private void forwardGpsLink() {
        if (!hasCoordinate) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
                SmartGpsLink.shareText(receivedPlaceTitle, latitude, longitude));
        try { startActivity(Intent.createChooser(share, "แชร์พิกัดนำทาง")); }
        catch (Exception ex) {
            Toast.makeText(this, "แชร์ไม่สำเร็จ: " + messageOf(ex), Toast.LENGTH_LONG).show();
        }
    }

    private void saveReceivedPlace() {
        if (!hasCoordinate) return;
        final double lat = latitude;
        final double lon = longitude;
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("ชื่อสถานที่");
        nameInput.setText(receivedPlaceTitle);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("บันทึกพิกัดในสถานที่ของฉัน")
                .setMessage(String.format(Locale.US, "%.7f, %.7f", lat, lon))
                .setView(nameInput)
                .setPositiveButton("บันทึก", null)
                .setNegativeButton("ยกเลิก", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameInput.setError("กรุณาใส่ชื่อสถานที่");
                        return;
                    }
                    try {
                        SavedGpsPlaces.save(this, name, lat, lon);
                        receivedPlaceTitle = name;
                        dialog.dismiss();
                        new AlertDialog.Builder(this)
                                .setTitle("✓ บันทึกพิกัดแล้ว")
                                .setMessage("สถานที่นี้อยู่ในเมนู “สถานที่ของฉัน” โดยไม่ต้องมีรูปภาพ")
                                .setPositiveButton("เปิดสถานที่ของฉัน", (d, which) ->
                                        startActivity(new Intent(this, MyPlacesV028Activity.class)))
                                .setNegativeButton("อยู่หน้านี้", null).show();
                    } catch (Exception err) {
                        Toast.makeText(this, "บันทึกไม่สำเร็จ: " + messageOf(err),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void showUnsupported(String message) {
        ++receiveSequence;
        lastIncomingUri = null;
        retryFileButton.setVisibility(View.GONE);
        sharedImageUri = null;
        imageView.setVisibility(View.GONE);
        savePlaceButton.setVisibility(View.GONE);
        forwardLinkButton.setVisibility(View.GONE);
        titleText.setText("รับข้อมูลไม่สำเร็จ");
        statusText.setText(message);
        detailText.setText("ลองดาวน์โหลดไฟล์จาก LINE แล้วเข้าเครื่องมือ → รับไฟล์ภาพจาก LINE / เพื่อน เพื่อตรวจ JPG หรือ ZIP");
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
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(SmartGpsLink.navigationUrl(latitude, longitude))));
        } catch (ActivityNotFoundException e) { openMap(); }
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
        if (requestCode == REQ_PICK_DOWNLOADED_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri file = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(file,
                            data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                handleSharedImage(file);
            }
            return;
        }
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
