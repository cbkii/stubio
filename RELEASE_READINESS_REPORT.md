# Release Readiness Report

## Summary of fixes
- Reworked manifest for TV-first packaging (leanback required, touchscreen optional, launcher banner, removed unsafe/unused permissions/components).
- Refactored MainActivity for safer intent handling, broadcast receiver registration hardening, and removal of unstable foreground-service logic.
- Modernised Gradle/dependencies and release config for deterministic versioning, R8/resource shrinking, Java 17, and lint gating.
- Upgraded setup UI/layouts for 10-foot readability and stronger D-pad accessibility.
- Added repeatable Android CI workflow for debug lint + debug/release builds.
- Replaced outdated README with release-focused setup/build/test instructions.

## Release blockers found and resolved
1. **Build-tooling instability / outdated dependency graph**
   - Resolved by updating plugin/dependency versions and Java toolchain alignment.
2. **Invalid/unimplemented exported receiver in manifest**
   - Removed broken receiver declaration path and tightened exported surface.
3. **Release shrinking disabled by ProGuard defaults**
   - Replaced with minimal targeted keep-rules and enabled minify/resource shrink in release.
4. **TV UX quality risks (small text, weak readability/focus ergonomics)**
   - Scaled typography/controls and improved D-pad-friendly setup/picker layouts.
5. **Missing continuous validation for release readiness**
   - Added CI workflow validating lint + debug/release assembly.

## Remaining risks
- No runtime emulator/device session was available in this environment, so focus traversal and visual quality were statically reviewed and build-validated only.
- `usesCleartextTraffic=true` remains intentionally enabled for local-network playback URLs.

## Verification commands used
- `./gradlew :app:assembleDebug --no-daemon`
- `./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon`

## Runtime vs static validation
- **Runtime-tested:** none (no connected Android TV emulator/device in this environment).
- **Statically reviewed:** manifest/package setup, activity logic, layouts/resources, Gradle/release config, CI workflows, and successful build/lint execution.
