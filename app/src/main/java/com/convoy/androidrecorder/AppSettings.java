package com.convoy.androidrecorder;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

public final class AppSettings {
    private static final String PREFS = "recorder_settings";
    private static final String KEY_STORAGE_MODE = "storage_mode";
    private static final String KEY_DARK_MODE = "dark_mode";

    public static final String STORAGE_DOCUMENTS = "documents";
    public static final String STORAGE_DOWNLOADS = "downloads";
    public static final String STORAGE_INTERNAL = "internal";

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getStorageMode(Context context) {
        return prefs(context).getString(KEY_STORAGE_MODE, null);
    }

    public static void setStorageMode(Context context, String storageMode) {
        prefs(context).edit().putString(KEY_STORAGE_MODE, storageMode).apply();
    }

    public static boolean isDarkModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DARK_MODE, false);
    }

    public static void setDarkModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(
                isDarkModeEnabled(context) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
    }
}
