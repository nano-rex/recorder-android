package com.convoy.androidrecorder;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchDarkMode = findViewById(R.id.switchDarkMode);
        TextView tvFolderPath = findViewById(R.id.tvFolderPath);
        Button btnFolderSetup = findViewById(R.id.btnFolderSetup);

        switchDarkMode.setChecked(AppSettings.isDarkModeEnabled(this));
        refreshFolderPath(tvFolderPath);

        switchDarkMode.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            AppSettings.setDarkModeEnabled(this, isChecked);
            AppSettings.applyNightMode(this);
            recreate();
        });
        btnFolderSetup.setOnClickListener(v -> showFolderModeDialog(tvFolderPath));
    }

    private void showFolderModeDialog(TextView tvFolderPath) {
        String[] labels = new String[]{
                "Documents folder",
                "Downloads folder",
                "Internal app folder"
        };
        String[] values = new String[]{
                AppSettings.STORAGE_DOCUMENTS,
                AppSettings.STORAGE_DOWNLOADS,
                AppSettings.STORAGE_INTERNAL
        };
        new AlertDialog.Builder(this)
                .setTitle("Choose base folder")
                .setItems(labels, (dialog, which) -> {
                    AppSettings.setStorageMode(this, values[which]);
                    refreshFolderPath(tvFolderPath);
                })
                .show();
    }

    private void refreshFolderPath(TextView tvFolderPath) {
        tvFolderPath.setText("Current base folder:\n" + StorageUtils.describeBaseDir(this));
    }
}
