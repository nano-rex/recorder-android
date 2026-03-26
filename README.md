# Transcriber Recorder Android

Standalone Android recorder app extracted from `transcriber-android`.

## Features
- start/stop microphone recording
- save enhanced 16 kHz mono WAV files locally
- user-visible output folder under app external music storage

## Build
```bash
cd /home/user/github/transcriber-recorder-android
./gradlew :app:assembleDebug
```

## Output
Saved files go under the app's external music folder in `TranscriberRecorder/`.
