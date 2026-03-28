package com.convoy.androidrecorder;

import android.Manifest;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidrecorder.util.RecorderUtil;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private TextView tvHardwarePanel;
    private TextView tvLatestFile;
    private Button btnRecord;
    private RecorderUtil.RecorderSession recorderSession;
    private boolean isRecording = false;
    private int defaultStatusColor;
    private String statusMessage = "Status: idle";
    private boolean statusWarning = false;

    private final ActivityResultLauncher<String> requestRecordPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startRecording();
                } else {
                    setStatusMessage("Status: microphone permission denied", true);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvHardwarePanel = findViewById(R.id.tvHardwarePanel);
        tvLatestFile = findViewById(R.id.tvLatestFile);
        btnRecord = findViewById(R.id.btnRecord);
        Button btnSettings = findViewById(R.id.btnSettings);
        defaultStatusColor = tvHardwarePanel.getCurrentTextColor();

        refreshLatestFile();
        refreshPanel();

        btnRecord.setOnClickListener(v -> {
            if (!StorageUtils.isWorkspaceConfigured(this)) {
                setStatusMessage("Status: set the workspace folder in Settings first", true);
                return;
            }
            if (isRecording) {
                stopRecording();
            } else {
                requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private File recordingsDir() {
        return StorageUtils.recordingsDir(this);
    }

    private void startRecording() {
        try {
            File out = new File(recordingsDir(), "recording_" + System.currentTimeMillis() + ".wav");
            recorderSession = RecorderUtil.startRecording(out, AppSettings.getRecordingSource(this));
            isRecording = true;
            btnRecord.setText("Stop recording");
            setStatusMessage("Status: recording with " + recorderSession.getSourceLabel() + "...", false);
            tvLatestFile.setText("Latest file: recording in progress");
        } catch (Exception e) {
            setStatusMessage("Status: failed to start recording - " + e.getMessage(), true);
        }
    }

    private void stopRecording() {
        if (recorderSession == null) return;
        new Thread(() -> {
            try {
                RecorderUtil.SavedRecording recorded = recorderSession.stopAndSave();
                runOnUiThread(() -> {
                    isRecording = false;
                    recorderSession = null;
                    btnRecord.setText("Start recording");
                    setStatusMessage("Status: recording saved via " + recorded.getSourceLabel(), false);
                    tvLatestFile.setText("Latest file: " + recorded.getRecordedFile().getAbsolutePath());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isRecording = false;
                    recorderSession = null;
                    btnRecord.setText("Start recording");
                    setStatusMessage("Status: failed to stop recording - " + e.getMessage(), true);
                });
            }
        }).start();
    }

    private void refreshLatestFile() {
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            tvLatestFile.setText("Latest file: set workspace folder in Settings");
            return;
        }
        File[] files = recordingsDir().listFiles((dir, name) -> name.toLowerCase().endsWith(".wav"));
        if (files == null || files.length == 0) {
            tvLatestFile.setText("Latest file: none");
            return;
        }
        File latest = files[0];
        for (File file : files) {
            if (file.lastModified() > latest.lastModified()) latest = file;
        }
        tvLatestFile.setText("Latest file: " + latest.getAbsolutePath());
    }

    private void setStatusMessage(String message, boolean warning) {
        statusMessage = message;
        statusWarning = warning;
        refreshPanel();
    }

    private void refreshPanel() {
        String folder = StorageUtils.describeBaseDir(this);
        String content = "Recorder\nWorkspace: " + folder + "\n" + statusMessage;
        tvHardwarePanel.setText(content);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(18f);
        box.setStroke(2, statusWarning ? Color.RED : Color.DKGRAY);
        box.setColor(statusWarning ? Color.parseColor("#FFF0F0") : Color.parseColor("#F2F2F2"));
        tvHardwarePanel.setBackground(box);
        tvHardwarePanel.setTextColor(statusWarning ? Color.RED : defaultStatusColor);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording && recorderSession != null) {
            try {
                recorderSession.stopAndSave();
            } catch (Exception ignored) {
            }
        }
    }
}
