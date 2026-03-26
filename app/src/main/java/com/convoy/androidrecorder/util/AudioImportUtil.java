package com.convoy.androidrecorder.util;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Locale;

public final class AudioImportUtil {
    private static final String TAG = "AudioImportUtil";
    private static final long TIMEOUT_US = 10000;
    private static final int TARGET_SAMPLE_RATE = 16000;

    private AudioImportUtil() {}

    public interface ProgressListener {
        void onProgress(int percent, String stage);
    }

    public static ImportedAudio importToWav(Context context, Uri uri, File outputDir, ProgressListener listener) throws IOException {
        String displayName = queryDisplayName(context, uri);
        if (displayName == null) displayName = "imported_audio";
        if (!outputDir.exists()) outputDir.mkdirs();

        String baseName = sanitizeBaseName(displayName);
        File outFile = new File(outputDir, baseName + ".wav");
        String lower = displayName.toLowerCase(Locale.US);
        notifyProgress(listener, 5, "preparing import");

        if (lower.endsWith(".wav")) {
            File copied = new File(outputDir, baseName + ".source.wav");
            copyUriToFile(context, uri, copied, listener);
            normalizeWavTo16kMono(copied, outFile, listener);
            notifyProgress(listener, 100, "import complete");
            return new ImportedAudio(displayName, outFile);
        }

        File sourceCopy = new File(outputDir, baseName + originalExtension(displayName));
        copyUriToFile(context, uri, sourceCopy, listener);
        try {
            decodeMediaTo16kMonoWav(context, uri, outFile, listener);
        } catch (IOException decodeError) {
            notifyProgress(listener, 72, "ffmpeg fallback");
            ffmpegConvertTo16kMonoWav(sourceCopy, outFile);
        }
        notifyProgress(listener, 100, "import complete");
        return new ImportedAudio(displayName, outFile);
    }

