package com.pcopi.dormcamera;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Makes an app-owned snapshot of a file provided by LINE or Android Documents.
 * This does not bypass LINE: an accessible source URI must already be delivered
 * by Android with temporary read permission.
 */
public final class IncomingFileCache {
    private static final long MAX_FILE_BYTES = 160L * 1024 * 1024;
    private IncomingFileCache() {}

    public static final class Result {
        @NonNull public final Uri uri;
        @NonNull public final String originalName;
        public final long bytes;
        public final boolean zip;

        Result(@NonNull Uri uri, @NonNull String originalName, long bytes, boolean zip) {
            this.uri = uri;
            this.originalName = originalName;
            this.bytes = bytes;
            this.zip = zip;
        }
    }

    @NonNull
    public static Result snapshot(@NonNull Context context, @NonNull Uri source)
            throws IOException, SecurityException {
        File dir = new File(context.getCacheDir(), "received_files");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("สร้างพื้นที่รับไฟล์ไม่ได้");
        }
        removeOld(dir);

        String name = fileName(context, source);
        String ext = safeExtension(name);
        // Opaque names: do not expose the sender's original filename in the cache path.
        File output = File.createTempFile("line_received_", ext, dir);
        long count = 0L;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(output)) {
                if (in == null) throw new IOException("LINE ยังไม่เปิดไฟล์ให้แอปอ่าน");
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("การรับไฟล์ถูกยกเลิก");
                    }
                    if (n > 0) {
                        count += n;
                        if (count > MAX_FILE_BYTES) {
                            throw new IOException("ไฟล์เกิน 160 MB");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                out.flush();
            }
            if (count < 32L) {
                throw new IOException("ไฟล์ยังว่างหรือดาวน์โหลดไม่สมบูรณ์");
            }
            boolean isZip = zipSignature(output);
            return new Result(FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", output),
                    name, count, isZip);
        } catch (IOException | RuntimeException e) {
            output.delete();
            throw e;
        }
    }

    private static boolean zipSignature(File file) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(magic) != 4) return false;
        }
        return magic[0] == 'P' && magic[1] == 'K'
                && ((magic[2] == 3 && magic[3] == 4)
                  || (magic[2] == 5 && magic[3] == 6)
                  || (magic[2] == 7 && magic[3] == 8));
    }

    @NonNull
    private static String fileName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String name = cursor.getString(i);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) { }
        String path = uri.getLastPathSegment();
        return path == null || path.trim().isEmpty() ? "LINE_file" : path;
    }

    @NonNull
    private static String safeExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".jpg")) return ".jpg";
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".heic")) return ".heic";
        if (lower.endsWith(".heif")) return ".heif";
        return ".bin";
    }

    private static void removeOld(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }
}
