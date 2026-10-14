package com.pcopi.dormcamera;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Size;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LatestMediaView extends AppCompatImageView {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FolderStore folderStore;
    private int generation = 0;
    private MediaRef current;

    public LatestMediaView(Context context) {
        super(context);
        init(context);
    }

    public LatestMediaView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LatestMediaView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        folderStore = new FolderStore(context);
        setScaleType(ScaleType.CENTER_CROP);
        setContentDescription("สื่อล่าสุด");
        setVisibility(View.INVISIBLE);
        setOnClickListener(v -> openCurrent());
    }

    public void refresh() {
        final int request = ++generation;
        executor.submit(() -> {
            MediaRef latest = findLatest();
            Bitmap thumb = latest != null ? loadThumbnail(latest) : null;
            post(() -> {
                if (request != generation) return;
                current = latest;
                if (latest == null) {
                    setImageDrawable(null);
                    setVisibility(View.INVISIBLE);
                    return;
                }
                if (thumb != null) setImageBitmap(thumb);
                setVisibility(View.VISIBLE);
            });
        });
    }

    @Nullable
    private MediaRef findLatest() {
        String folder = folderStore.getCurrentFolder();
        MediaRef image = queryLatest(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                false,
                FolderStore.pictureRelativePath(folder),
                FolderStore.legacyPictureDir(folder)
        );
        MediaRef video = queryLatest(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                FolderStore.videoRelativePath(folder),
                FolderStore.legacyVideoDir(folder)
        );
        if (image == null) return video;
        if (video == null) return image;
        if (video.dateAdded > image.dateAdded) return video;
        if (video.dateAdded < image.dateAdded) return image;
        return video.id > image.id ? video : image;
    }

    @Nullable
    private MediaRef queryLatest(Uri collection, boolean video, String relativePath, File legacyDir) {
        String[] projection;
        String selection;
        String[] args;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_ADDED
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

        try (Cursor cursor = getContext().getContentResolver().query(
                collection,
                projection,
                selection,
                args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
            String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
            String mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
            long date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED));
            return new MediaRef(
                    id,
                    ContentUris.withAppendedId(collection, id),
                    name != null ? name : "สื่อล่าสุด",
                    mime != null ? mime : (video ? "video/*" : "image/*"),
                    date,
                    video
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private Bitmap loadThumbnail(MediaRef ref) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContext().getContentResolver().loadThumbnail(ref.uri, new Size(320, 320), null);
            }
            if (ref.video) {
                return MediaStore.Video.Thumbnails.getThumbnail(
                        getContext().getContentResolver(),
                        ref.id,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                );
            }
            return MediaStore.Images.Thumbnails.getThumbnail(
                    getContext().getContentResolver(),
                    ref.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void openCurrent() {
        MediaRef ref = current;
        if (ref == null) return;
        Intent intent = new Intent(getContext(), PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_URI, ref.uri.toString());
        intent.putExtra(PreviewActivity.EXTRA_MIME, ref.mimeType);
        intent.putExtra(PreviewActivity.EXTRA_NAME, ref.name);
        getContext().startActivity(intent);
    }

    @Override
    protected void onDetachedFromWindow() {
        generation++;
        executor.shutdownNow();
        super.onDetachedFromWindow();
    }

    private static final class MediaRef {
        final long id;
        final Uri uri;
        final String name;
        final String mimeType;
        final long dateAdded;
        final boolean video;

        MediaRef(long id, Uri uri, String name, String mimeType, long dateAdded, boolean video) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.dateAdded = dateAdded;
            this.video = video;
        }
    }
}
