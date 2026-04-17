# Stubio (Android TV)

Stubio routes incoming Stremio playback intents to user-configured Android TV player apps (for example VLC, MX Player, SmartTube, YouTube).

## Requirements

- Android TV / Google TV device or emulator running Android 9+ (API 28+)
- JDK 17
- Android SDK — platform `android-35`, build-tools `35.0.0`

## Quick start

```bash
# Verify toolchain
./gradlew --version

# Debug build
./gradlew :app:assembleDebug

# Install to connected ATV device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## TV-focused behaviour

- **Leanback-only launcher** — app appears on Android TV home screen; `CATEGORY_LEANBACK_LAUNCHER` is the sole launcher entry point.
- **D-pad-first setup screen** — deterministic focus movement; D-pad up from the top field wraps to the Save button (circular navigation).
- **TV player detection** — checks both `CATEGORY_LAUNCHER` and `CATEGORY_LEANBACK_LAUNCHER` so TV-only builds of VLC, SmartTube, etc. are found correctly.
- **Async app picker** — icon loading runs off the main thread; buttons disable during load to prevent double-tap on slow TV hardware.
- **10-foot legibility** — 18–40 sp typography, 64 dp D-pad targets, high-contrast purple theme, accent-coloured focus ring.

## Build commands

```bash
# Lint + debug build + unit tests
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon

# Release candidate (unsigned by default)
./gradlew :app:lintRelease :app:assembleRelease --no-daemon
```

## Signed release build

Provide signing secrets via Gradle properties or environment variables:

| Variable | Description |
|----------|-------------|
| `ANDROID_SIGNING_STORE_FILE` | Absolute path to `.keystore` / `.jks` file |
| `ANDROID_SIGNING_STORE_PASSWORD` | Keystore password |
| `ANDROID_SIGNING_KEY_ALIAS` | Key alias |
| `ANDROID_SIGNING_KEY_PASSWORD` | Key password |

```bash
ANDROID_SIGNING_STORE_FILE=/path/release.keystore \
ANDROID_SIGNING_STORE_PASSWORD=*** \
ANDROID_SIGNING_KEY_ALIAS=*** \
ANDROID_SIGNING_KEY_PASSWORD=*** \
./gradlew :app:assembleRelease --no-daemon
```

## TV runtime validation checklist

Use a physical Android TV or a Google TV emulator (AVD: Television 1080p, API 28+):

1. **Launch** — app icon appears on TV launcher; D-pad centre opens Setup.
2. **D-pad navigation** — navigate every field and button with D-pad only; confirm visible focus highlight.
3. **Circular navigation** — D-pad up from the first field wraps focus to the Save button.
4. **App picker** — open picker; confirm grid is focusable, scrollable, items selectable with D-pad centre.
5. **Save and relaunch** — save settings, kill the app, relaunch; confirm saved values persist.
6. **Intent routing** — send a Stremio stream intent and verify the configured player opens.
7. **Back/Home** — press Back from the player; confirm Stubio exits with no orphaned activities in recents.
8. **Empty state** — clear all player fields, save, send an intent; confirm fallback to default packages.

### ADB intent test commands

```bash
# Open setup directly
adb shell am start -n com.intentrouter.stubio/.SetupActivity

# Simulate a stream intent
adb shell am start \
  -a android.intent.action.VIEW \
  -d "http://127.0.0.1:11470/stream/test.mkv" \
  -t "video/*" \
  com.intentrouter.stubio/.MainActivity
```

## Architecture overview

| Component | Role |
|-----------|------|
| `SetupActivity` | Full-screen TV setup UI — configures player packages via SharedPreferences |
| `MainActivity` | Transparent routing activity — validates URI and launches the right player |
| `StubioApp` | Application class — forces dark mode for consistent TV rendering |

### Networking and compatibility note

- Stubio does **not** make direct HTTP(S) requests in application code.
- `android.permission.INTERNET` remains declared intentionally as a compatibility safeguard for routed playback intents where external player apps consume network stream URLs.

## CI

- `.github/workflows/ci.yml` — lint, unit tests, debug + release assembles on every PR and push to `main`; lint reports uploaded as artifacts.
- `.github/workflows/release.yml` — creates versioned GitHub Releases with a signed (or unsigned-fallback) APK.

## Known limitations

- Launcher icon pack is wired for `android:icon` and adaptive icon layers, but no matching dedicated `ic_launcher_round` raster set was provided in the import. Legacy round mipmaps are kept as a safe pre-API 26 fallback.
- TV banner is still the existing generated vector drawable (`@drawable/tv_banner`); the imported art set only includes square launcher/store icons, not a 320×180 TV banner source.
- No Espresso/instrumentation tests — UI testing requires a connected device or emulator.
- Playback position resume relies on external players returning position via `RESULT_OK`; behaviour varies by player version.
