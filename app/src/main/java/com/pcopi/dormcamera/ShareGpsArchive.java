package com.pcopi.dormcamera;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Safely sends a GPS photo as original bytes inside a ZIP and imports it again. */
public final class ShareGpsArchive {
    private static final long MAX_IMAGE_BYTES = 160L * 1024 * 1024;
    private ShareGpsArchive() {}

    @NonNull
    public static Uri createGpsZip(@NonNull Context context,
            @NonNull Uri source, @NonNull String displayName,
            double expectedLat, double expectedLon) throws IOException {
        if (!Double.isFinite(expectedLat) || !Double.isFinite(expectedLon)
                || expectedLat < -90 || expectedLat > 90
                || expectedLon < -180 || expectedLon > 180) {
            throw new IOException("พิกัด GPS ของรายการนี้ไม่ถูกต้อง");
        }
        File dir = cacheFolder(context, "share_gps_zip");
        File temporary = File.createTempFile("gps_source_", ".img", dir);
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("อ่านภาพต้นฉบับไม่ได้");
                copyLimited(in, out, MAX_IMAGE_BYTES);
            }
            if (temporary.length() < 32) throw new IOException("ไฟล์ภาพต้นฉบับว่าง");
            ExifInterface metadata = new ExifInterface(temporary.getAbsolutePath());
            double[] coord = metadata.getLatLong();
            if (coord == null || coord.length < 2) {
                // MediaStore can redact GPS when exposing a stream even when
                // Dorm Camera previously verified this image's coordinates.
                // Restore only the same known GPS in the temporary export.
                metadata.setLatLong(expectedLat, expectedLon);
                metadata.saveAttributes();
                coord = new ExifInterface(temporary.getAbsolutePath()).getLatLong();
            }
            if (coord == null || coord.length < 2
                    || !Double.isFinite(coord[0]) || !Double.isFinite(coord[1])
                    || Math.abs(coord[0] - expectedLat) > 0.0001
                    || Math.abs(coord[1] - expectedLon) > 0.0001) {
                throw new IOException("ตรวจ GPS ของไฟล์ที่จะส่งไม่ผ่าน กรุณาเลือกภาพอีกครั้ง");
            }

            String imageName = safeFilename(displayName, ".jpg");
            String base = imageName.substring(0, imageName.lastIndexOf('.'));
            File archive = new File(dir, base + "_GPS_" + System.nanoTime() + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive));
                 InputStream image = new FileInputStream(temporary)) {
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                zip.putNextEntry(new ZipEntry(imageName));
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = image.read(buffer)) != -1) {
                    if (n > 0) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
            if (archive.length() <= 32) {
                archive.delete();
                throw new IOException("สร้าง ZIP สำหรับแชร์ไม่สำเร็จ");
            }
            return providerUri(context, archive);
        } finally {
            temporary.delete();
        }
    }

    /**
     * Extract the GPS-bearing image first. Bundles include NoGPS preview before GPS image;
     * old behavior accidentally returned the NoGPS preview and could not navigate.
     */
    @NonNull
    public static Uri extractFirstImage(@NonNull Context context, @NonNull Uri archiveUri)
            throws IOException {
        File dir = cacheFolder(context, "received_gps_zip");
        File fallback = null;
        try (InputStream in = context.getContentResolver().openInputStream(archiveUri)) {
            if (in == null) throw new IOException("LINE ไม่อนุญาตให้เปิดไฟล์ ZIP นี้");
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                int scanned = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++scanned > 50) {
                        throw new IOException("ZIP มีรายการมากเกินกว่าที่รองรับ");
                    }
                    if (entry.isDirectory() || !isSupportedImage(entry.getName())) {
                        zip.closeEntry();
                        continue;
                    }
                    String filename = safeFilename(entry.getName(), ".jpg");
                    int dot = filename.lastIndexOf('.');
                    String extension = dot >= 0 ? filename.substring(dot) : ".jpg";
                    File outFile = File.createTempFile("received_gps_", extension, dir);
                    try {
                        try (OutputStream out = new FileOutputStream(outFile)) {
                            copyLimited(zip, out, MAX_IMAGE_BYTES);
                        }
                        if (outFile.length() < 32) {
                            throw new IOException("ไฟล์ภาพภายใน ZIP ไม่สมบูรณ์");
                        }
                        boolean hasGps = false;
                        try {
                            double[] ll = new ExifInterface(outFile.getAbsolutePath()).getLatLong();
                            hasGps = ll != null && ll.length >= 2;
                        } catch (IOException ignored) {}
                        if (hasGps) {
                            if (fallback != null) fallback.delete();
                            return providerUri(context, outFile);
                        }
                        if (fallback == null) fallback = outFile;
                        else outFile.delete();
                    } catch (IOException e) {
                        outFile.delete();
                        throw e;
                    }
                    zip.closeEntry();
                }
            }
            if (fallback != null) {
                return providerUri(context, fallback);
            }
        } catch (IOException | RuntimeException ex) {
            if (fallback != null) fallback.delete();
            throw ex;
        }
        throw new IOException("ZIP ไม่มีไฟล์ภาพ JPG/PNG/HEIC/WEBP ที่รองรับ");
    }

    private static boolean isSupportedImage(String value) {
        if (value == null) return false;
        String name = value.toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".heic")
                || name.endsWith(".heif") || name.endsWith(".webp");
    }

    private static String safeFilename(String raw, String fallbackExt) {
        String name = raw == null ? "DormCamera" + fallbackExt : raw;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^\\p{L}\\p{M}\\p{N}._ -]", "_").trim();
        if (name.isEmpty()) name = "DormCamera" + fallbackExt;
        if (name.length() > 105) {
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot) : fallbackExt;
            name = name.substring(0, Math.min(88, name.length())) + ext;
        }
        if (!isSupportedImage(name)) name = name + fallbackExt;
        return name;
    }

    private static File cacheFolder(Context context, String name) throws IOException {
        File dir = new File(context.getCacheDir(), name);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("สร้างพื้นที่แชร์ไม่ได้");
        File[] files = dir.listFiles();
        if (files != null) {
            long cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff) file.delete();
            }
        }
        return dir;
    }

    private static Uri providerUri(Context context, File file) {
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", file);
    }

    private static void copyLimited(InputStream source, OutputStream dest, long maximum)
            throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0L;
        int n;
        while ((n = source.read(buffer)) != -1) {
            if (n > 0) {
                total += n;
                if (total > maximum) throw new IOException("ภาพเกิน 160 MB");
                dest.write(buffer, 0, n);
            }
        }
        dest.flush();
    }
}
