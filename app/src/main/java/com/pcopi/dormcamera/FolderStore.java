package com.pcopi.dormcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FolderStore {
    public static final String PUBLIC_FOLDER = "DormCamera";
    public static final String ROOT_FOLDER = "";

    private static final String PREFS = "dorm_camera_folder_prefs";
    private static final String KEY_CURRENT = "current_folder";
    private static final String KEY_LOCKED = "folder_locked";
    private static final String KEY_KNOWN = "known_folders";

    private final SharedPreferences prefs;

    public FolderStore(@NonNull Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public String getCurrentFolder() {
        return prefs.getString(KEY_CURRENT, ROOT_FOLDER);
    }

    public boolean isLocked() {
        return prefs.getBoolean(KEY_LOCKED, false);
    }

    public void setLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply();
    }

    public boolean setCurrentFolder(@NonNull String folder) {
        String normalized = normalize(folder);
        if (isLocked() && !normalized.equals(getCurrentFolder())) return false;
        remember(normalized);
        prefs.edit().putString(KEY_CURRENT, normalized).apply();
        return true;
    }

    public void addFolder(@NonNull String folder) {
        remember(normalize(folder));
    }

    public void renameFolder(@NonNull String oldName, @NonNull String newName) {
        String oldFolder = normalize(oldName);
        String newFolder = normalize(newName);
        Set<String> folders = new HashSet<>(prefs.getStringSet(KEY_KNOWN, Collections.emptySet()));
        folders.remove(oldFolder);
        if (!newFolder.isEmpty()) folders.add(newFolder);

        SharedPreferences.Editor editor = prefs.edit().putStringSet(KEY_KNOWN, folders);
        if (getCurrentFolder().equals(oldFolder)) {
            editor.putString(KEY_CURRENT, newFolder);
        }
        editor.apply();
    }

    @NonNull
    public List<String> getKnownFolders() {
        Set<String> stored = new HashSet<>(prefs.getStringSet(KEY_KNOWN, Collections.emptySet()));
        List<String> result = new ArrayList<>();
        result.add(ROOT_FOLDER);
        List<String> named = new ArrayList<>();
        for (String folder : stored) {
            String normalized = normalize(folder);
            if (!normalized.isEmpty()) named.add(normalized);
        }
        Collections.sort(named, String.CASE_INSENSITIVE_ORDER);
        result.addAll(named);
        return result;
    }

    @NonNull
    public static String normalize(@NonNull String raw) {
        String value = raw.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        value = value.replaceAll("\\s+", " ");
        while (value.startsWith(".")) value = value.substring(1).trim();
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1).trim();
        if (value.length() > 48) value = value.substring(0, 48).trim();
        return value;
    }

    @NonNull
    public static String displayName(@NonNull String folder) {
        return folder.isEmpty() ? "หลัก" : folder;
    }

    @NonNull
    public static String pictureRelativePath(@NonNull String folder) {
        return buildRelativePath(Environment.DIRECTORY_PICTURES, folder);
    }

    @NonNull
    public static String videoRelativePath(@NonNull String folder) {
        return buildRelativePath(Environment.DIRECTORY_MOVIES, folder);
    }

    @NonNull
    private static String buildRelativePath(@NonNull String base, @NonNull String folder) {
        StringBuilder path = new StringBuilder(base)
                .append(File.separator)
                .append(PUBLIC_FOLDER);
        if (!folder.isEmpty()) {
            path.append(File.separator).append(folder);
        }
        path.append(File.separator);
        return path.toString();
    }

    @NonNull
    public static File legacyPictureDir(@NonNull String folder) {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                PUBLIC_FOLDER
        );
        return folder.isEmpty() ? root : new File(root, folder);
    }

    @NonNull
    public static File legacyVideoDir(@NonNull String folder) {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                PUBLIC_FOLDER
        );
        return folder.isEmpty() ? root : new File(root, folder);
    }

    private void remember(@NonNull String folder) {
        if (folder.isEmpty()) return;
        Set<String> folders = new HashSet<>(prefs.getStringSet(KEY_KNOWN, Collections.emptySet()));
        folders.add(folder);
        prefs.edit().putStringSet(KEY_KNOWN, folders).apply();
    }
}
