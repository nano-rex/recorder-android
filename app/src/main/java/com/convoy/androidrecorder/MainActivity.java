package com.convoy.androidrecorder;

import android.Manifest;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.convoy.androidrecorder.util.AudioTrimUtil;
import com.convoy.androidrecorder.util.RecorderUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus;
    private TextView tvLatestFile;
    private TextView tvOutputDir;
    private Button btnRecord;
    private Button btnTrimFile;
    private CheckBox cbAutoTrim;
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

    private final ActivityResultLauncher<String[]> pickAudioFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handlePickedAudioFile);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvLatestFile = findViewById(R.id.tvLatestFile);
        tvOutputDir = findViewById(R.id.tvOutputDir);
        btnRecord = findViewById(R.id.btnRecord);
        btnTrimFile = findViewById(R.id.btnTrimFile);
        cbAutoTrim = findViewById(R.id.cbAutoTrim);
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
        btnTrimFile.setOnClickListener(v -> pickAudioFileLauncher.launch(new String[]{"audio/wav", "audio/x-wav", "audio/*"}));
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
            recorderSession = RecorderUtil.startRecording(out, cbAutoTrim.isChecked());
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
                    StringBuilder status = new StringBuilder("Status: recording saved via ")
                            .append(recorded.getSourceLabel());
                    if (recorded.getTrimmedFile() != null) {
                        status.append(" + trimmed quiet sections");
                    }
                    tvStatus.setText(status.toString());
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

    private void handlePickedAudioFile(Uri uri) {
        if (uri == null) return;
        setStatusNormal();
        tvStatus.setText("Status: preparing selected audio file...");
        new Thread(() -> {
            try {
                File imported = copyUriToLocalWav(uri);
                File trimmed = new File(recordingsDir(), baseName(imported.getName()) + "_trimmed.wav");
                AudioTrimUtil.trimQuietSections(imported, trimmed);
                runOnUiThread(() -> {
                    setStatusNormal();
                    tvStatus.setText("Status: quiet sections trimmed successfully");
                    tvLatestFile.setText("Latest file: " + trimmed.getAbsolutePath());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatusWarning();
                    tvStatus.setText("Status: trim failed - " + e.getMessage());
                });
            }
        }).start();
    }

    private File copyUriToLocalWav(Uri uri) throws IOException {
        File imported = new File(recordingsDir(), "imported_" + System.currentTimeMillis() + ".wav");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(imported)) {
            if (in == null) throw new IOException("Unable to read selected file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return imported;
    }

    private String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
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
