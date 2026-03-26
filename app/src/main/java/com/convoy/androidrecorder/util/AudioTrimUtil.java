package com.convoy.androidrecorder.util;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class AudioTrimUtil {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SAMPLES = 320;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int KEEP_PADDING_MS = 200;

    private AudioTrimUtil() {}

    public static File trimQuietSections(File inputWav, File outputWav) throws IOException {
        WavData wavData = readPcm16Wave(inputWav);
        byte[] trimmed = trimQuietSections(wavData);
        WaveUtil.createWaveFile(outputWav.getAbsolutePath(), trimmed, wavData.sampleRate, wavData.channels, 2);
        return outputWav;
    }

    public static byte[] trimQuietSections(byte[] pcmBytes, int sampleRate, int channels) throws IOException {
        return trimQuietSections(new WavData(sampleRate, channels, pcmBytes));
    }

    private static byte[] trimQuietSections(WavData wavData) throws IOException {
        validateVadFormat(wavData.sampleRate, wavData.channels);
        int totalFrames = Math.max(1, (int) Math.ceil(wavData.pcmBytes.length / (double) FRAME_BYTES));
        boolean[] keep = new boolean[totalFrames];
        int paddingFrames = Math.max(1, KEEP_PADDING_MS / frameDurationMs());
        boolean foundSpeech = false;

        try (VadWebRTC vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(300)
                .setSpeechDurationMs(50)
                .build()) {
            for (int frame = 0; frame < totalFrames; frame++) {
                byte[] frameBytes = copyFrame(wavData.pcmBytes, frame);
                if (vad.isSpeech(frameBytes)) {
                    foundSpeech = true;
                    int from = Math.max(0, frame - paddingFrames);
                    int to = Math.min(totalFrames - 1, frame + paddingFrames);
                    for (int i = from; i <= to; i++) keep[i] = true;
                }
            }
        } catch (Exception e) {
            throw new IOException("VAD trim failed", e);
        }

        if (!foundSpeech) {
            return wavData.pcmBytes;
        }

        List<byte[]> chunks = new ArrayList<>();
        int totalBytes = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            if (!keep[frame]) continue;
            int start = frame * FRAME_BYTES;
            int end = Math.min(wavData.pcmBytes.length, start + FRAME_BYTES);
            int len = end - start;
            byte[] chunk = new byte[len];
            System.arraycopy(wavData.pcmBytes, start, chunk, 0, len);
            chunks.add(chunk);
            totalBytes += len;
        }

        if (totalBytes == 0) {
            return wavData.pcmBytes;
        }

        byte[] trimmed = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, trimmed, offset, chunk.length);
            offset += chunk.length;
        }
        return trimmed;
    }

    private static byte[] copyFrame(byte[] pcmBytes, int frameIndex) {
        int start = frameIndex * FRAME_BYTES;
        int available = Math.max(0, pcmBytes.length - start);
        byte[] frame = new byte[FRAME_BYTES];
        if (available > 0) {
            System.arraycopy(pcmBytes, start, frame, 0, Math.min(FRAME_BYTES, available));
        }
        return frame;
    }

    private static int frameDurationMs() {
        return FRAME_SAMPLES * 1000 / SAMPLE_RATE;
    }

    private static void validateVadFormat(int sampleRate, int channels) throws IOException {
        if (sampleRate != SAMPLE_RATE || channels != CHANNELS) {
            throw new IOException("VAD trim only supports 16 kHz mono PCM WAV files");
        }
    }

    private static WavData readPcm16Wave(File inputWav) throws IOException {
        byte[] bytes = Files.readAllBytes(inputWav.toPath());
        if (bytes.length < 44) throw new IOException("WAV file too small");
        if (!matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WAVE")) {
            throw new IOException("Unsupported WAV container");
        }

        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int audioFormat = 0;
        int dataOffset = -1;
        int dataSize = -1;

        while (offset + 8 <= bytes.length) {
            String chunkId = new String(bytes, offset, 4);
            int chunkSize = littleEndianInt(bytes, offset + 4);
            int chunkDataOffset = offset + 8;
            if ("fmt ".equals(chunkId)) {
                audioFormat = littleEndianShort(bytes, chunkDataOffset);
                channels = littleEndianShort(bytes, chunkDataOffset + 2);
                sampleRate = littleEndianInt(bytes, chunkDataOffset + 4);
                bitsPerSample = littleEndianShort(bytes, chunkDataOffset + 14);
            } else if ("data".equals(chunkId)) {
                dataOffset = chunkDataOffset;
                dataSize = Math.min(chunkSize, bytes.length - chunkDataOffset);
                break;
            }
            offset = chunkDataOffset + chunkSize + (chunkSize % 2);
        }

        if (audioFormat != 1 || bitsPerSample != 16 || channels <= 0 || sampleRate <= 0 || dataOffset < 0 || dataSize <= 0) {
            throw new IOException("Only PCM 16-bit WAV files are supported");
        }

        byte[] pcmBytes = new byte[dataSize];
        System.arraycopy(bytes, dataOffset, pcmBytes, 0, dataSize);
        return new WavData(sampleRate, channels, pcmBytes);
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        byte[] target = value.getBytes();
        if (offset + target.length > bytes.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (bytes[offset + i] != target[i]) return false;
        }
        return true;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static short littleEndianShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8));
    }

    private static final class WavData {
        private final int sampleRate;
        private final int channels;
        private final byte[] pcmBytes;

        private WavData(int sampleRate, int channels, byte[] pcmBytes) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.pcmBytes = pcmBytes;
        }
    }
}
