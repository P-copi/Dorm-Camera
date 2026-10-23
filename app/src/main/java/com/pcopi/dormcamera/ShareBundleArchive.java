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
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds ONE LINE-compatible ZIP with the selected items.
 * One ACTION_SEND and one recipient selection. Never modifies source media.
 */
public final class ShareBundleArchive {
    private static final long MAX_ITEM_BYTES = 160L * 1024 * 1024;
    private ShareBundleArchive() {}

    @NonNull
    public static Uri create(@NonNull Context context, @NonNull Uri source,
            @NonNull String displayName, double latitude, double longitude,
            boolean withLocation, boolean withPreview, boolean withGps) throws IOException {
        int selected = (withLocation ? 1 : 0) + (withPreview ? 1 : 0) + (withGps ? 1 : 0);
        if (selected < 2) throw new IOException("การรวม ZIP ต้องเลือกอย่างน้อย 2 รายการ");

        File dir = new File(context.getCacheDir(), "share_bundle");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("ไม่สามารถสร้างโฟลเดอร์สำหรับไฟล์รวมได้");
        }
        String base = ShareToDownloads.cleanName(displayName);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 55) base = base.substring(0, 55);
        if (base.isEmpty()) base = "DormCamera";
        File output = new File(dir, base + "_Bundle_" + System.nanoTime() + ".zip");
        Uri previewUri = null;
        Uri gpsZipUri = null;
        try {
            // Build and verify the selected pictures BEFORE opening the Share sheet.
            if (withPreview) {
                previewUri = ShareImageSanitizer.createGpsFreeShareUri(
                        context, source, displayName);
            }
            if (withGps) {
                gpsZipUri = ShareGpsArchive.createGpsZip(
                        context, source, displayName, latitude, longitude);
            }

            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
                if (withLocation) {
                    String coordinates = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
                    String info = "Dorm Camera - " + base
                            + "\nพิกัด: " + coordinates
                            + "\nเปิดแผนที่: https://www.google.com/maps/search/?api=1&query="
                            + coordinates + "\n";
                    zip.putNextEntry(new ZipEntry("01_location_and_map.txt"));
                    zip.write(info.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                if (withPreview) {
                    zip.putNextEntry(new ZipEntry("02_photo_NoGPS.jpg"));
                    try (InputStream in = context.getContentResolver().openInputStream(previewUri)) {
                        if (in == null) throw new IOException("เปิดไฟล์ภาพ NoGPS สำหรับรวมไม่ได้");
                        copyLimited(in, zip);
                    }
                    zip.closeEntry();
                }
                if (withGps) {
                    boolean added = false;
                    try (InputStream file = context.getContentResolver().openInputStream(gpsZipUri)) {
                        if (file == null) throw new IOException("เปิด ZIP ภาพ GPS ที่ตรวจสอบแล้วไม่ได้");
                        try (ZipInputStream inner = new ZipInputStream(file)) {
                            ZipEntry image;
                            while ((image = inner.getNextEntry()) != null) {
                                if (!image.isDirectory() && isImage(image.getName())) {
                                    String ext = image.getName().substring(image.getName().lastIndexOf('.'))
                                            .toLowerCase(Locale.US);
                                    zip.putNextEntry(new ZipEntry("03_photo_GPS" + ext));
                                    copyLimited(inner, zip);
                                    zip.closeEntry();
                                    added = true;
                                    break;
                                }
                                inner.closeEntry();
                            }
                        }
                    }
                    if (!added) throw new IOException("ไม่พบภาพที่มี GPS ในไฟล์ ZIP ต้นทาง");
                }
            }

            // Verify the ZIP and EXIF of its actual contents, not just its existence.
            try (ZipFile verify = new ZipFile(output)) {
                int expected = selected;
                if (verify.size() != expected) {
                    throw new IOException("ไฟล์รวมมีจำนวนรายการไม่ตรงกับที่เลือก");
                }
                if (withLocation && verify.getEntry("01_location_and_map.txt") == null) {
                    throw new IOException("ลิงก์แผนที่หายไปจาก ZIP");
                }
                if (withPreview) {
                    ZipEntry entry = verify.getEntry("02_photo_NoGPS.jpg");
                    if (entry == null) throw new IOException("ไฟล์ภาพปกติหายไปจาก ZIP");
                    try (InputStream in = verify.getInputStream(entry)) {
                        if (new ExifInterface(in).getLatLong() != null) {
                            throw new IOException("ตรวจไม่ผ่าน: ภาพปกติยังมี GPS");
                        }
                    }
                }
                if (withGps) {
                    ZipEntry photo = null;
                    for (java.util.Enumeration<? extends ZipEntry> it = verify.entries();
                            it.hasMoreElements();) {
                        ZipEntry entry = it.nextElement();
                        if (entry.getName().startsWith("03_photo_GPS.")) {
                            photo = entry;
                            break;
                        }
                    }
                    if (photo == null) throw new IOException("ไฟล์ภาพ GPS หายไปจาก ZIP");
                    try (InputStream in = verify.getInputStream(photo)) {
                        double[] coord = new ExifInterface(in).getLatLong();
                        if (coord == null || coord.length < 2
                                || Math.abs(coord[0] - latitude) > 0.0001
                                || Math.abs(coord[1] - longitude) > 0.0001) {
                            throw new IOException("พิกัด GPS ใน ZIP รวมไม่ตรงกับต้นฉบับ");
                        }
                    }
                }
            }
            return FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", output);
        } catch (IOException | RuntimeException e) {
            output.delete();
            throw e;
        }
    }

    private static void copyLimited(InputStream in, java.io.OutputStream out)
            throws IOException {
        long total = 0;
        byte[] buffer = new byte[128 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > 0) {
                total += n;
                if (total > MAX_ITEM_BYTES) throw new IOException("ไฟล์ภาพเกิน 160 MB");
                out.write(buffer, 0, n);
            }
        }
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".heic") || lower.endsWith(".heif");
    }
}
