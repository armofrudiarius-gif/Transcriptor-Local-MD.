# Privacy Architecture — Transcriptor Local MD v2.0

## Runtime rule

The Android application must not declare `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE` and must not contain runtime HTTP clients, telemetry, analytics, cloud crash reporting or cloud speech APIs.

Audio is opened only through a user-selected URI or an application-owned recording. Processing, speaker diarization, ASR, SHA-256, checkpoints, editor state and exports remain on the Android device.

## Build-time versus runtime

GitHub Actions may download pinned model and library artifacts while compiling the APK. That does not grant the installed Android application network capability. CI verifies the compiled APK manifest after build.

## Data written locally

- selected audio: read only unless it is a recording created by the app;
- transcription checkpoints: private app storage;
- speaker display names and custom lexicon: SharedPreferences in private app storage;
- transcript exports: only destinations selected explicitly by the user through Android Storage Access Framework;
- SHA-256: computed locally from the selected audio bytes.

## CI gates

The v2.0 workflow fails if source/runtime manifest declares INTERNET or ACCESS_NETWORK_STATE. It also inspects the compiled APK permissions and records them in `PRIVACY-REPORT-v2.0.md`.

## Non-features

v2.0 does not perform automatic biometric identification of real persons. Speaker names are labels assigned by the user. No voice profile is uploaded or matched against a remote database.
