package com.pcopi.dormcamera;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** V0.2.9 My Places with prepared no-GPS image and LINE-safe queue order. */
public final class MyPlacesV028Activity extends AppCompatActivity {
    private static final String PREFS = "my_places_v025";
    private static final String KEY_FAVORITES = "favorite_uris";
    private static final int SHARE_COMBINED = 4;
    private static final int SHARE_LOCATION = 1;
    private static final int SHARE_ORIGINAL_FILE = 2;
    private static final int SHARE_VISIBLE_NO_GPS = 3;

    private final List<PlaceItem> allPlaces = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final List<Integer> shareQueue = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText searchBox;
    private PressFeedbackButton favoritesButton;
    private TextView summaryText;
    private LinearLayout listContainer;
    private boolean favoritesOnly;
    private boolean firstResume = true;
    private boolean waitingForShareReturn;
    private int shareQueueIndex;
    @Nullable private PlaceItem queuedShareItem;
    @Nullable private Uri preparedVisibleNoGpsUri;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_FAVORITES, Collections.emptySet());
        if (saved != null) favorites.addAll(saved);
        buildUi();
        loadPlaces();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForShareReturn) {
            waitingForShareReturn = false;
            handler.postDelayed(this::launchNextQueuedShare, 500L);
            return;
        }
        if (firstResume) {
            firstResume = false;
        } else if (shareQueue.isEmpty()) {
            loadPlaces();
        }
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
        title.setText("สถานที่ของฉัน");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top);

        TextView subtitle = new TextView(this);
        subtitle.setText("V0.2.9 • ค้นหา • ⭐ โปรด • Smart Share 3 แบบ");
        subtitle.setTextColor(0xFF8FC9A3);
        subtitle.setTextSize(12f);
        subtitle.setPadding(dp(2), dp(5), 0, dp(8));
        root.addView(subtitle);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("🔎 ค้นหาชื่อสถานที่ / ชื่อภาพ");
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setHintTextColor(0xFF8F929A);
        searchBox.setTextSize(15f);
        searchBox.setPadding(dp(14), 0, dp(14), 0);
        searchBox.setBackgroundResource(R.drawable.bg_control_panel);
        root.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderPlaces(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, dp(8), 0, dp(7));
        favoritesButton = makeButton("☆ ทั้งหมด", 13f);
        favoritesButton.setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            updateFavoriteFilterLabel();
            renderPlaces();
        });
        filterRow.addView(favoritesButton, new LinearLayout.LayoutParams(dp(126), dp(42)));
        summaryText = new TextView(this);
        summaryText.setTextColor(0xFFB9BBC2);
        summaryText.setTextSize(12f);
        summaryText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        summaryText.setPadding(dp(8), 0, dp(4), 0);
        filterRow.addView(summaryText, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(filterRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        PressFeedbackButton addPlace = makeButton("＋  เพิ่มสถานที่จากภาพ", 14f);
        addPlace.setOnClickListener(v -> startActivity(new Intent(this, PhotoNavigatorV024Activity.class)));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        addParams.setMargins(0, dp(8), 0, 0);
        root.addView(addPlace, addParams);

        setContentView(root);
        updateFavoriteFilterLabel();
    }

    private void loadPlaces() {
        summaryText.setText("กำลังอ่าน...");
        new Thread(() -> {
            List<PlaceItem> loaded = new ArrayList<>();
            ContentResolver resolver = getContentResolver();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
            };
            String selection;
            String[] args;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                args = new String[]{Environment.DIRECTORY_PICTURES + "/DormCamera/%"};
            } else {
                selection = MediaStore.Images.Media.DATA + " LIKE ?";
                args = new String[]{"%/Pictures/DormCamera/%"};
            }

            try (Cursor cursor = resolver.query(
                    collection, projection, selection, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                    int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIndex);
                        String name = cursor.getString(nameIndex);
                        long dateAdded = cursor.getLong(dateIndex);
                        Uri uri = ContentUris.withAppendedId(collection, id);
                        try (InputStream input = resolver.openInputStream(uri)) {
                            if (input == null) continue;
                            double[] ll = new ExifInterface(input).getLatLong();
                            if (ll == null || ll.length < 2 || !valid(ll[0], ll[1])) continue;
                            Bitmap thumb = null;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try { thumb = resolver.loadThumbnail(uri, new Size(160, 160), null); }
                                catch (Exception ignored) {}
                            }
                            loaded.add(new PlaceItem(uri,
                                    name == null ? "สถานที่" : name,
                                    ll[0], ll[1], dateAdded, thumb));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                String error = messageOf(e);
                runOnUiThread(() -> {
                    summaryText.setText("อ่านรายการไม่สำเร็จ");
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                });
                return;
            }

            runOnUiThread(() -> {
                allPlaces.clear();
                allPlaces.addAll(loaded);
                cleanupFavorites();
                renderPlaces();
            });
        }, "my-places-v029-loader").start();
    }

    private void renderPlaces() {
        if (listContainer == null || searchBox == null) return;
        listContainer.removeAllViews();
        String query = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (PlaceItem item : allPlaces) {
            boolean favorite = favorites.contains(item.uri.toString());
            if (favoritesOnly && !favorite) continue;
            String friendly = friendlyName(item.name).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !friendly.contains(query)
                    && !item.name.toLowerCase(Locale.ROOT).contains(query)) continue;
            listContainer.addView(makePlaceCard(item, favorite));
            shown++;
        }

        int favoriteCount = 0;
        for (PlaceItem item : allPlaces) if (favorites.contains(item.uri.toString())) favoriteCount++;
        summaryText.setText(String.format(Locale.getDefault(), "แสดง %d / %d • ⭐ %d",
                shown, allPlaces.size(), favoriteCount));

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(allPlaces.isEmpty()
                    ? "ยังไม่พบภาพที่มี GPS\nสร้างสำเนาพร้อม GPS ก่อน แล้วกลับมาหน้านี้"
                    : "ไม่พบรายการที่ตรงกับการค้นหา");
            empty.setTextColor(0xFFB9BBC2);
            empty.setTextSize(15f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(50), dp(12), dp(30));
            listContainer.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private View makePlaceCard(PlaceItem item, boolean favorite) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackgroundResource(R.drawable.bg_control_panel);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(cardParams);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF17181D);
        if (item.thumbnail != null) image.setImageBitmap(item.thumbnail);
        image.setOnClickListener(v -> viewImage(item));
        main.addView(image, new LinearLayout.LayoutParams(dp(78), dp(78)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(4), 0);
        TextView name = new TextView(this);
        name.setText(friendlyName(item.name));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(16f);
        name.setMaxLines(2);
        info.addView(name);
        TextView coord = new TextView(this);
        coord.setText(String.format(Locale.US, "📍 %.6f, %.6f", item.lat, item.lon));
        coord.setTextColor(0xFFB9BBC2);
        coord.setTextSize(12f);
        coord.setPadding(0, dp(5), 0, 0);
        info.addView(coord);
        main.addView(info, new LinearLayout.LayoutParams(0, dp(78), 1f));

        PressFeedbackButton star = makeButton(favorite ? "★" : "☆", 22f);
        star.setContentDescription(favorite ? "นำออกจากรายการโปรด" : "เพิ่มเป็นรายการโปรด");
        star.setOnClickListener(v -> toggleFavorite(item));
        main.addView(star, new LinearLayout.LayoutParams(dp(52), dp(48)));
        card.addView(main);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        PressFeedbackButton map = makeButton("🗺 แผนที่", 12f);
        map.setOnClickListener(v -> openMap(item));
        PressFeedbackButton nav = makeButton("🧭 นำทาง", 12f);
        nav.setOnClickListener(v -> navigate(item));
        PressFeedbackButton share = makeButton("📤 แชร์", 12f);
        share.setOnClickListener(v -> showSmartShare(item));
        actions.addView(map, actionParams(0));
        actions.addView(nav, actionParams(dp(6)));
        actions.addView(share, actionParams(dp(6)));
        card.addView(actions);
        return card;
    }

    private void showSmartShare(PlaceItem item) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(4), dp(18), dp(4));

        TextView note = new TextView(this);
        note.setText("เลือกตำแหน่ง + ภาพปกติ เพื่อทดลองส่งภาพพร้อมข้อความในครั้งเดียว\n"
                + "ภาพไม่มี GPS จะเตรียมให้เสร็จก่อนเริ่มแชร์\n"
                + "ไฟล์ต้นฉบับพร้อม GPS จะส่งเป็นรายการสุดท้าย");
        note.setTextColor(0xFFB9BBC2);
        note.setTextSize(14f);
        note.setLineSpacing(0f, 1.15f);
        note.setPadding(0, 0, 0, dp(10));
        content.addView(note);

        CheckBox location = checkBox("📍 แชร์ตำแหน่ง + ลิงก์แผนที่", true);
        CheckBox original = checkBox("📎 แชร์ภาพเป็นไฟล์ (รักษา GPS)", false);
        CheckBox visible = checkBox("🖼 ภาพปกติ (เห็นภาพ • ไม่มี GPS)", true);
        content.addView(location, checkParams());
        content.addView(original, checkParams());
        content.addView(visible, checkParams());

        TextView privacy = new TextView(this);
        privacy.setText("หาก LINE ไม่รับข้อความพร้อมภาพ ให้กดวางข้อความที่เตรียมไว้ในช่องแชต • ไม่ใช้ ZIP");
        privacy.setTextColor(0xFF8F929A);
        privacy.setTextSize(11f);
        privacy.setPadding(0, dp(8), 0, 0);
        content.addView(privacy);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Smart Share")
                .setView(content)
                .setPositiveButton("เริ่มแชร์", null)
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean useLocation = location.isChecked();
            boolean useOriginal = original.isChecked();
            boolean useVisible = visible.isChecked();
            if (!useLocation && !useOriginal && !useVisible) {
                Toast.makeText(this, "กรุณาเลือกอย่างน้อย 1 รายการ", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!useVisible) {
                preparedVisibleNoGpsUri = null;
                dialog.dismiss();
                beginShareQueue(item, useLocation, useVisible, useOriginal);
                return;
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("กำลังเตรียมภาพ...");
            new Thread(() -> {
                Uri cleanUri = null;
                String error = null;
                try {
                    cleanUri = ShareImageSanitizer.createGpsFreeShareUri(this, item.uri, item.name);
                } catch (Exception e) {
                    error = messageOf(e);
                }
                Uri finalCleanUri = cleanUri;
                String finalError = error;
                runOnUiThread(() -> {
                    if (finalCleanUri == null) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("เริ่มแชร์");
                        Toast.makeText(this,
                                finalError == null ? "เตรียมภาพแบบไม่มี GPS ไม่สำเร็จ" : finalError,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    preparedVisibleNoGpsUri = finalCleanUri;
                    dialog.dismiss();
                    beginShareQueue(item, useLocation, true, useOriginal);
                });
            }, "smart-share-prepare-no-gps").start();
        }));
        dialog.show();
    }

    private void beginShareQueue(PlaceItem item, boolean useLocation, boolean useVisible, boolean useOriginal) {
        shareQueue.clear();
        if (useLocation && useVisible) shareQueue.add(SHARE_COMBINED);
        else {
            if (useLocation) shareQueue.add(SHARE_LOCATION);
            if (useVisible) shareQueue.add(SHARE_VISIBLE_NO_GPS);
        }
        if (useOriginal) shareQueue.add(SHARE_ORIGINAL_FILE);
        queuedShareItem = item;
        shareQueueIndex = 0;
        launchNextQueuedShare();
    }

    private CheckBox checkBox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(15f);
        box.setChecked(checked);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(4), 0, dp(4), 0);
        return box;
    }

    private LinearLayout.LayoutParams checkParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, 0, 0, dp(4));
        return params;
    }

    private void launchNextQueuedShare() {
        PlaceItem item = queuedShareItem;
        if (item == null || shareQueueIndex >= shareQueue.size()) {
            if (queuedShareItem != null) {
                Toast.makeText(this, "จบขั้นตอนเปิดหน้าต่างแชร์ • ตรวจสอบการส่งใน LINE", Toast.LENGTH_SHORT).show();
            }
            queuedShareItem = null;
            preparedVisibleNoGpsUri = null;
            shareQueue.clear();
            shareQueueIndex = 0;
            loadPlaces();
            return;
        }

        int kind = shareQueue.get(shareQueueIndex++);
        String step = shareQueueIndex + "/" + shareQueue.size();
        if (kind == SHARE_COMBINED) {
            Toast.makeText(this, "รายการ " + step + " • ภาพ + ลิงก์ในครั้งเดียว", Toast.LENGTH_SHORT).show();
            shareCombined(item);
        } else if (kind == SHARE_LOCATION) {
            Toast.makeText(this, "รายการ " + step + " • ตำแหน่ง + ลิงก์", Toast.LENGTH_SHORT).show();
            shareLocationText(item);
        } else if (kind == SHARE_VISIBLE_NO_GPS) {
            Toast.makeText(this, "รายการ " + step + " • ภาพปกติไม่มี GPS", Toast.LENGTH_SHORT).show();
            shareVisibleImageWithoutGps(item);
        } else {
            Toast.makeText(this, "รายการ " + step + " • ไฟล์ต้นฉบับพร้อม GPS (รายการสุดท้าย)", Toast.LENGTH_SHORT).show();
            shareOriginalFile(item);
        }
    }

    private void shareCombined(PlaceItem item) {
        Uri cleanUri = preparedVisibleNoGpsUri;
        if (cleanUri == null) {
            Toast.makeText(this, "เตรียมภาพไม่สำเร็จ", Toast.LENGTH_LONG).show();
            handler.postDelayed(this::launchNextQueuedShare, 300L);
            return;
        }
        String message = buildShareText(item);
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Dorm Camera Maps", message));
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, cleanUri);
        share.putExtra(Intent.EXTRA_TEXT, message);
        share.putExtra(Intent.EXTRA_SUBJECT, friendlyName(item.name));
        share.setClipData(ClipData.newUri(getContentResolver(), "DormCamera_NoGPS", cleanUri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Toast.makeText(this, "เตรียมภาพและลิงก์แล้ว • ถ้า LINE ไม่แสดงข้อความ ให้กดวาง", Toast.LENGTH_LONG).show();
        launchShareIntent(share, "แชร์ภาพ + ตำแหน่ง");
    }

    private void shareLocationText(PlaceItem item) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, friendlyName(item.name));
        share.putExtra(Intent.EXTRA_TEXT, buildShareText(item));
        launchShareIntent(share, "แชร์ตำแหน่ง / ลิงก์แผนที่");
    }

    private void shareOriginalFile(PlaceItem item) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/octet-stream");
        share.putExtra(Intent.EXTRA_STREAM, item.uri);
        share.putExtra(Intent.EXTRA_TITLE, item.name);
        share.setClipData(ClipData.newUri(getContentResolver(), item.name, item.uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchShareIntent(share, "แชร์ภาพเป็นไฟล์ (รักษา GPS)");
    }

    private void shareVisibleImageWithoutGps(PlaceItem item) {
        Uri cleanUri = preparedVisibleNoGpsUri;
        if (cleanUri == null) {
            Toast.makeText(this, "ไม่พบภาพไม่มี GPS ที่เตรียมไว้", Toast.LENGTH_LONG).show();
            handler.postDelayed(this::launchNextQueuedShare, 300L);
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        String mime = getContentResolver().getType(cleanUri);
        share.setType(mime != null && mime.startsWith("image/") ? mime : "image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, cleanUri);
        share.putExtra(Intent.EXTRA_TITLE, friendlyName(item.name) + "_NoGPS");
        share.setClipData(ClipData.newUri(getContentResolver(), "DormCamera_NoGPS", cleanUri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchShareIntent(share, "แชร์ภาพปกติ (ไม่มี GPS)");
    }

    private void launchShareIntent(Intent share, String chooserTitle) {
        try {
            waitingForShareReturn = true;
            startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            waitingForShareReturn = false;
            Toast.makeText(this, "เปิดการแชร์ไม่สำเร็จ: " + messageOf(e), Toast.LENGTH_LONG).show();
            handler.postDelayed(this::launchNextQueuedShare, 300L);
        }
    }

    private String buildShareText(PlaceItem item) {
        String coord = String.format(Locale.US, "%.7f,%.7f", item.lat, item.lon);
        String link = "https://www.google.com/maps/search/?api=1&query=" + coord;
        return friendlyName(item.name)
                + "\nพิกัด: " + coord
                + "\nเปิดแผนที่: " + link;
    }

    private LinearLayout.LayoutParams actionParams(int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMarginStart(startMargin);
        return params;
    }

    private void toggleFavorite(PlaceItem item) {
        String key = item.uri.toString();
        if (favorites.contains(key)) favorites.remove(key); else favorites.add(key);
        prefs.edit().putStringSet(KEY_FAVORITES, new HashSet<>(favorites)).apply();
        renderPlaces();
    }

    private void cleanupFavorites() {
        Set<String> validUris = new HashSet<>();
        for (PlaceItem item : allPlaces) validUris.add(item.uri.toString());
        if (favorites.retainAll(validUris)) {
            prefs.edit().putStringSet(KEY_FAVORITES, new HashSet<>(favorites)).apply();
        }
    }

    private void updateFavoriteFilterLabel() {
        favoritesButton.setText(favoritesOnly ? "★ รายการโปรด" : "☆ ทั้งหมด");
    }

    private void viewImage(PlaceItem item) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(item.uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบแอปเปิดภาพ", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMap(PlaceItem item) {
        String coord = String.format(Locale.US, "%.7f,%.7f", item.lat, item.lon);
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

    private void navigate(PlaceItem item) {
        String coord = String.format(Locale.US, "%.7f,%.7f", item.lat, item.lon);
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
        nav.setPackage("com.google.android.apps.maps");
        try { startActivity(nav); }
        catch (ActivityNotFoundException e) { openMap(item); }
    }

    private String friendlyName(String name) {
        if (name == null || name.trim().isEmpty()) return "สถานที่";
        String out = name.trim();
        int dot = out.lastIndexOf('.');
        if (dot > 0) out = out.substring(0, dot);
        return out;
    }

    private boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private PressFeedbackButton makeButton(String text, float size) {
        PressFeedbackButton button = new PressFeedbackButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(size);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackgroundResource(R.drawable.bg_button_dark);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PlaceItem {
        final Uri uri;
        final String name;
        final double lat;
        final double lon;
        final long dateAdded;
        @Nullable final Bitmap thumbnail;

        PlaceItem(Uri uri, String name, double lat, double lon, long dateAdded, @Nullable Bitmap thumbnail) {
            this.uri = uri;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.dateAdded = dateAdded;
            this.thumbnail = thumbnail;
        }
    }
}