    private static void copyUriToFile(Context context, Uri uri, File target, ProgressListener listener) throws IOException {
        long totalBytes = -1L;
        try {
            var descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (descriptor != null) {
                totalBytes = descriptor.getLength();
                descriptor.close();
            }
        } catch (Exception ignored) {
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Unable to open input stream");
            byte[] buffer = new byte[8192];
            int read;
            long copied = 0L;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                if (totalBytes > 0) {
                    int percent = 5 + (int) Math.min(45, (copied * 45) / totalBytes);
                    notifyProgress(listener, percent, "copying wav");
                }
            }
        }
    }

    private static void normalizeWavTo16kMono(File source, File target, ProgressListener listener) throws IOException {
        notifyProgress(listener, 55, "normalizing wav");
        WavData wavData = readPcm16Wave(source);
        short[] pcm = bytesToShorts(wavData.pcmBytes);
        short[] mono = downmixToMono(pcm, wavData.channels);
        float[] monoFloat = pcm16ToFloat(mono);
        float[] resampled = enhanceForSpeech(resampleLinear(monoFloat, wavData.sampleRate, TARGET_SAMPLE_RATE));
        short[] finalPcm = floatToPcm16(resampled);
        notifyProgress(listener, 90, "writing wav");
        WaveUtil.createWaveFile(target.getAbsolutePath(), shortsToBytes(finalPcm), TARGET_SAMPLE_RATE, 1, 2);
    }

    private static void decodeMediaTo16kMonoWav(Context context, Uri uri, File outFile, ProgressListener listener) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            notifyProgress(listener, 10, "reading media");
            extractor.setDataSource(context, uri, null);
            int audioTrack = selectAudioTrack(extractor);
            if (audioTrack < 0) throw new IOException("No audio track found in selected media");
            extractor.selectTrack(audioTrack);

            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Audio mime type missing");

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int sourceSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int decodePercent = 15;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        outputBuffer.get(chunk);
                        pcmOut.write(chunk);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (decodePercent < 70) {
                        decodePercent += 2;
                        notifyProgress(listener, decodePercent, "decoding media");
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            codec.stop();
            codec.release();

            short[] decoded = bytesToShorts(pcmOut.toByteArray());
            short[] mono = downmixToMono(decoded, channelCount);
            float[] monoFloat = pcm16ToFloat(mono);
            notifyProgress(listener, 75, "enhancing audio");
            float[] resampled = enhanceForSpeech(resampleLinear(monoFloat, sourceSampleRate, TARGET_SAMPLE_RATE));
            short[] finalPcm = floatToPcm16(resampled);
            notifyProgress(listener, 92, "writing wav");
            WaveUtil.createWaveFile(outFile.getAbsolutePath(), shortsToBytes(finalPcm), TARGET_SAMPLE_RATE, 1, 2);
        } catch (Exception e) {
            throw new IOException("Failed to import media: " + e.getMessage(), e);
        } finally {
            extractor.release();
        }
    }

    private static void ffmpegConvertTo16kMonoWav(File source, File outFile) throws IOException {
        String command = "-y -i " + ffmpegQuote(source) + " -vn -ac 1 -ar 16000 -c:a pcm_s16le " + ffmpegQuote(outFile);
        FFmpegSession session = FFmpegKit.execute(command);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            String detail = session.getFailStackTrace();
            if (detail == null || detail.isBlank()) detail = session.getOutput();
            throw new IOException("FFmpeg fallback failed" + (detail == null || detail.isBlank() ? "" : ": " + detail));
        }
    }

    private static String ffmpegQuote(File file) {
        String path = file.getAbsolutePath().replace("'", "'\''");
        return "'" + path + "'";
    }

    private static String originalExtension(String displayName) {
        int dot = displayName.lastIndexOf('.');
        return dot >= 0 ? displayName.substring(dot) : ".bin";
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) return cursor.getString(nameIndex);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query display name", e);
        }
        return null;
    }

    private static String sanitizeBaseName(String displayName) {
        String base = displayName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
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

    private static short[] bytesToShorts(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) shorts[i] = buffer.getShort();
        return shorts;
    }

    private static byte[] shortsToBytes(short[] shorts) {
        ByteBuffer buffer = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : shorts) buffer.putShort(s);
        return buffer.array();
    }

    private static short[] downmixToMono(short[] pcm, int channelCount) {
        if (channelCount <= 1) return pcm;
        int frames = pcm.length / channelCount;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channelCount; c++) {
                sum += pcm[i * channelCount + c];
            }
            mono[i] = (short) (sum / channelCount);
        }
        return mono;
    }

    private static float[] pcm16ToFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768f;
        return out;
    }

    private static short[] floatToPcm16(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) (clamped * 32767f);
        }
        return out;
    }

    private static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (sourceRate <= 0 || sourceRate == targetRate) return input;
        int outputLength = (int) Math.max(1, Math.round(input.length * (targetRate / (double) sourceRate)));
        float[] output = new float[outputLength];
        double ratio = sourceRate / (double) targetRate;
        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i * ratio;
            int left = (int) Math.floor(srcIndex);
            int right = Math.min(left + 1, input.length - 1);
            double frac = srcIndex - left;
            output[i] = (float) ((1.0 - frac) * input[left] + frac * input[right]);
        }
        return output;
    }

    private static float[] enhanceForSpeech(float[] input) {
        if (input.length == 0) return input;

        float[] filtered = new float[input.length];
        float previousInput = 0f;
        float previousOutput = 0f;
        for (int i = 0; i < input.length; i++) {
            float current = input[i];
            float highPass = (float) (0.97 * (previousOutput + current - previousInput));
            filtered[i] = highPass;
            previousInput = current;
            previousOutput = highPass;
        }

        float[] leveled = applyAdaptiveSpeechGain(filtered);
        return softLimit(leveled);
    }

    private static float[] applyAdaptiveSpeechGain(float[] input) {
        int window = 1600;
        float[] output = new float[input.length];
        double avgAbs = 0.0;
        for (float sample : input) avgAbs += Math.abs(sample);
        avgAbs = avgAbs / Math.max(1, input.length);
        double gate = Math.max(0.004, avgAbs * 0.55);

        for (int start = 0; start < input.length; start += window) {
            int end = Math.min(input.length, start + window);
            double rms = 0.0;
            for (int i = start; i < end; i++) rms += input[i] * input[i];
            rms = Math.sqrt(rms / Math.max(1, end - start));

            float gain;
            if (rms < gate * 0.8) {
                gain = 1.1f;
            } else if (rms < 0.025) {
                gain = 6.5f;
            } else if (rms < 0.06) {
                gain = 3.8f;
            } else if (rms < 0.12) {
                gain = 2.2f;
            } else {
                gain = 1.2f;
            }

            for (int i = start; i < end; i++) {
                float sample = input[i];
                if (Math.abs(sample) < gate) sample *= 0.45f;
                output[i] = sample * gain;
            }
        }
        return output;
    }

    private static float[] softLimit(float[] input) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            float sample = (float) Math.tanh(input[i] * 1.5f) / 1.05f;
            output[i] = Math.max(-0.98f, Math.min(0.98f, sample));
        }
        return output;
    }

    private static void notifyProgress(ProgressListener listener, int percent, String stage) {
        if (listener != null) listener.onProgress(percent, stage);
    }

    public static final class ImportedAudio {
        private final String displayName;
        private final File wavFile;

        public ImportedAudio(String displayName, File wavFile) {
            this.displayName = displayName;
            this.wavFile = wavFile;
        }

        public String getDisplayName() {
            return displayName;
        }

        public File getWavFile() {
            return wavFile;
        }
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
