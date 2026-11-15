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
        return normalizePath(prefs.getString(KEY_CURRENT, ROOT_FOLDER));
    }

    public boolean isLocked() {
        return prefs.getBoolean(KEY_LOCKED, false);
    }

    public void setLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply();
    }

    public boolean setCurrentFolder(@NonNull String folder) {
        String normalized = normalizePath(folder);
        if (isLocked() && !normalized.equals(getCurrentFolder())) return false;
        remember(normalized);
        prefs.edit().putString(KEY_CURRENT, normalized).apply();
        return true;
    }

    public void addFolder(@NonNull String folder) {
        remember(normalizePath(folder));
    }

    public void renameFolder(@NonNull String oldName, @NonNull String newName) {
        String oldFolder = normalizePath(oldName);
        String newFolder = normalizePath(newName);
        Set<String> folders = new HashSet<>(prefs.getStringSet(KEY_KNOWN, Collections.emptySet()));
        Set<String> renamed = new HashSet<>();
        for (String path : folders) {
            String safe = normalizePath(path);
            if (safe.equals(oldFolder) || safe.startsWith(oldFolder + "/")) {
                renamed.add(newFolder + safe.substring(oldFolder.length()));
            } else {
                renamed.add(safe);
            }
        }
        folders = renamed;

        SharedPreferences.Editor editor = prefs.edit().putStringSet(KEY_KNOWN, folders);
        String current = getCurrentFolder();
        if (current.equals(oldFolder) || current.startsWith(oldFolder + "/")) {
            editor.putString(KEY_CURRENT, newFolder + current.substring(oldFolder.length()));
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
            String normalized = normalizePath(folder);
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

    /** Safe hierarchical path under DormCamera; never accept traversal or absolute paths. */
    @NonNull
    public static String normalizePath(@NonNull String raw) {
        String[] segments = raw.replace('\\\\', '/').split("/");
        List<String> safe = new ArrayList<>();
        for (String segment : segments) {
            String part = normalize(segment);
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            safe.add(part);
            if (safe.size() == 8) break;
        }
        return android.text.TextUtils.join("/", safe);
    }

    @NonNull
    public static String childPath(@NonNull String parent, @NonNull String child) {
        String safeParent = normalizePath(parent);
        String safeChild = normalize(child);
        if (safeChild.isEmpty()) return safeParent;
        return normalizePath(safeParent.isEmpty() ? safeChild : safeParent + "/" + safeChild);
    }

    @NonNull
    public static String parentPath(@NonNull String path) {
        String safe = normalizePath(path);
        int separator = safe.lastIndexOf('/');
        return separator < 0 ? ROOT_FOLDER : safe.substring(0, separator);
    }

    @NonNull
    public static String displayName(@NonNull String folder) {
        return folder.isEmpty() ? "หลัก" : folder.replace("/", " / ");
    }

    @NonNull
    public static String pictureRelativePath(@NonNull String folder) {
        return buildRelativePath(Environment.DIRECTORY_DCIM, folder);
    }

    @NonNull
    public static String videoRelativePath(@NonNull String folder) {
        return buildRelativePath(Environment.DIRECTORY_DCIM, folder);
    }

    @NonNull
    private static String buildRelativePath(@NonNull String base, @NonNull String folder) {
        StringBuilder path = new StringBuilder(base)
                .append(File.separator)
                .append(PUBLIC_FOLDER);
        if (!folder.isEmpty()) {
            path.append(File.separator).append(normalizePath(folder));
        }
        path.append(File.separator);
        return path.toString();
    }

    @NonNull
    public static File legacyPictureDir(@NonNull String folder) {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                PUBLIC_FOLDER
        );
        return folder.isEmpty() ? root : new File(root, normalizePath(folder));
    }

    @NonNull
    public static File legacyVideoDir(@NonNull String folder) {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                PUBLIC_FOLDER
        );
        return folder.isEmpty() ? root : new File(root, folder);
    }

    private void remember(@NonNull String folder) {
        if (folder.isEmpty()) return;
        Set<String> folders = new HashSet<>(prefs.getStringSet(KEY_KNOWN, Collections.emptySet()));
        String safe = normalizePath(folder);
        String[] parts = safe.split("/");
        String path = "";
        for (String part : parts) {
            if (part.isEmpty()) continue;
            path = path.isEmpty() ? part : path + "/" + part;
            folders.add(path);
        }
        prefs.edit().putStringSet(KEY_KNOWN, folders).apply();
    }
}
