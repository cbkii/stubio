# Stubio (Android TV)

Stubio routes incoming Stremio playback intents to user-configured Android TV player apps (for example VLC, MX Player, SmartTube, YouTube).

## Requirements
- Android TV / Google TV device on Android 9+ (API 28+)
- JDK 17
- Android SDK platform 35 and build-tools 35

## Local setup
```bash
./gradlew --version
./gradlew :app:assembleDebug
```

## TV-focused behavior
- Leanback launcher entry point (TV banner + TV launcher category)
- D-pad-first setup screen with deterministic focus movement
- High contrast, larger 10-foot readable text and controls
- Remote-operable app picker dialog for installed TV apps

## Build commands
```bash
# Fast verification
./gradlew :app:lintDebug :app:assembleDebug

# Release candidate verification (unsigned by default)
./gradlew :app:lintRelease :app:assembleRelease
```

## Signed release build
Provide signing secrets through Gradle properties or environment variables:
- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Example:
```bash
ANDROID_SIGNING_STORE_FILE=/path/release.keystore \
ANDROID_SIGNING_STORE_PASSWORD=*** \
ANDROID_SIGNING_KEY_ALIAS=*** \
ANDROID_SIGNING_KEY_PASSWORD=*** \
./gradlew :app:assembleRelease
```

## Runtime TV validation checklist
1. Launch app from Android TV home screen.
2. Navigate every setup field and picker button using D-pad only.
3. Open app picker and select installed apps with D-pad center.
4. Save settings and relaunch to confirm persistence.
5. Send a Stremio stream intent and verify selected external player opens.
6. Press Back/Home from external player and confirm Stubio exits cleanly.

## CI
- `.github/workflows/ci.yml` validates debug + release assemble, lint, and unit tests.
- `.github/workflows/release.yml` creates release artifacts/tags and now runs release lint before packaging.
