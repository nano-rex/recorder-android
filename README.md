# Recorder Android

Standalone Android recorder app extracted from `transcriber-android`.

## Features
- start and stop microphone recording
- prefer Android `VOICE_RECOGNITION` input, with `MIC` fallback
- enable supported speech audio effects when available
- save enhanced 16 kHz mono WAV files locally

## Build
```bash
cd /home/user/github/recorder-android
./gradlew :app:assembleDebug
```

## Output
Saved files go under the app's external music folder in `TranscriberRecorder/`.
