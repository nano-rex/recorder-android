package com.convoy.androidrecorder.util;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecorderUtil {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private RecorderUtil() {}

    public static RecorderSession startRecording(File outWavFile) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer, 4096);

        int[] preferredSources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        String[] sourceLabels = new String[]{
                "voice recognition",
                "microphone"
        };

        AudioRecord recorder = null;
        String sourceLabel = "microphone";
        for (int i = 0; i < preferredSources.length; i++) {
            AudioRecord candidate = new AudioRecord(
                    preferredSources[i],
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate;
                sourceLabel = sourceLabels[i];
                break;
            }
            candidate.release();
        }

        if (recorder == null) {
            throw new IllegalStateException("Unable to initialize audio recorder");
        }

        List<AutoCloseable> audioEffects = enableSpeechEffects(recorder.getAudioSessionId());
        AtomicBoolean running = new AtomicBoolean(true);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        AudioRecord finalRecorder = recorder;

        Thread worker = new Thread(() -> {
            finalRecorder.startRecording();
            while (running.get()) {
                int read = finalRecorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    pcmOut.write(buffer, 0, read);
                }
            }
            finalRecorder.stop();
            finalRecorder.release();
            for (AutoCloseable effect : audioEffects) {
                try {
                    effect.close();
                } catch (Exception ignored) {
                }
            }
        }, "wav-recorder");
        worker.start();

        return new RecorderSession(outWavFile, running, worker, pcmOut, sourceLabel);
    }

    private static List<AutoCloseable> enableSpeechEffects(int audioSessionId) {
        List<AutoCloseable> effects = new ArrayList<>();
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor ns = NoiseSuppressor.create(audioSessionId);
            if (ns != null) {
                ns.setEnabled(true);
                effects.add(ns::release);
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl agc = AutomaticGainControl.create(audioSessionId);
            if (agc != null) {
                agc.setEnabled(true);
                effects.add(agc::release);
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(audioSessionId);
            if (aec != null) {
                aec.setEnabled(true);
                effects.add(aec::release);
            }
        }
        return effects;
    }

    public static final class RecorderSession {
        private final File outWavFile;
        private final AtomicBoolean running;
        private final Thread worker;
        private final ByteArrayOutputStream pcmOut;
        private final String sourceLabel;

        private RecorderSession(File outWavFile, AtomicBoolean running, Thread worker, ByteArrayOutputStream pcmOut,
                                String sourceLabel) {
            this.outWavFile = outWavFile;
            this.running = running;
            this.worker = worker;
            this.pcmOut = pcmOut;
            this.sourceLabel = sourceLabel;
        }

        public SavedRecording stopAndSave() throws Exception {
            running.set(false);
            worker.join();

            byte[] pcmBytes = pcmOut.toByteArray();
            short[] pcm16 = bytesToShorts(pcmBytes);
            short[] enhanced = enhancePcm16(pcm16);
            byte[] enhancedBytes = shortsToBytes(enhanced);
            WaveUtil.createWaveFile(outWavFile.getAbsolutePath(), enhancedBytes, SAMPLE_RATE, 1, 2);

            return new SavedRecording(outWavFile, sourceLabel);
        }

        public String getSourceLabel() {
            return sourceLabel;
        }

        private short[] bytesToShorts(byte[] bytes) {
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            short[] out = new short[bytes.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = bb.getShort();
            return out;
        }

        private byte[] shortsToBytes(short[] shorts) {
            ByteBuffer bb = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : shorts) bb.putShort(s);
            return bb.array();
        }

        private short[] enhancePcm16(short[] pcm16) {
            if (pcm16.length == 0) return pcm16;
            float[] floatSamples = new float[pcm16.length];
            for (int i = 0; i < pcm16.length; i++) {
                floatSamples[i] = pcm16[i] / 32768f;
            }

            float previousIn = 0f;
            float previousOut = 0f;
            for (int i = 0; i < floatSamples.length; i++) {
                float current = floatSamples[i];
                float filtered = (float) (0.97 * (previousOut + current - previousIn));
                floatSamples[i] = filtered;
                previousIn = current;
                previousOut = filtered;
            }

            floatSamples = applyAdaptiveGain(floatSamples);
            short[] out = new short[pcm16.length];
            for (int i = 0; i < floatSamples.length; i++) {
                float sample = (float) Math.tanh(floatSamples[i] * 1.5f) / 1.05f;
                sample = Math.max(-0.98f, Math.min(0.98f, sample));
                out[i] = (short) (sample * 32767f);
            }
            return out;
        }

        private float[] applyAdaptiveGain(float[] samples) {
            int window = 1600;
            float[] out = new float[samples.length];
            double avgAbs = 0.0;
            for (float sample : samples) avgAbs += Math.abs(sample);
            avgAbs = avgAbs / Math.max(1, samples.length);
            double gate = Math.max(0.004, avgAbs * 0.55);

            for (int start = 0; start < samples.length; start += window) {
                int end = Math.min(samples.length, start + window);
                double rms = 0.0;
                for (int i = start; i < end; i++) rms += samples[i] * samples[i];
                rms = Math.sqrt(rms / Math.max(1, end - start));

                float gain;
                if (rms < gate * 0.8) {
                    gain = 1.1f;
                } else if (rms < 0.025) {
                    gain = 6.0f;
                } else if (rms < 0.06) {
                    gain = 3.5f;
                } else if (rms < 0.12) {
                    gain = 2.0f;
                } else {
                    gain = 1.1f;
                }

                for (int i = start; i < end; i++) {
                    float sample = samples[i];
                    if (Math.abs(sample) < gate) sample *= 0.45f;
                    out[i] = sample * gain;
                }
            }
            return out;
        }
    }

    public static final class SavedRecording {
        private final File recordedFile;
        private final String sourceLabel;

        public SavedRecording(File recordedFile, String sourceLabel) {
            this.recordedFile = recordedFile;
            this.sourceLabel = sourceLabel;
        }

        public File getRecordedFile() {
            return recordedFile;
        }

        public String getSourceLabel() {
            return sourceLabel;
        }
    }
}
