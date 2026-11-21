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
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.ListView;

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
    private LinearLayout compactSearchBar;
    private EditText compactSearchInput;
    private Spinner compactSearchType;
    private TextView countText;
    private GridView mediaGrid;
    private Button selectAllButton;
    private Button clearButton;
    private Button refreshButton;
    private Button toolsButton;
    private Button closeButton;

    private final List<MediaItem> items = new ArrayList<>();
    private final List<MediaItem> unfilteredMedia = new ArrayList<>();
    private String mediaSearchText = "";
    private int mediaTypeFilter = 0;
    private final Set<Uri> selected = new HashSet<>();
    private MediaAdapter adapter;
    private FolderStore folderStore;
    private ExecutorService thumbnailExecutor;
    private boolean showAllFolders = true;
    private boolean recentOnly = false;
    private String browsingFolder = "";
    private int loadGeneration = 0;
    private boolean folderGallery = true;
    private final List<String> visibleFolders = new ArrayList<>();
    private final List<MediaItem> directFolderMedia = new ArrayList<>();
    private BaseAdapter folderAdapter;
    private Button viewFolderMediaButton;
    private int gallerySortMode = 0;
    private static final String EXPLORER_PREFS = "dorm_explorer_preferences";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explorer);

        folderTitle = findViewById(R.id.folderTitle);
        compactSearchBar = findViewById(R.id.compactSearchBar);
        compactSearchInput = findViewById(R.id.compactSearchInput);
        compactSearchType = findViewById(R.id.compactSearchType);
        findViewById(R.id.compactSearchClose).setOnClickListener(v -> {
            compactSearchBar.setVisibility(View.GONE);
            mediaSearchText = "";
            mediaTypeFilter = 0;
            if (!folderGallery) applyMediaSearch();
        });
        compactSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mediaSearchText = s.toString().trim();
                if (!folderGallery) applyMediaSearch();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        ArrayAdapter<String> compactTypes = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ทั้งหมด", "ภาพ", "วิดีโอ"});
        compactSearchType.setAdapter(compactTypes);
        compactSearchType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                mediaTypeFilter = position;
                if (!folderGallery) applyMediaSearch();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        countText = findViewById(R.id.countText);
        mediaGrid = findViewById(R.id.mediaGrid);
        viewFolderMediaButton = findViewById(R.id.viewFolderMediaButton);
        viewFolderMediaButton.setOnClickListener(v -> {
            showAllFolders = false;
            recentOnly = false;
            loadMedia();
        });
        selectAllButton = findViewById(R.id.selectAllButton);
        clearButton = findViewById(R.id.clearButton);
        refreshButton = findViewById(R.id.refreshButton);
        toolsButton = findViewById(R.id.toolsButton);
        closeButton = findViewById(R.id.closeButton);

        folderStore = new FolderStore(this);
        browsingFolder = FolderStore.normalizePath(getSharedPreferences(EXPLORER_PREFS, MODE_PRIVATE)
                .getString("last_browsed_folder", folderStore.getCurrentFolder()));
        gallerySortMode = getSharedPreferences(EXPLORER_PREFS, MODE_PRIVATE).getInt("gallery_sort_mode", 0);
        folderTitle.setOnClickListener(v -> {
            if (folderGallery) showFolderSearchDialog();
            else showMediaSearchDialog();
        });
        findViewById(R.id.allMediaTab).setOnClickListener(v -> {
            folderGallery = false; showAllFolders = true; recentOnly = false; loadMedia();
        });
        findViewById(R.id.folderTreeTab).setOnClickListener(v -> {
            folderGallery = true; browsingFolder = ""; showFolderGallery();
        });
        findViewById(R.id.recentMediaTab).setOnClickListener(v -> {
            folderGallery = false; showAllFolders = true; recentOnly = true; loadMedia();
        });
        thumbnailExecutor = Executors.newFixedThreadPool(3);
        adapter = new MediaAdapter();
        mediaGrid.setAdapter(adapter);
        folderAdapter = new FolderGalleryAdapter();

        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        refreshButton.setOnClickListener(v -> scanAndRefreshMedia());
        refreshButton.setOnLongClickListener(v -> { diagnoseCurrentFolder(); return true; });
        toolsButton.setOnClickListener(this::showToolsMenu);
        closeButton.setOnClickListener(v -> navigateBackInExplorer());

        if (hasLegacyReadPermission()) {
            showFolderGallery();
            scanAndRefreshMedia();
        } else {
            String[] requested = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                    : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, requested, REQ_LEGACY_READ);
        }
    }

    /** Index files copied into DormCamera by other apps without moving or modifying them. */
    /** Read-only diagnostics for missing photos in a selected folder. */
    private void diagnoseCurrentFolder() {
        final String folder = browsingFolder;
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            File directory = FolderStore.legacyPictureDir(folder);
            report.append("Explorer V0.2.41 • READ ONLY\\n");
            report.append("Folder: ").append(folder.isEmpty() ? "(root)" : folder).append("\\n");
            report.append("Disk: ").append(directory.getAbsolutePath()).append("\\n");
            File[] children = directory.listFiles();
            report.append("Disk listing: ").append(children == null ? "unavailable" : children.length).append("\\n");
            if (children != null) {
                int shown = 0;
                for (File file : children) {
                    if (!file.isFile()) continue;
                    report.append("FILE ").append(file.getName()).append(" (").append(file.length()).append(" B)\\n");
                    if (++shown >= 20) { report.append("... additional files omitted\\n"); break; }
                }
            }
            String target = normalizeMediaPath(FolderStore.pictureRelativePath(folder));
            report.append("Expected MediaStore: ").append(target).append("\\n");
            int exact = 0, nested = 0, nameMatches = 0;
            String wantedName = "20261113_145509.jpg";
            String[] projection = {MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATA};
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null, null)) {
                if (cursor != null) {
                    int n = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                    int p = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
                    int d = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(n);
                        String rel = cursor.getString(p);
                        String normalized = normalizeMediaPath(rel);
                        if (normalized.equals(target)) exact++;
                        else if (normalized.startsWith(target)) nested++;
                        if (wantedName.equalsIgnoreCase(name)) {
                            nameMatches++;
                            report.append("TARGET ").append(name).append("\\n");
                            report.append("RELATIVE_PATH: ").append(rel).append("\\n");
                            report.append("DATA: ").append(d >= 0 ? cursor.getString(d) : "(not available)").append("\\n");
                        }
                    }
                }
            } catch (Exception error) { report.append("MediaStore error: ").append(error.getClass().getSimpleName()).append("\\n"); }
            report.append("MediaStore direct: ").append(exact).append(", nested: ").append(nested).append("\\n");
            report.append("Known target filename matches: ").append(nameMatches);
            final String message = report.toString();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("ตรวจสอบภาพในโฟลเดอร์")
                    .setMessage(message)
                    .setPositiveButton("คัดลอกผล", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DormCamera diagnostics", message));
                        Toast.makeText(this, "คัดลอกผลตรวจสอบแล้ว", Toast.LENGTH_SHORT).show();
                    }).setNegativeButton("ปิด", null).show());
        }).start();
    }

    private void scanAndRefreshMedia() {
        Toast.makeText(this, "กำลังตรวจสอบภาพและวิดีโอที่ย้ายเข้ามา...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            File root = FolderStore.legacyPictureDir("");
            collectMediaPaths(root, paths, 0);
            Set<String> discovered = new HashSet<>();
            if (root.isDirectory() && root.listFiles() != null) {
                discoverImportedFolders(root, "", 0, discovered);
                folderStore.reconcileKnownFolders(discovered);
            }
            if (paths.isEmpty()) {
                runOnUiThread(() -> { if (folderGallery) showFolderGallery(); else loadMedia(); });
                return;
            }
            MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        private final java.util.concurrent.atomic.AtomicInteger remaining =
                                new java.util.concurrent.atomic.AtomicInteger(paths.size());
                        @Override public void onScanCompleted(String path, Uri uri) {
                            if (remaining.decrementAndGet() == 0) runOnUiThread(() -> {
                                if (folderGallery) showFolderGallery(); else loadMedia();
                                Toast.makeText(ExplorerActivity.this, "ตรวจสอบสื่อแล้ว " + paths.size() + " ไฟล์", Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        }).start();
    }

    private void discoverImportedFolders(File directory, String relative, int depth, Set<String> discovered) {
        if (depth > 8) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) continue;
            String next = FolderStore.childPath(relative, child.getName());
            if (next.isEmpty() || next.equals(relative)) continue;
            discovered.add(next);
            discoverImportedFolders(child, next, depth + 1, discovered);
        }
    }

    private void collectMediaPaths(File folder, List<String> paths, int depth) {
        if (depth > 10 || paths.size() >= 2000) return;
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectMediaPaths(child, paths, depth + 1);
            else if (child.isFile()) {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".mp4")
                        || name.endsWith(".mov")) paths.add(child.getAbsolutePath());
            }
            if (paths.size() >= 2000) break;
        }
    }

    private void navigateBackInExplorer() {
        if (!folderGallery) {
            showFolderGallery();
        } else if (!browsingFolder.isEmpty()) {
            browsingFolder = FolderStore.parentPath(browsingFolder);
            showFolderGallery();
        } else finish();
    }

    @Override
    public void onBackPressed() {
        navigateBackInExplorer();
    }

    private boolean hasLegacyReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void loadMedia() {
        folderGallery = false;
        viewFolderMediaButton.setVisibility(View.GONE);
        mediaGrid.setNumColumns(3);
        mediaGrid.setAdapter(adapter);
        String folder = browsingFolder;
        boolean all = showAllFolders;
        boolean recent = recentOnly;
        int generation = ++loadGeneration;
        folderTitle.setText((all ? (recent ? "Explorer • ล่าสุด" : "Explorer • ภาพทั้งหมด") : "Explorer • " + FolderStore.displayName(folder) + " (รวมซับโฟลเดอร์)") + "  🔎");
        countText.setText("กำลังอ่านรายการ...");
        items.clear();
        unfilteredMedia.clear();
        mediaSearchText = "";
        selected.clear();
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            List<MediaItem> loaded = new ArrayList<>();
            queryCollection(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    false,
                    FolderStore.pictureRelativePath(folder),
                    FolderStore.legacyPictureDir(folder),
                    loaded, true
            );
            queryCollection(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    FolderStore.videoRelativePath(folder),
                    FolderStore.legacyVideoDir(folder),
                    loaded, all
            );
            Collections.sort(loaded, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));
            if (recent && loaded.size() > 60) loaded.subList(60, loaded.size()).clear();

            runOnUiThread(() -> {
                if (generation != loadGeneration) return;
                unfilteredMedia.clear();
                unfilteredMedia.addAll(loaded);
                applyMediaSearch();
                if (loaded.isEmpty() && !all) {
                    countText.setText("ไม่พบภาพในโฟลเดอร์นี้ • กดรีเฟรชเพื่อตรวจสอบไฟล์และสิทธิ์");
                }
            });
        }).start();
    }

    /** Query the media index broadly, then match folder paths ourselves.
     *  Exact SQL RELATIVE_PATH matching can miss imported files when path formatting differs. */
    private void queryCollection(Uri collection, boolean video, String relativePath,
                                 File legacyDir, List<MediaItem> output, boolean includeChildren) {
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        String pathColumn = modern ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, pathColumn,
                MediaStore.MediaColumns.DATA};
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int pathIndex = cursor.getColumnIndexOrThrow(pathColumn);
            int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            String root = normalizeMediaPath("DCIM/" + FolderStore.PUBLIC_FOLDER + "/");
            String wanted = normalizeMediaPath(relativePath);
            while (cursor.moveToNext()) {
                String actual = normalizeMediaPath(cursor.getString(pathIndex));
                String diskPath = dataIndex >= 0 ? normalizeMediaPath(cursor.getString(dataIndex)) : "";
                String targetDisk = normalizeMediaPath(legacyDir.getAbsolutePath());
                boolean matches;
                if (modern) matches = (includeChildren ? actual.startsWith(wanted) : actual.equals(wanted))
                        || (!diskPath.isEmpty() && (includeChildren
                        ? diskPath.startsWith(targetDisk)
                        : diskPath.equals(targetDisk)));
                else {
                    String legacy = normalizeMediaPath(legacyDir.getAbsolutePath());
                    String allRoot = normalizeMediaPath(FolderStore.legacyPictureDir("").getAbsolutePath());
                    matches = includeChildren ? actual.startsWith(legacy) : actual.equals(legacy);
                }
                if (!matches) continue;
                long id = cursor.getLong(idIndex);
                output.add(new MediaItem(id, ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameIndex), cursor.getString(mimeIndex), cursor.getLong(dateIndex), video,
                        modern ? cursor.getString(pathIndex) : cursor.getString(dataIndex)));
            }
        } catch (Exception e) {
            android.util.Log.w("DormExplorer", "MediaStore folder query failed", e);
        }
    }

    private String normalizeMediaPath(String raw) {
        if (raw == null) return "";
        String path = raw.replace((char) 92, '/').trim().toLowerCase(Locale.ROOT);
        while (path.contains("//")) path = path.replace("//", "/");
        return path.endsWith("/") ? path : path + "/";
    }

    private void showFolderTreeDialog() {
        List<String> paths = folderStore.getKnownFolders();
        List<String> labels = new ArrayList<>();
        for (String path : paths) {
            int depth = path.isEmpty() ? 0 : path.split("/").length;
            String leaf = path.isEmpty() ? "DormCamera" : path.substring(path.lastIndexOf('/') + 1);
            StringBuilder label = new StringBuilder();
            for (int i = 1; i < depth; i++) label.append("   ");
            label.append(depth == 0 ? "📂 " : depth == 1 ? "📁 " : "▫ 📁 ");
            label.append(leaf);
            labels.add(label.toString());
        }
        new AlertDialog.Builder(this)
                .setTitle("เลือกโฟลเดอร์ • DormCamera")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String chosen = paths.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("📁 " + FolderStore.displayName(chosen))
                            .setItems(new String[]{"เปิดภาพและวิดีโอ", "＋ สร้างซับโฟลเดอร์ที่นี่"}, (d, action) -> {
                                browsingFolder = chosen;
                                showAllFolders = false;
                                recentOnly = false;
                                if (action == 0) { showAllFolders = false; recentOnly = false; loadMedia(); }
                                else createExplorerSubfolder();
                            })
                            .setNegativeButton("กลับ", null)
                            .show();
                })
                .setNeutralButton("+ ซับโฟลเดอร์", (dialog, which) -> createExplorerSubfolder())
                .setNegativeButton("ปิด", null)
                .show();
    }

    private void createExplorerSubfolder() {
        String parent = browsingFolder;
        if (!parent.isEmpty() && parent.split("/").length >= 8) {
            Toast.makeText(this, "โฟลเดอร์ซ้อนกันได้สูงสุด 8 ระดับ", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("เช่น สถานีที่ 1");
        new AlertDialog.Builder(this)
                .setTitle("สร้างซับโฟลเดอร์ใน " + FolderStore.displayName(parent))
                .setView(input)
                .setPositiveButton("สร้าง", (d, w) -> {
                    String leaf = FolderStore.normalize(input.getText().toString());
                    if (leaf.isEmpty()) return;
                    String path = FolderStore.childPath(parent, leaf);
                    if (folderStore.getKnownFolders().contains(path)) {
                        Toast.makeText(this, "โฟลเดอร์นี้มีอยู่แล้ว", Toast.LENGTH_LONG).show();
                        return;
                    }
                    folderStore.addFolder(path);
                    browsingFolder = path;
                    showAllFolders = false;
                    recentOnly = false;
                    showFolderGallery();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showFolderGallery() {
        folderGallery = true;
        compactSearchBar.setVisibility(View.GONE);
        viewFolderMediaButton.setVisibility(View.VISIBLE);
        viewFolderMediaButton.setText(browsingFolder.isEmpty() ? "ดูภาพในโฟลเดอร์หลัก" : "🖼 ดูภาพและวิดีโอในโฟลเดอร์นี้");
        int galleryGeneration = ++loadGeneration;
        selected.clear();
        directFolderMedia.clear();
        visibleFolders.clear();
        for (String path : folderStore.getKnownFolders()) {
            if (path.isEmpty()) continue;
            if (FolderStore.parentPath(path).equals(browsingFolder)) visibleFolders.add(path);
        }
        Collections.sort(visibleFolders, (a, b) -> gallerySortMode == 1
                ? b.compareToIgnoreCase(a) : a.compareToIgnoreCase(b));
        getSharedPreferences(EXPLORER_PREFS, MODE_PRIVATE).edit()
                .putString("last_browsed_folder", browsingFolder).apply();
        folderTitle.setText("Explorer • " + (browsingFolder.isEmpty() ? "DormCamera" : FolderStore.displayName(browsingFolder)) + "  ▾");
        countText.setText(visibleFolders.size() + " โฟลเดอร์ • แตะชื่อด้านบนเพื่อจัดเรียง");
        mediaGrid.setNumColumns(2);
        mediaGrid.setAdapter(folderAdapter);
        folderAdapter.notifyDataSetChanged();
        final String folder = browsingFolder;
        new Thread(() -> {
            List<MediaItem> direct = new ArrayList<>();
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false,
                    FolderStore.pictureRelativePath(folder), FolderStore.legacyPictureDir(folder), direct, false);
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true,
                    FolderStore.videoRelativePath(folder), FolderStore.legacyVideoDir(folder), direct, false);
            sortGalleryMedia(direct);
            runOnUiThread(() -> {
                if (!folderGallery || galleryGeneration != loadGeneration) return;
                directFolderMedia.clear();
                directFolderMedia.addAll(direct);
                countText.setText(visibleFolders.size() + " โฟลเดอร์ • " + direct.size() + " ภาพ/วิดีโอในชั้นนี้ • แตะชื่อเพื่อจัดเรียง");
                folderAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    /** Search known folder paths, including nested folders, without changing disk contents. */
    /** Live substring search across all known parent and nested folders. */
    private void showFolderSearchDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("พิมพ์บางส่วนของชื่อโฟลเดอร์");
        panel.addView(input);
        TextView count = new TextView(this);
        panel.addView(count);
        ListView results = new ListView(this);
        panel.addView(results, new LinearLayout.LayoutParams(-1, dp(320)));
        List<String> matches = new ArrayList<>();
        ArrayAdapter<String> resultAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, matches);
        results.setAdapter(resultAdapter);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ค้นหาโฟลเดอร์ทันที")
                .setView(panel)
                .setPositiveButton("ปิด", null)
                .setNeutralButton("จัดเรียง", (d, which) -> showGallerySortDialog())
                .create();
        Runnable refresh = () -> {
            String needle = input.getText().toString().trim().toLowerCase(Locale.ROOT);
            matches.clear();
            if (!needle.isEmpty()) for (String path : folderStore.getKnownFolders()) {
                if (!path.isEmpty() && path.toLowerCase(Locale.ROOT).contains(needle)) matches.add(path);
            }
            resultAdapter.notifyDataSetChanged();
            count.setText(needle.isEmpty() ? "พิมพ์คำเพื่อค้นหา" : "พบ " + matches.size() + " โฟลเดอร์");
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        results.setOnItemClickListener((parent, view, position, id) -> {
            browsingFolder = matches.get(position);
            showAllFolders = false;
            recentOnly = false;
            dialog.dismiss();
            showFolderGallery();
        });
        refresh.run();
        dialog.show();
    }

    /** Sorting affects presentation only; never modifies files or their metadata. */
    private void sortGalleryMedia(List<MediaItem> media) {
        if (gallerySortMode == 0) Collections.sort(media, (a, b) -> a.name.compareToIgnoreCase(b.name));
        else if (gallerySortMode == 1) Collections.sort(media, (a, b) -> b.name.compareToIgnoreCase(a.name));
        else if (gallerySortMode == 2) Collections.sort(media, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));
        else Collections.sort(media, (a, b) -> Long.compare(a.dateAdded, b.dateAdded));
    }

    private void showGallerySortDialog() {
        final String[] modes = {"ชื่อ ก–ฮ / A–Z", "ชื่อ ฮ–ก / Z–A", "วันที่ใหม่ก่อน", "วันที่เก่าก่อน"};
        new AlertDialog.Builder(this)
                .setTitle("จัดเรียง Explorer")
                .setSingleChoiceItems(modes, gallerySortMode, (dialog, choice) -> {
                    gallerySortMode = choice;
                    getSharedPreferences(EXPLORER_PREFS, MODE_PRIVATE).edit()
                            .putInt("gallery_sort_mode", choice).apply();
                    dialog.dismiss();
                    showFolderGallery();
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    private void showFolderActions(String path) {
        new AlertDialog.Builder(this)
                .setTitle("📁 " + FolderStore.displayName(path))
                .setItems(new String[]{"เปิดโฟลเดอร์", "ดูภาพในโฟลเดอร์นี้", "＋ สร้างซับโฟลเดอร์", "เปลี่ยนชื่อโฟลเดอร์", "ใช้เป็นโฟลเดอร์ถ่ายภาพ", "ลบโฟลเดอร์ว่าง"}, (d, which) -> {
                    if (which == 0) { browsingFolder = path; showFolderGallery(); }
                    else if (which == 1) { browsingFolder = path; showAllFolders = false; recentOnly = false; loadMedia(); }
                    else if (which == 2) { browsingFolder = path; createExplorerSubfolder(); }
                    else if (which == 3) renameExplorerFolder(path);
                    else if (which == 5) confirmDeleteEmptyFolder(path);
                    else {
                        if (folderStore.isLocked()) Toast.makeText(this, "กรุณาปลดล็อกโฟลเดอร์ในหน้ากล้องก่อน", Toast.LENGTH_LONG).show();
                        else { folderStore.setCurrentFolder(path); Toast.makeText(this, "เลือกโฟลเดอร์บันทึกแล้ว", Toast.LENGTH_SHORT).show(); }
                    }
                })
                .setNegativeButton("ปิด", null)
                .show();
    }

    private void confirmDeleteEmptyFolder(String path) {
        if (path.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("ลบโฟลเดอร์ว่าง")
                .setMessage("ลบเฉพาะโฟลเดอร์ที่ไม่มีภาพ วิดีโอ หรือโฟลเดอร์ย่อย\\n" + FolderStore.displayName(path) + "\\nไม่มีการลบไฟล์ภาพ")
                .setPositiveButton("ตรวจสอบและลบ", (dialog, which) -> {
                    if (folderStore.isLocked() || path.equals(folderStore.getCurrentFolder())) {
                        Toast.makeText(this, "โฟลเดอร์กำลังใช้งานหรือถูกล็อก ไม่อนุญาตให้ลบ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    for (String known : folderStore.getKnownFolders()) {
                        if (known.startsWith(path + "/")) {
                            Toast.makeText(this, "มีซับโฟลเดอร์อยู่ ไม่อนุญาตให้ลบ", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    new Thread(() -> {
                        boolean safe = true;
                        String prefix = FolderStore.pictureRelativePath(path);
                        for (Uri collection : new Uri[]{MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI}) {
                            try (Cursor cursor = getContentResolver().query(collection,
                                    new String[]{MediaStore.MediaColumns._ID},
                                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                                    new String[]{prefix + "%"}, null)) {
                                if (cursor == null || cursor.moveToFirst()) safe = false;
                            } catch (Exception e) { safe = false; }
                        }
                        File directory = FolderStore.legacyPictureDir(path);
                        File[] children = directory.listFiles();
                        if (directory.exists() && (children == null || children.length != 0)) safe = false;
                        final boolean verifiedEmpty = safe;
                        runOnUiThread(() -> {
                            if (!verifiedEmpty) {
                                Toast.makeText(this, "โฟลเดอร์มีข้อมูลหรือไม่สามารถตรวจสอบได้ จึงไม่ลบ", Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (directory.exists() && !directory.delete()) {
                                Toast.makeText(this, "ลบโฟลเดอร์ในเครื่องไม่สำเร็จ", Toast.LENGTH_LONG).show();
                                return;
                            }
                            folderStore.removeEmptyFolder(path);
                            if (browsingFolder.equals(path)) browsingFolder = FolderStore.parentPath(path);
                            showFolderGallery();
                            Toast.makeText(this, "ลบโฟลเดอร์ว่างแล้ว", Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("ยกเลิก", null).show();
    }

    /** Rename the actual directory first. Never rewrite folder preferences on a failed move. */
    private void renameExplorerFolder(String path) {
        if (path.isEmpty()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(path.substring(path.lastIndexOf('/') + 1));
        new AlertDialog.Builder(this).setTitle("เปลี่ยนชื่อโฟลเดอร์จริง").setView(input)
                .setPositiveButton("ตรวจสอบและเปลี่ยนชื่อ", (d, w) -> {
                    String leaf = FolderStore.normalize(input.getText().toString());
                    if (leaf.isEmpty()) {
                        Toast.makeText(this, "ชื่อโฟลเดอร์ไม่ถูกต้อง", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String renamed = FolderStore.childPath(FolderStore.parentPath(path), leaf);
                    if (renamed.equals(path)) return;
                    if (folderStore.isLocked() && (path.equals(folderStore.getCurrentFolder())
                            || folderStore.getCurrentFolder().startsWith(path + "/"))) {
                        Toast.makeText(this, "โฟลเดอร์บันทึกภาพถูกล็อก กรุณาปลดล็อกก่อน", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (folderStore.getKnownFolders().contains(renamed)) {
                        Toast.makeText(this, "มีชื่อโฟลเดอร์ปลายทางแล้ว", Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("ยืนยันเปลี่ยนชื่อโฟลเดอร์จริง")
                            .setMessage("จาก: " + path + "\\nเป็น: " + renamed
                                    + "\\nระบบจะเปลี่ยนชื่อโฟลเดอร์จริงพร้อมโฟลเดอร์ลูก ไม่ลบภาพ")
                            .setPositiveButton("ยืนยัน", (confirm, which) -> renameRealExplorerFolder(path, renamed))
                            .setNegativeButton("ยกเลิก", null).show();
                }).setNegativeButton("ยกเลิก", null).show();
    }

    private void renameRealExplorerFolder(String oldPath, String newPath) {
        new Thread(() -> {
            File source = FolderStore.legacyPictureDir(oldPath);
            File destination = FolderStore.legacyPictureDir(newPath);
            boolean valid = source.isDirectory() && !destination.exists()
                    && source.getParentFile() != null && source.getParentFile().isDirectory()
                    && source.getParentFile().equals(destination.getParentFile());
            // Fail closed when video is stored in a distinct physical directory.
            File oldVideo = FolderStore.legacyVideoDir(oldPath);
            File newVideo = FolderStore.legacyVideoDir(newPath);
            if (!oldVideo.equals(source) && oldVideo.exists()) valid = false;
            if (!newVideo.equals(destination) && newVideo.exists()) valid = false;
            boolean moved = valid && source.renameTo(destination);
            runOnUiThread(() -> {
                if (!moved) {
                    Toast.makeText(this, "ไม่เปลี่ยนชื่อ: ตรวจสอบโฟลเดอร์จริง/สิทธิ์/ชื่อซ้ำก่อน ข้อมูลเดิมยังอยู่", Toast.LENGTH_LONG).show();
                    return;
                }
                folderStore.renameFolder(oldPath, newPath);
                if (browsingFolder.equals(oldPath) || browsingFolder.startsWith(oldPath + "/"))
                    browsingFolder = newPath + browsingFolder.substring(oldPath.length());
                showFolderGallery();
                scanAndRefreshMedia();
                Toast.makeText(this, "เปลี่ยนชื่อโฟลเดอร์จริงแล้ว กำลังปรับดัชนีภาพ", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private final class FolderGalleryAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleFolders.size() + directFolderMedia.size(); }
        @Override public Object getItem(int position) { return position < visibleFolders.size() ? visibleFolders.get(position) : directFolderMedia.get(position - visibleFolders.size()); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (position >= visibleFolders.size()) return createDirectMediaCard(directFolderMedia.get(position - visibleFolders.size()));
            String path = visibleFolders.get(position);
            android.widget.LinearLayout card = new android.widget.LinearLayout(ExplorerActivity.this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setPadding(dp(5), dp(5), dp(5), dp(8));
            card.setBackgroundColor(0xFF202127);
            ImageView cover = new ImageView(ExplorerActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageResource(android.R.drawable.ic_menu_gallery);
            card.addView(cover, new android.widget.LinearLayout.LayoutParams(-1, dp(110)));
            TextView name = new TextView(ExplorerActivity.this);
            name.setText("📁 " + path.substring(path.lastIndexOf('/') + 1));
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(13);
            name.setMaxLines(2);
            card.addView(name);
            card.setOnClickListener(v -> {
                browsingFolder = path;
                boolean hasChildren = false;
                for (String known : folderStore.getKnownFolders()) {
                    if (FolderStore.parentPath(known).equals(path) && !known.equals(path)) { hasChildren = true; break; }
                }
                if (hasChildren) showFolderGallery();
                else { showAllFolders = false; recentOnly = false; loadMedia(); }
            });
            card.setOnLongClickListener(v -> { showFolderActions(path); return true; });
            loadFolderCover(path, cover, name);
            return card;
        }
    }

    /** Show images stored directly in this folder beside child folder cards. */
    private View createDirectMediaCard(MediaItem item) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(8));
        card.setBackgroundColor(0xFF202127);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        card.addView(image, new android.widget.LinearLayout.LayoutParams(-1, dp(110)));
        TextView label = new TextView(this);
        label.setText((item.video ? "▶ " : "🖼 ") + item.name);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setMaxLines(2);
        card.addView(label);
        card.setOnClickListener(v -> openPreview(item));
        card.setOnLongClickListener(v -> {
            showAllFolders = false;
            recentOnly = false;
            loadMedia();
            Toast.makeText(this, "เปิดรายการภาพเพื่อเลือกและจัดการไฟล์", Toast.LENGTH_SHORT).show();
            return true;
        });
        String key = item.uri.toString();
        image.setTag(key);
        thumbnailExecutor.submit(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    bitmap = getContentResolver().loadThumbnail(item.uri, new Size(320, 240), null);
            } catch (Exception ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (result != null && key.equals(image.getTag())) image.setImageBitmap(result);
            });
        });
        return card;
    }

    private void loadFolderCover(String path, ImageView target, TextView label) {
        String relative = FolderStore.pictureRelativePath(path);
        target.setTag(relative);
        thumbnailExecutor.submit(() -> {
            Uri cover = null;
            int imageCount = 0;
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{relative + "%"}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    imageCount = cursor.getCount();
                    if (cursor.moveToFirst()) cover = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                }
            } catch (Exception ignored) { }
            Bitmap bitmap = null;
            if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) try { bitmap = getContentResolver().loadThumbnail(cover, new Size(320, 240), null); } catch (Exception ignored) { }
            Bitmap result = bitmap;
            final int count = imageCount;
            runOnUiThread(() -> {
                if (!relative.equals(target.getTag())) return;
                if (result != null) target.setImageBitmap(result);
                label.setText("📁 " + path.substring(path.lastIndexOf('/') + 1) + " • " + count + " ภาพ");
            });
        });
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
                "แสดง %d รายการ • เลือก %d รายการ%s",
                items.size(), selected.size(),
                mediaSearchText.isEmpty() ? "" : " • ค้นหา: " + mediaSearchText
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
        intent.putExtra(PreviewActivity.EXTRA_FOLDER, browsingFolder);
        intent.putExtra(PreviewActivity.EXTRA_ALL_FOLDERS, true);
        // Swipe only through the visible search results, in their displayed order.
        if (!folderGallery && !items.isEmpty()) {
            List<String> uris = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> mimes = new ArrayList<>();
            for (MediaItem visible : items) {
                uris.add(visible.uri.toString());
                names.add(visible.name);
                mimes.add(visible.mimeType);
            }
            intent.putExtra(PreviewActivity.EXTRA_SEQUENCE_TOKEN,
                    PreviewActivity.prepareSequence(uris, names, mimes));
        }
        startActivity(intent);
    }

    /** Search only the loaded list; never rename, move, or alter any file. */
    private void showMediaSearchDialog() {
        compactSearchBar.setVisibility(View.VISIBLE);
        compactSearchInput.setText(mediaSearchText);
        compactSearchInput.setSelection(compactSearchInput.length());
        compactSearchType.setSelection(mediaTypeFilter);
    }

    private void applyMediaSearch() {
        items.clear();
        String needle = mediaSearchText.toLowerCase(Locale.ROOT);
        for (MediaItem media : unfilteredMedia) {
            if ((mediaTypeFilter == 0 || (mediaTypeFilter == 1 && !media.video)
                    || (mediaTypeFilter == 2 && media.video))
                    && (needle.isEmpty() || media.name.toLowerCase(Locale.ROOT).contains(needle)
                    || media.folderPath.toLowerCase(Locale.ROOT).contains(needle))) items.add(media);
        }
        // Keep only selections still visible after filtering; never act on hidden files.
        java.util.HashSet<Uri> visibleUris = new java.util.HashSet<>();
        for (MediaItem visible : items) visibleUris.add(visible.uri);
        selected.retainAll(visibleUris);
        adapter.notifyDataSetChanged();
        updateSelectionStatus();
        if (!needle.isEmpty() || mediaTypeFilter != 0) countText.setText("พบ " + items.size() + " จาก " + unfilteredMedia.size() + " รายการ" + (needle.isEmpty() ? "" : " • " + mediaSearchText));
    }

    private void showToolsMenu(View anchor) {
        if (selected.isEmpty()) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_SHARE, 0, "แชร์ " + selected.size() + " รายการ");
        popup.getMenu().add(0, MENU_COPY, 1, "คัดลอก " + selected.size() + " รายการ");
        popup.getMenu().add(0, MENU_MOVE, 2, "ย้าย " + selected.size() + " รายการ");
        popup.getMenu().add(0, MENU_RENAME, 3, "เปลี่ยนชื่อ");
        popup.getMenu().add(0, MENU_DELETE, 4, "ลบ " + selected.size() + " รายการ");
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

        // Show the same folder hierarchy used by the folder picker.
        // Search the full path, but transfer only to the selected original path.
        List<String> visiblePaths = new ArrayList<>(targets);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("🔎 ค้นหาโฟลเดอร์ปลายทาง");
        content.addView(search);

        TextView count = new TextView(this);
        count.setPadding(0, 8, 0, 8);
        content.addView(count);

        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(330 * getResources().getDisplayMetrics().density)));

        Button createFolder = new Button(this);
        createFolder.setText("＋ สร้างโฟลเดอร์ใหม่");
        content.addView(createFolder);
        Button createChild = new Button(this);
        createChild.setText("＋ สร้างซับโฟลเดอร์ในโฟลเดอร์ปัจจุบัน");
        content.addView(createChild);

        final Runnable refresh = () -> {
            visiblePaths.clear();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<String> labels = new ArrayList<>();
            for (String folder : targets) {
                String full = FolderStore.displayName(folder);
                if (!query.isEmpty() && !full.toLowerCase(Locale.ROOT).contains(query)
                        && !folder.toLowerCase(Locale.ROOT).contains(query)) continue;
                visiblePaths.add(folder);
                int depth = folder.isEmpty() ? 0 : folder.split("/").length - 1;
                StringBuilder label = new StringBuilder();
                for (int i = 0; i < Math.min(depth, 5); i++) label.append("  ");
                label.append(depth == 0 ? "📁 " : "📂 ");
                label.append(full);
                labels.add(label.toString());
            }
            count.setText("พบ " + visiblePaths.size() + " โฟลเดอร์");
            list.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, labels));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refresh.run();
            }
            @Override public void afterTextChanged(Editable text) { }
        });

        String title = move ? "ย้ายไปโฟลเดอร์" : "คัดลอกไปโฟลเดอร์";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("ยกเลิก", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visiblePaths.size()) return;
            String destination = visiblePaths.get(position);
            dialog.dismiss();
            confirmTransfer(move, destination);
        });
        createFolder.setOnClickListener(v -> {
            dialog.dismiss();
            promptNewDestination(move, false);
        });
        createChild.setOnClickListener(v -> {
            dialog.dismiss();
            promptNewDestination(move, true);
        });
        refresh.run();
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        });
        dialog.show();
    }

    private void promptNewDestination(boolean move, boolean nested) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("เช่น 20");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(nested ? "สร้างซับโฟลเดอร์ปลายทาง" : "สร้างโฟลเดอร์ปลายทาง")
                .setView(input)
                .setPositiveButton("สร้าง", (d, which) -> {
                    String leaf = FolderStore.normalize(input.getText().toString());
                    String folder = nested
                            ? FolderStore.childPath(folderStore.getCurrentFolder(), leaf)
                            : leaf;
                    if (leaf.isEmpty()) {
                        Toast.makeText(this, "กรุณาใส่ชื่อโฟลเดอร์", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (folder.equals(folderStore.getCurrentFolder())) {
                        Toast.makeText(this, "โฟลเดอร์นี้เป็นโฟลเดอร์ต้นทางอยู่แล้ว", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (folderStore.getKnownFolders().contains(folder)) {
                        Toast.makeText(this, "มีโฟลเดอร์นี้แล้ว", Toast.LENGTH_LONG).show();
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
                .setTitle("ยืนยันการลบไฟล์")
                .setMessage("กำลังจะลบ " + count + " รายการที่เลือกอย่างถาวร\\nโปรดตรวจสอบรายการที่เลือกก่อนดำเนินการ")
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
        if (adapter != null && hasLegacyReadPermission()) {
            if (folderGallery) showFolderGallery(); else loadMedia();
        }
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
            if (hasLegacyReadPermission()) { showFolderGallery(); scanAndRefreshMedia(); }
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
            TextView playBadge;
            TextView folderBadge;

            if (convertView instanceof FrameLayout) {
                root = (FrameLayout) convertView;
                image = (ImageView) root.findViewWithTag("image");
                check = (CheckBox) root.findViewWithTag("check");
                badge = (TextView) root.findViewWithTag("badge");
                playBadge = (TextView) root.findViewWithTag("playBadge");
                folderBadge = (TextView) root.findViewWithTag("folderBadge");
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

                playBadge = new TextView(ExplorerActivity.this);
                playBadge.setTag("playBadge");
                playBadge.setText("▶");
                playBadge.setTextColor(0xFFFFFFFF);
                playBadge.setTextSize(19f);
                playBadge.setGravity(Gravity.CENTER);
                playBadge.setFocusable(false);
                playBadge.setContentDescription("วิดีโอ");
                playBadge.setBackgroundResource(R.drawable.bg_video_play_badge);
                playBadge.setElevation(dp(4));
                FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                        dp(44),
                        dp(44),
                        Gravity.CENTER
                );
                root.addView(playBadge, playParams);

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
                folderBadge = new TextView(ExplorerActivity.this);
                folderBadge.setTag("folderBadge");
                folderBadge.setTextColor(0xFFEEEEEE);
                folderBadge.setTextSize(10f);
                folderBadge.setSingleLine(true);
                folderBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
                folderBadge.setBackgroundColor(0xAA222222);
                folderBadge.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams folderParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP);
                root.addView(folderBadge, folderParams);
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

            badge.setText(item.name);
            String folderLabel = item.folderPath.replace((char) 92, '/');
            while (folderLabel.endsWith("/")) folderLabel = folderLabel.substring(0, folderLabel.length() - 1);
            // Show the useful folder hierarchy, not just the final leaf name.
            String lowerPath = folderLabel.toLowerCase(Locale.ROOT);
            String publicRoot = FolderStore.PUBLIC_FOLDER.toLowerCase(Locale.ROOT);
            int rootPos = lowerPath.indexOf("/" + publicRoot + "/");
            if (rootPos >= 0) folderLabel = folderLabel.substring(rootPos + publicRoot.length() + 2);
            else if (lowerPath.endsWith("/" + publicRoot)) folderLabel = "";
            if (folderLabel.isEmpty()) folderLabel = "DormCamera";
            folderBadge.setText("📁 " + folderLabel);
            folderBadge.setContentDescription("โฟลเดอร์ " + folderLabel);
            folderBadge.setVisibility(View.VISIBLE);
            playBadge.setVisibility(item.video ? View.VISIBLE : View.GONE);
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
        final String folderPath;

        MediaItem(long id, Uri uri, String name, String mimeType, long dateAdded, boolean video, String folderPath) {
            this.id = id;
            this.uri = uri;
            this.name = name != null ? name : "ไม่ทราบชื่อ";
            this.mimeType = mimeType != null ? mimeType : (video ? "video/*" : "image/*");
            this.dateAdded = dateAdded;
            this.video = video;
            this.folderPath = folderPath != null ? folderPath : "";
        }
    }
}
