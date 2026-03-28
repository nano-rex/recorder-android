package com.convoy.androidrecorder;

import android.content.Intent;
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
        TextView tvRecordingSource = findViewById(R.id.tvRecordingSource);
        Button btnFolderSetup = findViewById(R.id.btnFolderSetup);
        Button btnRecordingSource = findViewById(R.id.btnRecordingSource);
        Button btnTabHome = findViewById(R.id.btnTabHome);
        Button btnTabFolder = findViewById(R.id.btnTabFolder);
        Button btnTabSettings = findViewById(R.id.btnTabSettings);

        switchDarkMode.setChecked(AppSettings.isDarkModeEnabled(this));
        refreshFolderPath(tvFolderPath);
        refreshRecordingSource(tvRecordingSource);

        switchDarkMode.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            AppSettings.setDarkModeEnabled(this, isChecked);
            AppSettings.applyNightMode(this);
            recreate();
        });
        btnFolderSetup.setOnClickListener(v -> showFolderModeDialog(tvFolderPath));
        btnRecordingSource.setOnClickListener(v -> showRecordingSourceDialog(tvRecordingSource));
        btnTabHome.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
        btnTabFolder.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class)));
        btnTabSettings.setEnabled(false);
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

    private void showRecordingSourceDialog(TextView tvRecordingSource) {
        String[] labels = new String[]{
                "Voice recognition",
                "Microphone"
        };
        String[] values = new String[]{
                AppSettings.SOURCE_VOICE,
                AppSettings.SOURCE_MICROPHONE
        };
        new AlertDialog.Builder(this)
                .setTitle("Choose recording source")
                .setItems(labels, (dialog, which) -> {
                    AppSettings.setRecordingSource(this, values[which]);
                    refreshRecordingSource(tvRecordingSource);
                })
                .show();
    }

    private void refreshRecordingSource(TextView tvRecordingSource) {
        String source = AppSettings.getRecordingSource(this);
        String label = AppSettings.SOURCE_MICROPHONE.equals(source) ? "Microphone" : "Voice recognition";
        tvRecordingSource.setText("Current recording source:\n" + label);
    }
}
