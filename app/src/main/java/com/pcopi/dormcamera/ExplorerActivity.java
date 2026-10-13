package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExplorerActivity extends AppCompatActivity {
    private static final int REQ_LEGACY_READ = 2001;

    private TextView folderTitle;
    private TextView countText;
    private GridView mediaGrid;
    private Button selectAllButton;
    private Button clearButton;
    private Button refreshButton;
    private Button shareButton;
    private Button renameButton;
    private Button deleteButton;
    private Button closeButton;

    private final List<MediaItem> items = new ArrayList<>();
    private final Set<Uri> selected = new HashSet<>();
    private MediaAdapter adapter;
    private FolderStore folderStore;
    private ExecutorService thumbnailExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explorer);

        folderTitle = findViewById(R.id.folderTitle);
        countText = findViewById(R.id.countText);
        mediaGrid = findViewById(R.id.mediaGrid);
        selectAllButton = findViewById(R.id.selectAllButton);
        clearButton = findViewById(R.id.clearButton);
        refreshButton = findViewById(R.id.refreshButton);
        shareButton = findViewById(R.id.shareButton);
        renameButton = findViewById(R.id.renameButton);
        deleteButton = findViewById(R.id.deleteButton);
        closeButton = findViewById(R.id.closeButton);

        folderStore = new FolderStore(this);
        thumbnailExecutor = Executors.newFixedThreadPool(3);
        adapter = new MediaAdapter();
        mediaGrid.setAdapter(adapter);

        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        refreshButton.setOnClickListener(v -> loadMedia());
        shareButton.setOnClickListener(v -> shareSelected());
        renameButton.setOnClickListener(v -> renameSelected());
        deleteButton.setOnClickListener(v -> confirmDeleteSelected());
        closeButton.setOnClickListener(v -> finish());

        if (hasLegacyReadPermission()) {
            loadMedia();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_LEGACY_READ
            );
        }
    }

    private boolean hasLegacyReadPermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void loadMedia() {
        String folder = folderStore.getCurrentFolder();
        folderTitle.setText("Explorer • โฟลเดอร์: " + FolderStore.displayName(folder));
        countText.setText("กำลังอ่านรายการ...");
        items.clear();
        selected.clear();
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            List<MediaItem> loaded = new ArrayList<>();
            queryCollection(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    false,
                    FolderStore.pictureRelativePath(folder),
                    FolderStore.legacyPictureDir(folder),
                    loaded
            );
            queryCollection(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    FolderStore.videoRelativePath(folder),
                    FolderStore.legacyVideoDir(folder),
                    loaded
            );
            Collections.sort(loaded, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));

            runOnUiThread(() -> {
                items.clear();
                items.addAll(loaded);
                adapter.notifyDataSetChanged();
                updateSelectionStatus();
            });
        }).start();
    }

    private void queryCollection(
            Uri collection,
            boolean video,
            String relativePath,
            File legacyDir,
            List<MediaItem> output
    ) {
        String[] projection;
        String selection;
        String[] args;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
            selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            args = new String[]{relativePath};
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATA
            };
            selection = MediaStore.MediaColumns.DATA + " LIKE ?";
            args = new String[]{legacyDir.getAbsolutePath() + File.separator + "%"};
        }

        try (Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )) {
            if (cursor == null) return;
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                String name = cursor.getString(nameIndex);
                String mime = cursor.getString(mimeIndex);
                long date = cursor.getLong(dateIndex);
                Uri uri = ContentUris.withAppendedId(collection, id);
                output.add(new MediaItem(id, uri, name, mime, date, video));
            }
        } catch (Exception ignored) {
        }
    }

    private void handleItemTap(MediaItem item) {
        if (!selected.isEmpty()) {
            toggleSelection(item.uri);
            adapter.notifyDataSetChanged();
        } else {
            openPreview(item);
        }
    }

    private boolean handleItemLongPress(MediaItem item) {
        toggleSelection(item.uri);
        adapter.notifyDataSetChanged();
        return true;
    }

    private void selectAll() {
        selected.clear();
        for (MediaItem item : items) selected.add(item.uri);
        adapter.notifyDataSetChanged();
        updateSelectionStatus();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateSelectionStatus();
    }

    private void toggleSelection(Uri uri) {
        if (selected.contains(uri)) selected.remove(uri);
        else selected.add(uri);
        updateSelectionStatus();
    }

    private void updateSelectionStatus() {
        countText.setText(String.format(
                Locale.US,
                "ทั้งหมด %d รายการ • เลือก %d รายการ",
                items.size(),
                selected.size()
        ));
        shareButton.setEnabled(!selected.isEmpty());
        deleteButton.setEnabled(!selected.isEmpty());
        renameButton.setEnabled(selected.size() == 1);
        clearButton.setEnabled(!selected.isEmpty());
        selectAllButton.setEnabled(!items.isEmpty() && selected.size() != items.size());
    }

    private void openPreview(MediaItem item) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_URI, item.uri.toString());
        intent.putExtra(PreviewActivity.EXTRA_MIME, item.mimeType);
        intent.putExtra(PreviewActivity.EXTRA_NAME, item.name);
        startActivity(intent);
    }

    private void shareSelected() {
        if (selected.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>(selected);
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "แชร์ไฟล์ที่เลือก"));
    }

    private void renameSelected() {
        if (selected.size() != 1) return;
        Uri target = selected.iterator().next();
        MediaItem item = findItem(target);
        if (item == null) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(stripExtension(item.name));
        input.setSelection(input.length());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("เปลี่ยนชื่อไฟล์")
                .setView(input)
                .setPositiveButton("บันทึก", (d, which) -> {
                    String base = sanitizeFileName(input.getText().toString());
                    if (base.isEmpty()) {
                        Toast.makeText(this, "กรุณาใส่ชื่อไฟล์", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String newName = base + extensionOf(item.name);
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                        int changed = getContentResolver().update(item.uri, values, null, null);
                        if (changed > 0) {
                            Toast.makeText(this, "เปลี่ยนชื่อแล้ว", Toast.LENGTH_SHORT).show();
                            loadMedia();
                        } else {
                            Toast.makeText(this, "เปลี่ยนชื่อไม่สำเร็จ", Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "เปลี่ยนชื่อไม่สำเร็จ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF1565C0);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF555555);
        });
        dialog.show();
    }

    private void confirmDeleteSelected() {
        if (selected.isEmpty()) return;
        int count = selected.size();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ลบไฟล์")
                .setMessage("ลบ " + count + " รายการที่เลือกหรือไม่?")
                .setPositiveButton("ลบ", (d, which) -> deleteSelected())
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFD32F2F);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF555555);
        });
        dialog.show();
    }

    private void deleteSelected() {
        List<Uri> targets = new ArrayList<>(selected);
        new Thread(() -> {
            int deleted = 0;
            for (Uri uri : targets) {
                try {
                    if (getContentResolver().delete(uri, null, null) > 0) deleted++;
                } catch (Exception ignored) {
                }
            }
            int finalDeleted = deleted;
            runOnUiThread(() -> {
                Toast.makeText(this, "ลบแล้ว " + finalDeleted + " รายการ", Toast.LENGTH_LONG).show();
                loadMedia();
            });
        }).start();
    }

    private MediaItem findItem(Uri uri) {
        for (MediaItem item : items) if (item.uri.equals(uri)) return item;
        return null;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private String sanitizeFileName(String raw) {
        String value = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (value.length() > 80) value = value.substring(0, 80).trim();
        return value;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && hasLegacyReadPermission()) loadMedia();
    }

    @Override
    protected void onDestroy() {
        if (thumbnailExecutor != null) thumbnailExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_READ) {
            if (hasLegacyReadPermission()) loadMedia();
            else Toast.makeText(this, "ต้องอนุญาตสิทธิ์อ่านไฟล์เพื่อใช้ Explorer", Toast.LENGTH_LONG).show();
        }
    }

    private final class MediaAdapter extends BaseAdapter {
        private final LruCache<String, Bitmap> thumbnailCache = new LruCache<>(32);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout root;
            ImageView image;
            CheckBox check;
            TextView badge;

            if (convertView instanceof FrameLayout) {
                root = (FrameLayout) convertView;
                image = (ImageView) root.findViewWithTag("image");
                check = (CheckBox) root.findViewWithTag("check");
                badge = (TextView) root.findViewWithTag("badge");
            } else {
                root = new FrameLayout(ExplorerActivity.this);
                root.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

                image = new ImageView(ExplorerActivity.this);
                image.setTag("image");
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                check = new CheckBox(ExplorerActivity.this);
                check.setTag("check");
                FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
                root.addView(check, checkParams);

                badge = new TextView(ExplorerActivity.this);
                badge.setTag("badge");
                badge.setTextColor(0xFFFFFFFF);
                badge.setBackgroundColor(0x99000000);
                badge.setPadding(dp(5), dp(3), dp(5), dp(3));
                badge.setTextSize(11f);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START
                );
                root.addView(badge, badgeParams);
            }

            MediaItem item = items.get(position);

            View.OnClickListener previewClick = v -> handleItemTap(item);
            View.OnLongClickListener selectLong = v -> handleItemLongPress(item);
            root.setOnClickListener(previewClick);
            root.setOnLongClickListener(selectLong);
            image.setOnClickListener(previewClick);
            image.setOnLongClickListener(selectLong);
            badge.setOnClickListener(previewClick);
            badge.setOnLongClickListener(selectLong);

            check.setOnCheckedChangeListener(null);
            check.setChecked(selected.contains(item.uri));
            check.setFocusable(false);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(item.uri);
                else selected.remove(item.uri);
                updateSelectionStatus();
            });

            badge.setText(item.video ? "▶ " + item.name : item.name);
            image.setImageDrawable(null);
            image.setTag(R.id.thumbnail_uri_tag, item.uri.toString());
            loadThumbnail(item, image);
            return root;
        }

        private void loadThumbnail(MediaItem item, ImageView target) {
            String key = item.uri.toString();
            Bitmap cached = thumbnailCache.get(key);
            if (cached != null) {
                target.setImageBitmap(cached);
                return;
            }

            thumbnailExecutor.submit(() -> {
                Bitmap bitmap = null;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bitmap = getContentResolver().loadThumbnail(item.uri, new Size(360, 360), null);
                    } else if (item.video) {
                        bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                                getContentResolver(), item.id,
                                MediaStore.Video.Thumbnails.MINI_KIND, null
                        );
                    } else {
                        bitmap = MediaStore.Images.Thumbnails.getThumbnail(
                                getContentResolver(), item.id,
                                MediaStore.Images.Thumbnails.MINI_KIND, null
                        );
                    }
                } catch (Exception ignored) {
                }

                Bitmap finalBitmap = bitmap;
                if (finalBitmap != null) thumbnailCache.put(key, finalBitmap);
                runOnUiThread(() -> {
                    Object tag = target.getTag(R.id.thumbnail_uri_tag);
                    if (key.equals(tag) && finalBitmap != null) target.setImageBitmap(finalBitmap);
                });
            });
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class MediaItem {
        final long id;
        final Uri uri;
        final String name;
        final String mimeType;
        final long dateAdded;
        final boolean video;

        MediaItem(long id, Uri uri, String name, String mimeType, long dateAdded, boolean video) {
            this.id = id;
            this.uri = uri;
            this.name = name != null ? name : "ไม่ทราบชื่อ";
            this.mimeType = mimeType != null ? mimeType : (video ? "video/*" : "image/*");
            this.dateAdded = dateAdded;
            this.video = video;
        }
    }
}
