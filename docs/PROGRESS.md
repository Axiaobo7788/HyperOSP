# HyperOSP progress

Last updated: 2026-09-27

## Current checkpoint

The M1 code and local build are complete and ready for the first human-gated
LSPosed device validation. No APK has been installed and no device, LSPosed,
SystemUI, Magisk, KernelSU, boot, or recovery action has been performed.

The working tree began as an unmodified JingMatrix/libxposed-example clone at
`87e9cb8`. The first implementation pass was completed while the HyperOSP
remote was temporarily unreadable from the shell. The original HyperOSP
documentation history was later fetched at `073db3b` and merged into this
feature branch with both histories preserved. No reset, force-push, or shared
history rewrite was used.

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

## Repository history status

`origin` points to `https://github.com/Axiaobo7788/HyperOSP.git` and `upstream`
points to JingMatrix/libxposed-example. The feature branch combines the
original HyperOSP documentation history with the retained template history and
the M1 implementation. It is intended to be published with a normal
fast-forward push to `origin/feat/aosp-qs-legacy-poc`.

## Original bootstrap plan (historical)

The remainder of this section is the M0 plan preserved from the original
HyperOSP documentation tip (`073db3b`). Its unchecked implementation items are
an archival snapshot and are superseded by the current checkpoint above.

Last updated: 2026-09-27.

### Historical phase

**M0 — Bootstrap and evidence capture**

Status at that revision: IN PROGRESS.

The repository exists and the research direction is validated. The next engineering milestone is a minimal modern-libxposed QS backend PoC.

## Completed research

- [x] Confirmed AOSP DocumentsUI can be restored on tested HyperOS build.
- [x] Confirmed HyperOS photo picker can be bypassed without replacing the MediaProvider APEX.
- [x] Confirmed AOSP legacy PhotoPicker remains present and usable.
- [x] Confirmed `use_control_panel=0` selects MIUI classic QS, not AOSP QS.
- [x] Confirmed `MiuiSystemUI.apk` includes Xiaomi QS, AOSP legacy QS, and AOSP Compose QS implementations.
- [x] Confirmed the APK includes providers / components for legacy and Compose AOSP QS.
- [x] Identified a narrow candidate hook path for replacing the requested QS fragment class.
- [x] Chosen architecture: LSPosed first; optional Magisk / KernelSU companion only for static resources or boot-time configuration.
- [x] Chosen project license: GPL-3.0-only.
- [x] Chosen modern libxposed example lineage as project scaffold.

## M0 repository bootstrap

- [x] Add project README.
- [x] Add persistent project memory.
- [x] Add agent operating contract.
- [x] Add progress tracking.
- [ ] Import / adapt the local `JingMatrix/libxposed-example` scaffold into HyperOSP.
- [ ] Rename package / namespace to `io.github.axiaobo7788.hyperosp`.
- [ ] Remove demo native code and example hooks unless needed.
- [ ] Preserve Apache-2.0 attribution for adapted template code.
- [ ] Confirm `./gradlew :app:assembleDebug` succeeds before adding the QS hook.

## M1 — AOSP legacy QS PoC

Goal: cause HyperOS SystemUI to instantiate `QSFragmentLegacy` instead of `MiuiQSFragment` with one narrow, defensive hook.

### Implementation tasks

- [ ] Scope module only to `com.android.systemui`.
- [ ] Add module load logging.
- [ ] Resolve:
  - [ ] `MiuiQSFragment`
  - [ ] `QSFragmentLegacy`
  - [ ] `FragmentHostManager$ExtensionFragmentManager`
- [ ] Resolve and validate `instantiateWithInjections` signature.
- [ ] Install one argument-rewrite hook.
- [ ] Rewrite only the exact MIUI QS class-name argument.
- [ ] No-op safely if target classes / method are absent.
- [ ] Build debug APK.
- [ ] Record exact compatibility checks and hook logs.

### Human verification checkpoint

The agent must stop before the first real-device activation.

Human actions required:

- enable module in LSPosed;
- confirm scope is only `com.android.systemui`;
- restart / reload SystemUI or reboot;
- report UI result and logs.

### M1 acceptance criteria

All of the following should be checked manually:

- [ ] SystemUI does not crash-loop.
- [ ] Notification shade opens.
- [ ] AOSP legacy QS renders.
- [ ] QS expansion / collapse works.
- [ ] Wi-Fi / Bluetooth / flashlight tiles render and react.
- [ ] Third-party TileService tiles still work.
- [ ] Brightness UI works.
- [ ] Media controls do not crash the shade.
- [ ] Lockscreen shade does not crash.
- [ ] Rotation / configuration change does not crash.
- [ ] Returning `use_control_panel` between Xiaomi modes does not corrupt state.
- [ ] Disabling HyperOSP restores stock behavior.

## M2 — Hardening

After M1 succeeds:

- [ ] Runtime build detection.
- [ ] Compatibility matrix by HyperOS / Android / device.
- [ ] Master enable switch.
- [ ] Feature-specific toggles.
- [ ] Crash-safe compatibility gate.
- [ ] Structured diagnostics export.
- [ ] CI build.
- [ ] Release signing strategy.

## M3 — Surface restoration modules

Candidates, in tentative order:

1. AOSP legacy Quick Settings.
2. AOSP Compose Quick Settings.
3. Photo picker routing.
4. Per-app file-picker routing.
5. Volume UI.
6. Notification shade behavior / visuals.
7. Lockscreen surfaces.
8. Optional static Pixel resources via root companion.

### Historical blockers

No repository-level blocker.

Real-device verification is intentionally gated behind human confirmation.

### Historical next action

Codex should bootstrap the local modern-libxposed scaffold, clean the example code, make the project build, then implement the defensive M1 hook on a feature branch.

Do not wait for another design discussion unless the source / API presents a concrete ambiguity that changes safety or architecture.
