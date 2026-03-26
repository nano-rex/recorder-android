# Recorder Android

Standalone Android recorder app extracted from `transcriber-android`.

## Features
- start and stop microphone recording
- prefer Android `VOICE_RECOGNITION` input, with `MIC` fallback
- enable supported speech audio effects when available
- save enhanced 16 kHz mono WAV files locally
- optionally auto-trim quiet sections after recording using offline WebRTC VAD
- trim a picked local 16 kHz mono PCM WAV file with the same offline VAD path

## Build
```bash
cd /home/user/github/recorder-android
./gradlew :app:assembleDebug
```

## Output
Saved files go under the app's external music folder in `TranscriberRecorder/`.

When auto-trim is enabled, the app also saves a `_trimmed.wav` file beside the recorded WAV.
The picked-file trim flow currently supports 16 kHz mono PCM WAV input.
