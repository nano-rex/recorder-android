package com.convoy.androidrecorder;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecordingsActivity extends AppCompatActivity {
    private TextView tvHardwarePanel;
    private TextView tvFolderStatus;
    private TextView tvNowPlaying;
    private Button btnPlayPause;
    private SeekBar seekPlayback;
    private ListView listRecordings;
    private int defaultStatusColor;
    private String statusMessage = "Status: idle";
    private boolean statusWarning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> recordings = new ArrayList<>();
    private ArrayAdapter<String> recordingsAdapter;
    private File selectedRecording;
    private MediaPlayer mediaPlayer;
    private boolean isSeeking = false;

    private final Runnable playbackUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null) {
                try {
                    if (!isSeeking) {
                        seekPlayback.setProgress(mediaPlayer.getCurrentPosition());
                    }
                    if (mediaPlayer.isPlaying()) {
                        handler.postDelayed(this, 300);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppSettings.applyNightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        tvHardwarePanel = findViewById(R.id.tvHardwarePanel);
        tvFolderStatus = findViewById(R.id.tvFolderStatus);
        tvNowPlaying = findViewById(R.id.tvNowPlaying);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        seekPlayback = findViewById(R.id.seekPlayback);
        listRecordings = findViewById(R.id.listRecordings);
        Button btnTabHome = findViewById(R.id.btnTabHome);
        Button btnTabFolder = findViewById(R.id.btnTabFolder);
        Button btnTabSettings = findViewById(R.id.btnTabSettings);
        Button btnSettings = findViewById(R.id.btnSettings);
        Button btnFolderSetupPage = findViewById(R.id.btnFolderSetupPage);
        Button btnRefreshFolder = findViewById(R.id.btnRefreshFolder);
        defaultStatusColor = tvHardwarePanel.getCurrentTextColor();

        recordingsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, new ArrayList<>());
        listRecordings.setAdapter(recordingsAdapter);
        listRecordings.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        btnTabHome.setOnClickListener(v -> finish());
        btnTabFolder.setEnabled(false);
        btnTabSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnFolderSetupPage.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnRefreshFolder.setOnClickListener(v -> refreshRecordingsList());
        btnPlayPause.setOnClickListener(v -> togglePlayback());
        listRecordings.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < recordings.size()) {
                selectedRecording = recordings.get(position);
                tvNowPlaying.setText("Selected: " + selectedRecording.getName());
                stopPlayback();
                btnPlayPause.setText("Play");
            }
        });
        seekPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(progress);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
            }
        });

        refreshRecordingsList();
        refreshPanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecordingsList();
        refreshPanel();
    }

    private File recordingsDir() {
        return StorageUtils.recordingsDir(this);
    }

    private void refreshRecordingsList() {
        recordings.clear();
        List<String> names = new ArrayList<>();
        Button btnFolderSetupPage = findViewById(R.id.btnFolderSetupPage);
        if (!StorageUtils.isWorkspaceConfigured(this)) {
            btnFolderSetupPage.setVisibility(android.view.View.VISIBLE);
            tvFolderStatus.setText("Workspace folder not set. Choose a folder in Settings.");
            recordingsAdapter.clear();
            recordingsAdapter.addAll(names);
            recordingsAdapter.notifyDataSetChanged();
            seekPlayback.setProgress(0);
            seekPlayback.setMax(0);
            btnPlayPause.setEnabled(false);
            tvNowPlaying.setText("Now playing: none");
            return;
        }

        btnFolderSetupPage.setVisibility(android.view.View.GONE);
        File[] files = recordingsDir().listFiles((dir, name) -> name.toLowerCase().endsWith(".wav"));
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                recordings.add(file);
                names.add(file.getName());
            }
        }
        tvFolderStatus.setText(recordings.isEmpty() ? "No recordings found." : "Recordings: " + recordings.size());
        recordingsAdapter.clear();
        recordingsAdapter.addAll(names);
        recordingsAdapter.notifyDataSetChanged();
        btnPlayPause.setEnabled(!recordings.isEmpty());
        if (selectedRecording != null && !selectedRecording.exists()) {
            selectedRecording = null;
        }
        if (selectedRecording == null && !recordings.isEmpty()) {
            selectedRecording = recordings.get(0);
            tvNowPlaying.setText("Selected: " + selectedRecording.getName());
            listRecordings.setItemChecked(0, true);
        } else if (selectedRecording == null) {
            tvNowPlaying.setText("Now playing: none");
        }
    }

    private void togglePlayback() {
        if (selectedRecording == null) {
            setStatusMessage("Status: select a recording first", true);
            return;
        }
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            btnPlayPause.setText("Play");
            return;
        }
        if (mediaPlayer != null) {
            mediaPlayer.start();
            btnPlayPause.setText("Pause");
            handler.post(playbackUpdater);
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(selectedRecording.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                btnPlayPause.setText("Play");
                seekPlayback.setProgress(seekPlayback.getMax());
            });
            seekPlayback.setMax(mediaPlayer.getDuration());
            seekPlayback.setProgress(0);
            mediaPlayer.start();
            btnPlayPause.setText("Pause");
            tvNowPlaying.setText("Now playing: " + selectedRecording.getName());
            handler.post(playbackUpdater);
        } catch (IOException e) {
            stopPlayback();
            setStatusMessage("Status: failed to play recording - " + e.getMessage(), true);
        }
    }

    private void stopPlayback() {
        handler.removeCallbacks(playbackUpdater);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        seekPlayback.setProgress(0);
    }

    private void setStatusMessage(String message, boolean warning) {
        statusMessage = message;
        statusWarning = warning;
        refreshPanel();
    }

    private void refreshPanel() {
        String folder = StorageUtils.describeBaseDir(this);
        String content = "Recorder\nWorkspace: " + folder + "\nSource: " +
                (AppSettings.SOURCE_MICROPHONE.equals(AppSettings.getRecordingSource(this)) ? "Microphone" : "Voice recognition")
                + "\n" + statusMessage;
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
        handler.removeCallbacks(playbackUpdater);
        stopPlayback();
    }
}
