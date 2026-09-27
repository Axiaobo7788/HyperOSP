# Progress

Last updated: 2026-09-27.

## Current phase

**M0 — Bootstrap and evidence capture**

Status: IN PROGRESS.

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

## Current blockers

No repository-level blocker.

Real-device verification is intentionally gated behind human confirmation.

## Next autonomous action

Codex should bootstrap the local modern-libxposed scaffold, clean the example code, make the project build, then implement the defensive M1 hook on a feature branch.

Do not wait for another design discussion unless the source / API presents a concrete ambiguity that changes safety or architecture.
