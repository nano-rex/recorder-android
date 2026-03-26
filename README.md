# Recorder Android

Standalone Android recorder app extracted from `transcriber-android`.

## Features
- start and stop microphone recording
- prefer Android `VOICE_RECOGNITION` input, with `MIC` fallback
- enable supported speech audio effects when available
- save enhanced 16 kHz mono WAV files locally
- optionally auto-trim quiet sections after recording
- trim a picked PCM 16-bit WAV file from the device

## Build
```bash
cd /home/user/github/recorder-android
./gradlew :app:assembleDebug
```

## Output
Saved files go under the app's external music folder in `TranscriberRecorder/`.

When auto-trim is enabled, the app also saves a `_trimmed.wav` file beside the recorded WAV.
Picked-file trimming currently supports PCM 16-bit WAV input.
