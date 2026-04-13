# RELEASE_READINESS_REPORT

## Summary of fixes
- Reworked manifest for TV-first packaging: leanback-only launcher, TV banner, tighter permissions, and safer network security policy.
- Reworked routing activity for stability: removed unnecessary foreground service flow, hardened intent validation, safer receiver registration, and resilient playback callback handling.
- Rebuilt setup UI for Android TV 10-foot UX: larger typography, larger controls, deterministic D-pad movement, stronger focus states, and package-name validation.
- Modernized release config: enabled R8/resource shrinking in release, updated dependency versions, moved to Java/Kotlin 17 toolchain, and simplified ProGuard rules.
- Added repeatable CI workflow for debug/release lint + build and unit tests.
- Updated maintainer docs with TV validation and release instructions.
- Fixed release-workflow blocker from the previous PR by removing incompatible `androidComponents` output-file mutation in `app/build.gradle.kts` and hardening SDK package installation in CI/release workflows.

## Release blockers found and resolved
1. **Potential runtime crash risk from foreground-service path** in `MainActivity` and overly broad permissions (service/notification permissions no longer needed).
2. **Manifest not TV-release ready** (mixed launcher categories, no TV banner, permissive cleartext setting).
3. **Weak release hardening** (minify disabled, over-broad ProGuard keeps, stale dependencies).
4. **Focus/legibility issues on setup flow** (small controls/text and incomplete D-pad semantics).
5. **No baseline CI validation workflow** for PRs.
6. **Workflow compile-time failure risk** from unsupported AGP API usage in `androidComponents`.

## Remaining risks
- Runtime intent/playback behavior could only be statically validated in this environment (no connected Android TV emulator/device available here).
- TV banner currently uses a generated drawable banner; a final branded 320x180 asset may still be desirable before store submission.

## Exact verification commands used
```bash
JAVA_HOME=$(mise where java@17) PATH="$JAVA_HOME/bin:$PATH" ./gradlew help --no-daemon
JAVA_HOME=$(mise where java@17) PATH="$JAVA_HOME/bin:$PATH" ./gradlew :app:lintRelease --no-daemon
curl -I -L 'https://github.com/cbkii/stubio/actions/runs/24366290020/job/71158810545?pr=9' | head -n 20
```

## Runtime-tested vs static review
- **Runtime tested:** Not possible in this environment (no Android TV emulator/device attached).
- **Statically reviewed:** Manifest/exported components, Gradle/release config, CI workflows, focus navigation XML, accessibility labels/content descriptions, and intent-routing code paths.
