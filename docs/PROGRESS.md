# HyperOSP progress

Last updated: 2026-09-27

## Current checkpoint

The M1 code and local build are complete and ready for the first human-gated
LSPosed device validation. No APK has been installed and no device, LSPosed,
SystemUI, Magisk, KernelSU, boot, or recovery action has been performed.

The working tree began as an unmodified JingMatrix/libxposed-example clone at
`87e9cb8`. The HyperOSP remote repository was not readable from the current
unauthenticated shell/browser environment, and no HyperOSP document objects
were present locally. The repository context was therefore bootstrapped from
the explicit project handoff constraints without resetting or discarding the
template history.

## Milestones

| Milestone | State | Evidence / remaining work |
| --- | --- | --- |
| Safe repository bootstrap | Complete | Template history preserved; `origin` is HyperOSP, `upstream` is JingMatrix, work is on `feat/aosp-qs-legacy-poc` |
| Minimal HyperOSP module | Complete | UI/service/native demos removed; package, app identity, metadata, API 100 and SystemUI-only scope verified in APK |
| M1 legacy QS hook | Locally complete | Exact package/first-load gates, class probes, non-ambiguous signature validation, reflected String index and guarded replacement implemented |
| Local debug build | Complete | Clean debug build passes on JDK 21; APK metadata and dex references inspected |
| Lint and release/R8 packaging | Complete | `lintDebug`, minified `assembleRelease`, metadata rewriting and runtime hook annotations verified |
| Physical-device LSPosed validation | Human-gated | No installation or device action has been performed |

## Validation boundary

Do not interpret a local APK build as proof that SystemUI starts, that the
legacy fragment renders correctly, or that the device recovery path works.
Those claims require the explicit human-gated device procedure documented at
the M1 handoff.

## Final local build evidence

- Required command: `./gradlew :app:assembleDebug` — passed
- Final comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` (89 actionable tasks); lint reports
  `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.1` (`versionCode=1`)
- compileSdk/targetSdk: 36 / 36
- APK metadata contains only the HyperOSP Java entry, API 100 static metadata,
  and `com.android.systemui` scope; no native library was packaged.
- The compile-only libxposed stub is absent from the APK; dex entries contain
  only runtime references to API 100.
- R8 rewrites `java_init.list` to the obfuscated entry and retains runtime
  `XposedHooker`, `BeforeInvocation`, and `AfterInvocation` annotations.
- Debug APK SHA-256:
  `df20327f72322478aa24ff6ad756bf320e9a978a9c0eaa690dea2359bc654509`
- Release-check APK SHA-256:
  `229ced6d7ada96394f2e7c7ad8df8b38f6d16824a56aa57e7649cacb8fd0323a`

## Remote status

`origin` points to `https://github.com/Axiaobo7788/HyperOSP.git` and `upstream`
points to JingMatrix/libxposed-example. The local shell has neither usable
GitHub HTTPS credentials nor an SSH key, so the HyperOSP remote could not be
fetched and these local commits have not been pushed. No force-push, history
rewrite, or destructive reset was used.
