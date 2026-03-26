package com.convoy.androidrecorder;

import android.Manifest;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidrecorder.util.RecorderUtil;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus;
    private TextView tvLatestFile;
    private TextView tvOutputDir;
    private Button btnRecord;
    private RecorderUtil.RecorderSession recorderSession;
    private boolean isRecording = false;
    private int defaultStatusColor;

    private final ActivityResultLauncher<String> requestRecordPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startRecording();
                } else {
                    setStatusWarning();
                    tvStatus.setText("Status: microphone permission denied");
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvLatestFile = findViewById(R.id.tvLatestFile);
        tvOutputDir = findViewById(R.id.tvOutputDir);
        btnRecord = findViewById(R.id.btnRecord);
        defaultStatusColor = tvStatus.getCurrentTextColor();

        File outputDir = recordingsDir();
        tvOutputDir.setText("Output folder: " + outputDir.getAbsolutePath());
        refreshLatestFile();

        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
    }

    private File recordingsDir() {
        File root = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (root == null) root = new File(getFilesDir(), "music");
        File dir = new File(root, "TranscriberRecorder");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void startRecording() {
        try {
            File out = new File(recordingsDir(), "recording_" + System.currentTimeMillis() + ".wav");
            recorderSession = RecorderUtil.startRecording(out);
            isRecording = true;
            setStatusNormal();
            btnRecord.setText("Stop recording");
            tvStatus.setText("Status: recording with " + recorderSession.getSourceLabel() + "...");
            tvLatestFile.setText("Latest file: recording in progress");
        } catch (Exception e) {
            setStatusWarning();
            tvStatus.setText("Status: failed to start recording - " + e.getMessage());
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
                    setStatusNormal();
                    tvStatus.setText("Status: recording saved via " + recorded.getSourceLabel());
                    tvLatestFile.setText("Latest file: " + recorded.getRecordedFile().getAbsolutePath());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isRecording = false;
                    recorderSession = null;
                    btnRecord.setText("Start recording");
                    setStatusWarning();
                    tvStatus.setText("Status: failed to stop recording - " + e.getMessage());
                });
            }
        }).start();
    }

    private void refreshLatestFile() {
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

    private void setStatusWarning() {
        tvStatus.setTextColor(Color.RED);
    }

    private void setStatusNormal() {
        tvStatus.setTextColor(defaultStatusColor);
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
