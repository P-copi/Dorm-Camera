package com.pcopi.dormcamera;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int MENU_SHARE = 1;
    private static final int MENU_COPY = 2;
    private static final int MENU_MOVE = 3;
    private static final int MENU_RENAME = 4;
    private static final int MENU_DELETE = 5;

    private TextView folderTitle;
    private TextView countText;
    private GridView mediaGrid;
    private Button selectAllButton;
    private Button clearButton;
    private Button refreshButton;
    private Button toolsButton;
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
        toolsButton = findViewById(R.id.toolsButton);
        closeButton = findViewById(R.id.closeButton);

        folderStore = new FolderStore(this);
        thumbnailExecutor = Executors.newFixedThreadPool(3);
        adapter = new MediaAdapter();
        mediaGrid.setAdapter(adapter);

        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        refreshButton.setOnClickListener(v -> loadMedia());
        toolsButton.setOnClickListener(this::showToolsMenu);
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
        toolsButton.setEnabled(!selected.isEmpty());
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

    private void showToolsMenu(View anchor) {
        if (selected.isEmpty()) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_SHARE, 0, "แชร์");
        popup.getMenu().add(0, MENU_COPY, 1, "คัดลอก (Copy)");
        popup.getMenu().add(0, MENU_MOVE, 2, "ย้าย (Move)");
        popup.getMenu().add(0, MENU_RENAME, 3, "เปลี่ยนชื่อ");
        popup.getMenu().add(0, MENU_DELETE, 4, "ลบ");
        popup.getMenu().findItem(MENU_RENAME).setEnabled(selected.size() == 1);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SHARE:
                    shareSelected();
                    return true;
                case MENU_COPY:
                    chooseDestination(false);
                    return true;
                case MENU_MOVE:
                    chooseDestination(true);
                    return true;
                case MENU_RENAME:
                    renameSelected();
                    return true;
                case MENU_DELETE:
                    confirmDeleteSelected();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
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

    private void chooseDestination(boolean move) {
        if (selected.isEmpty()) return;
        String current = folderStore.getCurrentFolder();
        List<String> targets = new ArrayList<>();
        for (String folder : folderStore.getKnownFolders()) {
            if (!folder.equals(current)) targets.add(folder);
        }

        List<String> labels = new ArrayList<>();
        for (String folder : targets) labels.add(FolderStore.displayName(folder));
        labels.add("＋ สร้างโฟลเดอร์ใหม่...");

        String title = move ? "ย้ายไปโฟลเดอร์" : "คัดลอกไปโฟลเดอร์";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == targets.size()) {
                        promptNewDestination(move);
                    } else {
                        confirmTransfer(move, targets.get(which));
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void promptNewDestination(boolean move) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("เช่น 20");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("สร้างโฟลเดอร์ปลายทาง")
                .setView(input)
                .setPositiveButton("สร้าง", (d, which) -> {
                    String folder = FolderStore.normalize(input.getText().toString());
                    if (folder.isEmpty()) {
                        Toast.makeText(this, "กรุณาใส่ชื่อโฟลเดอร์", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (folder.equals(folderStore.getCurrentFolder())) {
                        Toast.makeText(this, "โฟลเดอร์นี้เป็นโฟลเดอร์ต้นทางอยู่แล้ว", Toast.LENGTH_LONG).show();
                        return;
                    }
                    folderStore.addFolder(folder);
                    confirmTransfer(move, folder);
                })
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF1565C0);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF555555);
        });
        dialog.show();
    }

    private void confirmTransfer(boolean move, String targetFolder) {
        List<MediaItem> chosen = selectedItemsSnapshot();
        if (chosen.isEmpty()) return;

        String action = move ? "ย้าย" : "คัดลอก";
        String source = FolderStore.displayName(folderStore.getCurrentFolder());
        String target = FolderStore.displayName(targetFolder);
        String message = action + " " + chosen.size() + " รายการ\n"
                + "จาก: " + source + "\n"
                + "ไป: " + target + "\n\n"
                + "ถ้ามีชื่อไฟล์ซ้ำ ระบบจะสร้างชื่อใหม่อัตโนมัติและไม่เขียนทับไฟล์เดิม";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(action + "ไฟล์")
                .setMessage(message)
                .setPositiveButton(action, (d, which) -> performTransfer(chosen, targetFolder, move))
                .setNegativeButton("ยกเลิก", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF1565C0);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF555555);
        });
        dialog.show();
    }

    private List<MediaItem> selectedItemsSnapshot() {
        List<MediaItem> chosen = new ArrayList<>();
        for (MediaItem item : items) {
            if (selected.contains(item.uri)) chosen.add(item);
        }
        return chosen;
    }

    private void performTransfer(List<MediaItem> chosen, String targetFolder, boolean move) {
        String action = move ? "ย้าย" : "คัดลอก";
        toolsButton.setEnabled(false);
        countText.setText("กำลัง" + action + " " + chosen.size() + " รายการ...");
        folderStore.addFolder(targetFolder);

        new Thread(() -> {
            int success = 0;
            int failed = 0;
            for (MediaItem item : chosen) {
                boolean ok;
                try {
                    ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? transferScoped(item, targetFolder, move)
                            : transferLegacy(item, targetFolder, move);
                } catch (Exception e) {
                    ok = false;
                }
                if (ok) success++;
                else failed++;
            }

            int finalSuccess = success;
            int finalFailed = failed;
            runOnUiThread(() -> {
                String result = action + "สำเร็จ " + finalSuccess + " รายการ";
                if (finalFailed > 0) result += " • ไม่สำเร็จ " + finalFailed + " รายการ";
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                loadMedia();
            });
        }).start();
    }

    private boolean transferScoped(MediaItem item, String targetFolder, boolean move) throws Exception {
        Uri collection = item.video
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String relativePath = item.video
                ? FolderStore.videoRelativePath(targetFolder)
                : FolderStore.pictureRelativePath(targetFolder);
        String targetName = uniqueDisplayName(collection, relativePath, item.name);

        if (move) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, targetName);
            return getContentResolver().update(item.uri, values, null, null) > 0;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, targetName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri destination = getContentResolver().insert(collection, values);
        if (destination == null) return false;

        boolean copied = false;
        try (InputStream in = getContentResolver().openInputStream(item.uri);
             OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (in == null || out == null) return false;
            copyStream(in, out);
            copied = true;
        } finally {
            if (copied) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
            } else {
                try {
                    getContentResolver().delete(destination, null, null);
                } catch (Exception ignored) {
                }
            }
        }
        return copied;
    }

    private String uniqueDisplayName(Uri collection, String relativePath, String originalName) {
        if (!displayNameExists(collection, relativePath, originalName)) return originalName;
        String base = stripExtension(originalName);
        String extension = extensionOf(originalName);
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (!displayNameExists(collection, relativePath, candidate)) return candidate;
        }
        return base + " (copy-" + System.currentTimeMillis() + ")" + extension;
    }

    private boolean displayNameExists(Uri collection, String relativePath, String name) {
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                selection,
                new String[]{relativePath, name},
                null
        )) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean transferLegacy(MediaItem item, String targetFolder, boolean move) throws Exception {
        String sourcePath = queryLegacyDataPath(item.uri);
        if (sourcePath == null) return false;
        File source = new File(sourcePath);
        if (!source.isFile()) return false;

        File destinationDir = item.video
                ? FolderStore.legacyVideoDir(targetFolder)
                : FolderStore.legacyPictureDir(targetFolder);
        if (!destinationDir.exists() && !destinationDir.mkdirs()) return false;
        File destination = uniqueLegacyFile(destinationDir, item.name);

        if (move) {
            boolean moved = source.renameTo(destination);
            if (!moved) {
                copyFile(source, destination);
                moved = source.delete();
            }
            if (!moved) return false;
            try {
                getContentResolver().delete(item.uri, null, null);
            } catch (Exception ignored) {
            }
        } else {
            copyFile(source, destination);
        }

        MediaScannerConnection.scanFile(
                this,
                new String[]{destination.getAbsolutePath()},
                new String[]{item.mimeType},
                null
        );
        return true;
    }

    private String queryLegacyDataPath(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DATA},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private File uniqueLegacyFile(File dir, String originalName) {
        File direct = new File(dir, originalName);
        if (!direct.exists()) return direct;
        String base = stripExtension(originalName);
        String extension = extensionOf(originalName);
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + " (copy-" + System.currentTimeMillis() + ")" + extension);
    }

    private void copyFile(File source, File destination) throws Exception {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(destination)) {
            copyStream(in, out);
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
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
