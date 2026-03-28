package com.convoy.androidrecorder;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class StorageUtils {
    private static final String APP_FOLDER = "RecorderAndroid";

    private StorageUtils() {}

    public static boolean isWorkspaceConfigured(Context context) {
        return getStorageMode(context) != null;
    }

    public static String describeBaseDir(Context context) {
        File base = baseDir(context);
        return base == null ? "Not set" : base.getAbsolutePath();
    }

    public static File recordingsDir(Context context) {
        File base = baseDir(context);
        if (base == null) {
            throw new IllegalStateException("Workspace folder is not set");
        }
        File dir = new File(base, "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File baseDir(Context context) {
        String mode = getStorageMode(context);
        if (mode == null || mode.trim().isEmpty()) return null;
        File root;
        switch (mode) {
            case AppSettings.STORAGE_DOWNLOADS:
                root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (root == null) root = new File(context.getFilesDir(), "downloads");
                break;
            case AppSettings.STORAGE_INTERNAL:
                root = new File(context.getFilesDir(), "workspace");
                break;
            case AppSettings.STORAGE_DOCUMENTS:
            default:
                root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (root == null) root = new File(context.getFilesDir(), "documents");
                break;
        }
        File dir = new File(root, APP_FOLDER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String getStorageMode(Context context) {
        return AppSettings.getStorageMode(context);
    }
}
