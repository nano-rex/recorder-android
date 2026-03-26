package com.convoy.androidrecorder.util;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WaveUtil {
    public static final String TAG = "WaveUtil";

    public static void createWaveFile(String filePath, byte[] samples, int sampleRate, int numChannels, int bytesPerSample) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
            int dataSize = samples.length;
            int audioFormat = (bytesPerSample == 2) ? 1 : (bytesPerSample == 4) ? 3 : 0;

            fileOutputStream.write("RIFF".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(36 + dataSize), 0, 4);
            fileOutputStream.write("WAVE".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write("fmt ".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(16), 0, 4);
            fileOutputStream.write(shortToByteArray((short) audioFormat), 0, 2);
            fileOutputStream.write(shortToByteArray((short) numChannels), 0, 2);
            fileOutputStream.write(intToByteArray(sampleRate), 0, 4);
            fileOutputStream.write(intToByteArray(sampleRate * numChannels * bytesPerSample), 0, 4);
            fileOutputStream.write(shortToByteArray((short) (numChannels * bytesPerSample)), 0, 2);
            fileOutputStream.write(shortToByteArray((short) (bytesPerSample * 8)), 0, 2);
            fileOutputStream.write("data".getBytes(StandardCharsets.UTF_8));
            fileOutputStream.write(intToByteArray(dataSize), 0, 4);
            fileOutputStream.write(samples);
        } catch (IOException e) {
            Log.e(TAG, "Error creating wav", e);
        }
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private static byte[] shortToByteArray(short value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }
}
