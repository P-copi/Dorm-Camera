package com.pcopi.dormcamera;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PreviewActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";

    private TextView title;
    private ZoomImageView imageView;
    private VideoView videoView;
    private FrameLayout mediaFrame;
    private Button playPauseButton;
    private TextView previewStatus;
    private boolean videoPrepared;

    private final List<PreviewItem> items = new ArrayList<>();
    private int currentIndex = 0;
    private String initialUriText;

    private float swipeStartX;
    private float swipeStartY;
    private boolean swipeCandidate;
    private boolean multiTouchSeen;
    private int swipeThresholdPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        title = findViewById(R.id.previewTitle);
        imageView = findViewById(R.id.previewImage);
        videoView = findViewById(R.id.previewVideo);
        mediaFrame = findViewById(R.id.previewMediaFrame);
        previewStatus = findViewById(R.id.previewStatus);
        playPauseButton = findViewById(R.id.previewPlayPauseButton);
        Button closeButton = findViewById(R.id.previewCloseButton);
        closeButton.setOnClickListener(v -> finish());

        swipeThresholdPx = Math.round(72f * getResources().getDisplayMetrics().density);

        initialUriText = getIntent().getStringExtra(EXTRA_URI);
        String mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        if (initialUriText == null) {
            Toast.makeText(this, "ไม่พบไฟล์สำหรับ Preview", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        items.add(new PreviewItem(
                Uri.parse(initialUriText),
                name != null ? name : "Preview",
                mime != null ? mime : "",
                Long.MAX_VALUE
        ));
        loadCurrentItem();
        loadFolderSequence();
    }

    private void loadFolderSequence() {
        new Thread(() -> {
            FolderStore folderStore = new FolderStore(this);
            String folder = folderStore.getCurrentFolder();
            List<PreviewItem> loaded = new ArrayList<>();

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
            if (loaded.isEmpty()) return;

            int found = -1;
            for (int i = 0; i < loaded.size(); i++) {
                if (loaded.get(i).uri.toString().equals(initialUriText)) {
                    found = i;
                    break;
                }
            }
            final int resolvedIndex = found >= 0 ? found : 0;

            runOnUiThread(() -> {
                items.clear();
                items.addAll(loaded);
                currentIndex = Math.min(resolvedIndex, items.size() - 1);
                if (!items.get(currentIndex).uri.toString().equals(initialUriText)) {
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).uri.toString().equals(initialUriText)) {
                            currentIndex = i;
                            break;
                        }
                    }
                }
                updateTitle();
            });
        }).start();
    }

    private void queryCollection(
            Uri collection,
            boolean video,
            String relativePath,
            File legacyDir,
            List<PreviewItem> output
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
                output.add(new PreviewItem(
                        uri,
                        name != null ? name : "ไม่ทราบชื่อ",
                        mime != null ? mime : (video ? "video/*" : "image/*"),
                        date
                ));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadCurrentItem() {
        if (items.isEmpty()) return;
        stopVideoPlayback();
        PreviewItem item = items.get(currentIndex);
        updateTitle();

        boolean video = item.mimeType.startsWith("video/");
        if (video) {
            imageView.setVisibility(View.GONE);
            imageView.setImageDrawable(null);
            resetVideoBounds();
            videoView.setVisibility(View.VISIBLE);
            playPauseButton.setVisibility(View.VISIBLE);
            previewStatus.setText("ปัดซ้าย/ขวาเพื่อเปลี่ยนไฟล์ • กำลังเตรียมวิดีโอ...");

            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(item.uri);

            videoView.setOnPreparedListener(mp -> {
                videoPrepared = true;
                fitVideoToFrame(mp.getVideoWidth(), mp.getVideoHeight());
                previewStatus.setText("ปัดซ้าย/ขวาเพื่อเปลี่ยนไฟล์ • วิดีโอพร้อมเล่น");
                playPauseButton.setText("หยุดชั่วคราว");
                videoView.start();
            });

            videoView.setOnCompletionListener(mp -> {
                playPauseButton.setText("เล่นอีกครั้ง");
                previewStatus.setText("เล่นจบแล้ว • ปัดซ้าย/ขวาเพื่อเปลี่ยนไฟล์");
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                videoPrepared = false;
                playPauseButton.setText("ลองเล่นอีกครั้ง");
                previewStatus.setText("เปิดวิดีโอไม่สำเร็จ • ปัดซ้าย/ขวาเพื่อดูไฟล์อื่น");
                Toast.makeText(
                        this,
                        "เปิดวิดีโอไม่สำเร็จ (" + what + "/" + extra + ")",
                        Toast.LENGTH_LONG
                ).show();
                return true;
            });

            playPauseButton.setOnClickListener(v -> {
                if (!videoPrepared) {
                    previewStatus.setText("กำลังลองเปิดวิดีโออีกครั้ง...");
                    resetVideoBounds();
                    videoView.setVideoURI(item.uri);
                    videoView.start();
                    return;
                }
                if (videoView.isPlaying()) {
                    videoView.pause();
                    playPauseButton.setText("เล่นต่อ");
                } else {
                    videoView.start();
                    playPauseButton.setText("หยุดชั่วคราว");
                }
            });
        } else {
            videoView.setVisibility(View.GONE);
            playPauseButton.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            previewStatus.setText(
                    "ปัดซ้าย/ขวาเพื่อเปลี่ยนไฟล์ • บีบนิ้วเพื่อขยาย • แตะสองครั้งเพื่อขยาย/คืนค่า"
            );
            imageView.setImageURI(item.uri);
            imageView.post(() -> {
                if (imageView.getDrawable() == null) {
                    previewStatus.setText("เปิดภาพไม่สำเร็จ • ปัดซ้าย/ขวาเพื่อดูไฟล์อื่น");
                    Toast.makeText(this, "เปิดภาพไม่สำเร็จ", Toast.LENGTH_LONG).show();
                } else {
                    imageView.resetZoom();
                }
            });
        }
    }

    private void resetVideoBounds() {
        if (videoView == null) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        params.gravity = Gravity.CENTER;
        videoView.setLayoutParams(params);
    }

    private void fitVideoToFrame(int videoWidth, int videoHeight) {
        if (videoView == null || mediaFrame == null || videoWidth <= 0 || videoHeight <= 0) return;
        mediaFrame.post(() -> {
            int frameWidth = mediaFrame.getWidth();
            int frameHeight = mediaFrame.getHeight();
            if (frameWidth <= 0 || frameHeight <= 0) return;

            float scale = Math.min(
                    frameWidth / (float) videoWidth,
                    frameHeight / (float) videoHeight
            );
            int targetWidth = Math.max(1, Math.round(videoWidth * scale));
            int targetHeight = Math.max(1, Math.round(videoHeight * scale));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(targetWidth, targetHeight);
            params.gravity = Gravity.CENTER;
            videoView.setLayoutParams(params);
            videoView.requestLayout();
        });
    }

    private void updateTitle() {
        if (items.isEmpty()) return;
        PreviewItem item = items.get(currentIndex);
        title.setText((currentIndex + 1) + "/" + items.size() + " • " + item.name);
    }

    private void showRelative(int delta) {
        if (items.size() <= 1) {
            Toast.makeText(this, "มีไฟล์เดียวในโฟลเดอร์นี้", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = currentIndex + delta;
        if (target < 0) {
            Toast.makeText(this, "ถึงรายการแรกแล้ว", Toast.LENGTH_SHORT).show();
            return;
        }
        if (target >= items.size()) {
            Toast.makeText(this, "ถึงรายการสุดท้ายแล้ว", Toast.LENGTH_SHORT).show();
            return;
        }
        currentIndex = target;
        loadCurrentItem();
    }

    private void stopVideoPlayback() {
        videoPrepared = false;
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getX();
            swipeStartY = event.getY();
            swipeCandidate = true;
            multiTouchSeen = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchSeen = true;
        }

        boolean handled = super.dispatchTouchEvent(event);

        if (action == MotionEvent.ACTION_UP && swipeCandidate && !multiTouchSeen) {
            float dx = event.getX() - swipeStartX;
            float dy = event.getY() - swipeStartY;
            swipeCandidate = false;

            boolean imageIsZoomed = imageView != null
                    && imageView.getVisibility() == View.VISIBLE
                    && imageView.isZoomed();

            if (!imageIsZoomed
                    && Math.abs(dx) >= swipeThresholdPx
                    && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                showRelative(dx < 0 ? 1 : -1);
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            swipeCandidate = false;
        }
        return handled;
    }

    @Override
    protected void onPause() {
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
            if (playPauseButton != null) playPauseButton.setText("เล่นต่อ");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopVideoPlayback();
        super.onDestroy();
    }

    private static final class PreviewItem {
        final Uri uri;
        final String name;
        final String mimeType;
        final long dateAdded;

        PreviewItem(Uri uri, String name, String mimeType, long dateAdded) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.dateAdded = dateAdded;
        }
    }
}
