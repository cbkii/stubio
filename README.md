# Stubio (Android TV)

Stubio is an Android TV intent router for Stremio external playback. It forwards trailer links to a YouTube app and stream links to a configured external video player.

## Requirements
- Android TV / Google TV device running Android 9+ (API 28+)
- Android SDK 35 for building
- JDK 17

## Local setup
```bash
./gradlew --version
./gradlew :app:assembleDebug
```

## QA commands
```bash
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## Release build
Unsigned local release:
```bash
./gradlew :app:assembleRelease
```

Signed release (env vars):
```bash
export ANDROID_SIGNING_STORE_FILE=/path/to/keystore.jks
export ANDROID_SIGNING_STORE_PASSWORD=***
export ANDROID_SIGNING_KEY_ALIAS=***
export ANDROID_SIGNING_KEY_PASSWORD=***
export VERSION_NAME=1.4.0
export VERSION_CODE=140
./gradlew :app:assembleRelease
```

## TV validation checklist
- Launch app from TV launcher banner/icon.
- Navigate setup entirely with D-pad (no touch).
- Verify initial focus appears in first editable field.
- Open app picker and select app using D-pad center key.
- Back key exits picker and returns to setup screen.
- Save settings and confirm toast feedback.
- Trigger Stremio playback and verify routing to configured trailer/stream app.

## ADB runtime checks
```bash
adb install -r app/build/outputs/apk/debug/stubio-1.4.0-debug.apk
adb shell monkey -p com.intentrouter.stubio.debug 1
adb logcat | rg Stubio
```

## Known limitations
- Runtime emulator/device verification is required for final visual/focus acceptance.
- Cleartext HTTP is enabled to support local-network Stremio stream URLs.
