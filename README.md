# Stubio (Android TV)

Stubio unifies "External Player" setup for Stremio - separating trailers from streams.

Route incoming Stremio playback intents to user-configured Android TV player apps (for example VLC, Vimu, MX Player, SmartTube, YouTube).

One simple setup "seta nd forget" UI, no more juggling external player choices.

## Requirements

- Android TV / Google TV device or emulator running Android 9+ (API 28+)
- Stremio app

For development/contributors:
- JDK 17
- Android SDK — platform `android-35`, build-tools `35.0.0`

> ℹ️ **NOTE: Networking and compatibility:**
> 
> Stubio does **not** make direct HTTP(S) requests in application code. `android.permission.INTERNET` remains declared intentionally as a compatibility safeguard for routed playback intents where external player apps consume network stream URLs.

---

## Quick start

1. Simply install or sideload the release APK. 
2. Open via launcher icon to configure (primary and fallback) external players, and/or to whitelist additional hosts.
3. Set Stubio as your external player in Stremio.
4. 🪄 *Let the magic of convenience unfold*. Trailers will now route to your preferred youtube app, and streams will use your preferred video player (when using "external"); no more shitty Stremio/Android app dialog to blindly pick from.

```bash
# Install to connected ATV device/emulator
adb install -r stubio*.apk
```

## TV-focused behaviour

- **Leanback-only launcher** — app appears on Android TV home screen; `CATEGORY_LEANBACK_LAUNCHER` is the sole launcher entry point.
- **D-pad-first setup screen** — deterministic focus movement; D-pad up from the top field wraps to the Save button (circular navigation).
- **TV player detection** — checks both `CATEGORY_LAUNCHER` and `CATEGORY_LEANBACK_LAUNCHER` so external player apps are found correctly.
- **Async app picker and 10'-legibility** — designed to load reliably, even on slow TV hardware. Big typography and D-pad targets, high-contrast purple theme, compact to fit standard landscape screen.
- **Configurable URI allowlist extension** — Setup includes an "Additional allowed domains/IPs" field (comma-separated) that extends host whitelisting beyond built-in localhost/private-network/Stremio domain rules.

---

## ADB intent test commands

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


## Known limitations

- Launcher icon pack is wired for `android:icon` and adaptive icon layers, but no matching dedicated `ic_launcher_round` raster set. Legacy round mipmaps are kept as a safe pre-API 26 fallback.
- TV banner is currently a basic generated vector drawable (`@drawable/tv_banner`); the imported art set only includes square launcher/store icons, not a 320×180 TV banner source.
- Exit from youtube apps is still manual (back out). "One-track then exit" may be developed for SmartTube in future.
- Playback position resume relies on external players returning position via `RESULT_OK`; behaviour varies by player version. This is a limitation of external players and Stremio, best-effort helpers built in to Stubio have not yet solved this entirely.
