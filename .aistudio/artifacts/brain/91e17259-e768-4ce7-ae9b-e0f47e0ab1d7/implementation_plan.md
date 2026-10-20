# Implementation Plan — Live Audio Response & Secure Settings Dialog

## Problem Analysis

1. **AI Not Speaking ("keep saying hello, still no response")**:
   - **Root Cause 1 (`GeminiLiveClient.kt`)**: The client was sending audio chunks immediately upon WebSocket TCP connection without waiting for the `{ "setupComplete": {} }` handshake from the Gemini server. Sending audio before `setupComplete` causes the server to drop or reset the setup session.
   - **Root Cause 2 (`GeminiLiveClient.kt`)**: MIME type in `realtimeInput` was set to `"audio/pcm;rate=16000"` instead of `"audio/pcm"`.
   - **Root Cause 3 (`AudioRecordStreamer.kt`)**: `VOICE_COMMUNICATION` audio source returns zero bytes or fails to initialize on certain custom Android ROMs without falling back to `MediaRecorder.AudioSource.MIC`.
   - **Root Cause 4 (`AudioTrackPlayer.kt`)**: `USAGE_VOICE_COMMUNICATION` mode routes audio to earpiece on some devices unless `USAGE_MEDIA` and media volume are set.

2. **Secure System Settings Card ("Tap to Grant")**:
   - On standard Android, `WRITE_SECURE_SETTINGS` cannot be granted via Settings -> App Info -> Permissions.
   - **Fix**: When tapping "Secure System Settings", attempt root grant if rooted; if non-root, pop up a dialog with the exact ADB command (`adb shell pm grant ...`) and a "Copy ADB Command" button.

---

## Proposed Changes

### 1. `core/live/GeminiLiveClient.kt`
- Add `setupComplete` handler: wait for `{ "setupComplete": {} }` before setting `_connectionState.value = LiveConnectionState.Connected`.
- Update `realtimeInput` MIME type to `"audio/pcm"`.
- Explicitly log and report WebSocket error messages to `_modelTranscript`.

### 2. `core/audio/AudioRecordStreamer.kt` & `core/audio/AudioTrackPlayer.kt`
- `AudioRecordStreamer.kt`: Fall back to `MediaRecorder.AudioSource.MIC` if `VOICE_COMMUNICATION` fails or reads 0 bytes.
- `AudioTrackPlayer.kt`: Use `AudioAttributes.USAGE_MEDIA` with `AudioAttributes.CONTENT_TYPE_SPEECH` and ensure volume is set to `1.0f`.

### 3. `ui/screens/SetupWizardScreen.kt`
- When tapping "Secure System Settings" (`"secure"`):
  - If rooted: execute `pm grant com.aistudio.aura.vceagt android.permission.WRITE_SECURE_SETTINGS`.
  - If non-rooted: show an interactive dialog containing `adb shell pm grant com.aistudio.aura.vceagt android.permission.WRITE_SECURE_SETTINGS` with a "Copy ADB Command" button.

### 4. Build & Verify
- Run `compile_applet`.
- Run unit test suite (`gradle :app:testDebugUnitTest`).
- Build APK (`gradle assembleDebug`), upload to **GoFile**, and provide direct download link.

---

## User Review Required

Please review the plan above and click **Proceed** to apply the fixes.
