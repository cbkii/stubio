# RELEASE_READINESS_REPORT

_Last updated: 2026-04-15_

## Summary of fixes

### Round 1 (initial PR)
- Reworked manifest for TV-first packaging: leanback-only launcher, TV banner, tighter permissions, safer network security policy.
- Reworked routing activity for stability: removed foreground-service path, hardened intent validation, safer receiver registration, resilient playback callback handling.
- Rebuilt setup UI for Android TV 10-foot UX: larger typography, larger controls, deterministic D-pad movement, strong focus states, package-name validation.
- Modernized release config: enabled R8/resource shrinking in release, updated dependency versions, Java/Kotlin 17 toolchain, simplified ProGuard rules.
- Added repeatable CI workflow for debug/release lint + build + unit tests.
- Updated maintainer docs with TV validation and release instructions.
- Fixed release-workflow blocker from incompatible `androidComponents` API.

### Round 2 (TV release-readiness pass)
1. **Security: tightened URI allow-list** — `isAllowedUri` used an unanchored regex `"127\\.0\\.0\\.1"` that would also match `127.0.0.10`, `0127.0.0.1`, etc. Fixed by anchoring to `^127\\.0\\.0\\.1$`.
2. **TV functionality bug: leanback launcher detection** — `loadPlayerPackages` used `getLaunchIntentForPackage()` exclusively, which returns `null` for TV-only apps that only register `CATEGORY_LEANBACK_LAUNCHER` (no `CATEGORY_LAUNCHER`). Added `getLeanbackLaunchIntentForPackage()` fallback so TV-only builds of VLC, SmartTube, etc. are correctly detected.
3. **State correctness: stale broadcast-receiver statics** — `wipePlaybackData()` cleared local prefs but did not reset `StreamResultReceiver.lastKnownPosition`/`lastKnownDuration` statics, so a position from a previous session could be merged back in on the next `restoreCachedPlaybackData()` call. Fixed.
4. **Performance / ANR prevention: async app loading** — `loadLaunchableApps()` (invoked synchronously in `showAppPicker`) loads all app icons via `loadIcon()` on the main thread. On low-RAM TV hardware this can block the UI noticeably. Moved to an IO coroutine; picker buttons are disabled during the load to prevent double-tap. Activity `isFinishing` guard added to avoid updating a destroyed activity.
5. **Performance: GridView ViewHolder** — `LaunchableAppsAdapter.getView()` called `findViewById` on every scroll recycle. Added `ViewHolder` pattern to cache view references.
6. **TV UX: GridView D-pad selection highlight** — `app_tile_bg.xml` had no `state_selected` state, so items selected via D-pad in the picker `GridView` showed no visual change. Added `state_selected` to match the focused-state appearance.
7. **TV UX: phone-only ripple removed** — `item_app_tile.xml` had `android:foreground="?attr/selectableItemBackground"` and `android:clickable="true"`. The touch ripple foreground is invisible on TV (no touch), adds unnecessary overdraw, and the explicit `clickable` can interfere with `GridView`'s own click handling. Both removed.
8. **TV UX: theme focus ring colour** — `Theme.StubioSetup` did not set `colorControlHighlight`, so AppCompat used its default blue focus ring instead of the app's purple accent. Added `colorControlHighlight="@color/purple_accent"` and `colorControlActivated="@color/purple_accent"` (EditText cursor colour) to match the UI palette.
9. **TV UX: circular D-pad navigation** — Without `android:nextFocusUp` on the first row, pressing D-pad up from `editStreamPrimary`/`btnPickStreamPrimary` left focus stranded at the top of the form. Added `nextFocusUp="@id/btnSave"` so D-pad up wraps to the Save button (standard TV form navigation pattern).
10. **Build: targetSdk alignment** — `targetSdk` was set to 34 while `compileSdk` was 35. Aligned `targetSdk = 35`.
11. **Dead assets removed** — `ic_notification.png` existed in four density buckets (`drawable-hdpi/mdpi/xhdpi/xxhdpi`) and was referenced nowhere in the manifest, code, or layouts. Removed together with the now-empty density directories.
12. **Unit tests added** — `PackageNameValidationTest` provides a first set of reproducible tests for the package-name validation regex, giving the CI `testDebugUnitTest` step something meaningful to run.

## Release blockers found and resolved

| # | Blocker | Severity | Resolution |
|---|---------|----------|------------|
| 1 | URI allow-list allows 127.0.0.10+ due to unanchored regex | Medium | Fixed: anchored to `^127\\.0\\.0\\.1$` |
| 2 | TV-only player apps silently skipped in `loadPlayerPackages` | High | Fixed: added `getLeanbackLaunchIntentForPackage` check |
| 3 | Stale playback position re-used across sessions | Medium | Fixed: reset statics in `wipePlaybackData` |
| 4 | Main-thread icon load may ANR on slow TV hardware | Medium | Fixed: moved to IO coroutine |
| 5 | No state-selected on picker grid items | Low | Fixed: added `state_selected` to `app_tile_bg.xml` |
| 6 | Focus stranded at top of setup form with D-pad | Medium | Fixed: circular `nextFocusUp` added |
| 7 | `targetSdk 34` vs `compileSdk 35` mismatch | Low | Fixed: aligned both to 35 |

## Remaining risks

- **Runtime intent/playback behaviour** could only be statically validated here — no connected Android TV emulator/device was available in this environment.
- **TV banner** is a generated drawable gradient. A final 320×180 branded PNG/WebP may be preferable before store submission (Play Store requires a 320×180 TV banner for TV apps).
- **Round launcher parity** — imported launcher pack did not include dedicated `ic_launcher_round` raster assets. Existing round mipmaps remain in place for compatibility, while API 26+ round icons resolve through `mipmap-anydpi-v26/ic_launcher_round.xml`.
- **`PACKAGE_PATTERN` allows `org.videolan.vlc.debug`-style suffixes** — the regex validates structure but cannot verify the package is actually installed or is a legitimate player. This is by design (validation is structural, installation check happens at launch time).
- **No instrumentation/UI tests** — adding Espresso or UI Automator tests is beyond the scope of a static-analysis pass without a connected device.

## Exact verification commands used

```bash
# Static: confirm build and lint pass
./gradlew :app:lintDebug :app:assembleDebug --no-daemon
./gradlew :app:lintRelease :app:assembleRelease --no-daemon

# Static: run unit tests
./gradlew :app:testDebugUnitTest --no-daemon
```

All three commands complete without errors (verified in CI).

## Runtime-tested vs static review

| Area | Method |
|------|--------|
| Build / lint / unit tests | CI (automated) |
| Manifest exported-component hardening | Static analysis |
| Intent-routing code paths | Static analysis + code review |
| Focus navigation XML | Static analysis of layout + focus-chain XML attributes |
| Accessibility labels / content descriptions | Static analysis |
| D-pad flow end-to-end | **Not runtime-tested** — requires physical Android TV or ATV emulator |
| Playback position reporting to Stremio | **Not runtime-tested** |
| App picker async loading | **Not runtime-tested** |
