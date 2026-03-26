package com.convoy.androidrecorder.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class AudioTrimUtil {
    private static final int FRAME_MS = 20;
    private static final int KEEP_PADDING_MS = 150;
    private static final float SILENCE_THRESHOLD = 0.012f;
    private static final int MIN_KEEP_MS = 120;

    private AudioTrimUtil() {}

    public static File trimQuietSections(File inputWav, File outputWav) throws IOException {
        WavData wavData = readPcm16Wave(inputWav);
        byte[] trimmed = trimQuietSections(wavData);
        WaveUtil.createWaveFile(outputWav.getAbsolutePath(), trimmed, wavData.sampleRate, wavData.channels, 2);
        return outputWav;
    }

    public static byte[] trimQuietSections(byte[] pcmBytes, int sampleRate, int channels) {
        return trimQuietSections(new WavData(sampleRate, channels, pcmBytes));
    }

    private static byte[] trimQuietSections(WavData wavData) {
        int frameSamples = Math.max(1, wavData.sampleRate * FRAME_MS / 1000);
        int frameBytes = frameSamples * wavData.channels * 2;
        int paddingFrames = Math.max(1, KEEP_PADDING_MS / FRAME_MS);
        int minKeepFrames = Math.max(1, MIN_KEEP_MS / FRAME_MS);
        int totalFrames = Math.max(1, (int) Math.ceil(wavData.pcmBytes.length / (double) frameBytes));
        boolean[] keep = new boolean[totalFrames];

        for (int frame = 0; frame < totalFrames; frame++) {
            int start = frame * frameBytes;
            int end = Math.min(wavData.pcmBytes.length, start + frameBytes);
            float level = averageAbsLevel(wavData.pcmBytes, start, end, wavData.channels);
            if (level >= SILENCE_THRESHOLD) {
                int from = Math.max(0, frame - paddingFrames);
                int to = Math.min(totalFrames - 1, frame + paddingFrames);
                for (int i = from; i <= to; i++) keep[i] = true;
            }
        }

        int keptFrames = 0;
        for (boolean keepFrame : keep) if (keepFrame) keptFrames++;
        if (keptFrames == 0 || keptFrames < minKeepFrames) {
            return wavData.pcmBytes;
        }

        List<byte[]> chunks = new ArrayList<>();
        int totalBytes = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            if (!keep[frame]) continue;
            int start = frame * frameBytes;
            int end = Math.min(wavData.pcmBytes.length, start + frameBytes);
            int len = end - start;
            byte[] chunk = new byte[len];
            System.arraycopy(wavData.pcmBytes, start, chunk, 0, len);
            chunks.add(chunk);
            totalBytes += len;
        }

        byte[] trimmed = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, trimmed, offset, chunk.length);
            offset += chunk.length;
        }
        return trimmed.length == 0 ? wavData.pcmBytes : trimmed;
    }

    private static float averageAbsLevel(byte[] pcmBytes, int start, int end, int channels) {
        ByteBuffer bb = ByteBuffer.wrap(pcmBytes, start, end - start).order(ByteOrder.LITTLE_ENDIAN);
        int samples = Math.max(1, (end - start) / 2);
        double sum = 0.0;
        while (bb.remaining() >= 2) {
            short value = bb.getShort();
            sum += Math.abs(value / 32768.0);
        }
        return (float) (sum / samples * Math.max(1, channels));
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
