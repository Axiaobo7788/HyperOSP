# HyperOSP progress

Last updated: 2026-09-27

## Current checkpoint

Repository bootstrap is complete. The working tree began as an unmodified
JingMatrix/libxposed-example clone at `87e9cb8`; the HyperOSP remote repository
was not readable from the current unauthenticated shell/browser environment,
and no HyperOSP document objects were present locally. These documents were
therefore bootstrapped from the explicit project handoff constraints without
resetting or discarding the template history.

## Milestones

| Milestone | State | Evidence / remaining work |
| --- | --- | --- |
| Safe repository bootstrap | Complete | Template history preserved; `origin` is HyperOSP, `upstream` is JingMatrix, work is on `feat/aosp-qs-legacy-poc` |
| Minimal HyperOSP module | Complete | UI/service/native demos removed; package, app identity, metadata, API 100 and SystemUI-only scope verified in APK |
| M1 legacy QS hook | Pending | Implement signature-checked, fail-safe class-name substitution |
| Local debug build | Baseline passed | `./gradlew :app:assembleDebug` passed on JDK 21 before the M1 hook; final hook build still required |
| Physical-device LSPosed validation | Human-gated | No installation or device action has been performed |

## Validation boundary

Do not interpret a local APK build as proof that SystemUI starts, that the
legacy fragment renders correctly, or that the device recovery path works.
Those claims require the explicit human-gated device procedure documented at
the M1 handoff.

## Baseline build evidence

- Command: `./gradlew :app:assembleDebug`
- Result: `BUILD SUCCESSFUL` (35 actionable tasks)
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.1` (`versionCode=1`)
- compileSdk/targetSdk: 36 / 36
- APK metadata contains only the HyperOSP Java entry, API 100 static metadata,
  and `com.android.systemui` scope; no native library was packaged.
