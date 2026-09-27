# HyperOSP progress

Last updated: 2026-09-27

## Current checkpoint

HyperOSP v0.0.4 is compiled and statically verified as an observation-only M1
diagnostic build.
It retains the device-verified class-name rewrite, corrects the OEM
one-argument fragment-listener matcher, removes frame-adjacent legacy wiring
logs, and focuses on the reflected `panelVisible` transition and collapse call
chain. It never suppresses a collapse or writes panel state.

The v0.0.3 API 101 device run verified `QSFragmentLegacy#onViewCreated`,
`mQsImpl -> QSImpl`, every expected standard AOSP wiring call, final controller
`mQs`, valid expansion bounds, and enabled policy/ambient state. The shade
opened and drew, then was hidden and made invisible about 100-300 ms later.
Consequently, incomplete legacy-QS wiring is rejected; the active blocker is
now more likely Xiaomi's notification-panel visibility/collapse arbitration.
The APK is ready for a human-gated diagnostic run; no APK installation,
LSPosed change, device command, or SystemUI restart was performed while
preparing it.

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
| Minimal HyperOSP module | Complete | v0.0.4/package identity, API 101 metadata, formal compile-only API dependency and SystemUI-only scope retained |
| API 101 migration | Complete | No-argument module entry, `onModuleLoaded`, `Hooker#intercept(Chain)`, protective HookBuilder and copied-argument proceed path implemented |
| M1 legacy QS hook | Runtime partially verified | Exact rewrite and full standard legacy-QS lifecycle/wiring succeeded; shade opens/draws but is actively hidden shortly afterward |
| v0.0.3 QS wiring diagnostics | Runtime complete | Delegate, wiring, final mQs, expansion bounds, and enabled-state evidence captured; missing-wiring hypothesis rejected |
| v0.0.4 collapse diagnostics | Implementation complete | OEM listener matcher fixed; panel transition stack, animation caller, reflected collapse-method hooks, and arbitration inventory are observation-only |
| Local debug build | Complete | Clean debug build passes on JDK 21; metadata, scope, entry, version, DEX references, and SHA-256 verified |
| Lint and release/R8 packaging | Complete | `lintDebug` reports no issues; minified release builds and its rewritten `t0` entry is valid |
| API 100 compatibility | Rejected by runtime | API 101 manager automatically disabled v0.0.1 before module execution |
| First API 101 device run | Completed with functional blocker | Rewrite succeeded and SystemUI lived; shade expansion stayed at `0.0` |
| Clean v0.0.4 diagnostic run | Human-gated | Temporarily exclude other SystemUI modules, then capture the true-to-false stack and reflected collapse-call sequence from one pull-down |

## Validation boundary

Do not interpret v0.0.4 compilation as proof that its reflected visibility
setter or collapse methods resolve on the OEM build. The complete legacy-QS
wiring facts above are device-verified. The still-open question is which live
caller changes `panelVisible` from true to false and whether the suspicious
Xiaomi control-center arbitration state appears in that path.

## v0.0.4 local build evidence

- Fast source check: `./gradlew :app:compileDebugKotlin` — `BUILD SUCCESSFUL`.
- Required comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` in 1m 2s (89 actionable tasks; 86 executed,
  3 up-to-date); lint reports `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.4` (`versionCode=4`)
- compileSdk/targetSdk: 36 / 36; JDK toolchain: 21
- Both APKs contain `minApiVersion=101`, `targetApiVersion=101`,
  `staticScope=true`, and exactly one scope line: `com.android.systemui`.
- Debug `java_init.list` names
  `io.github.axiaobo7788.hyperosp.HyperOSPModule`. R8 rewrites the release entry
  to `t0`; DEX inspection confirms `t0` is public, extends `XposedModule`, has a
  public no-argument constructor, and retains `onModuleLoaded` and
  `onPackageLoaded`.
- DEX inspection confirms the rewrite hook and all four v0.0.4 diagnostic
  interceptor classes implement `XposedInterface.Hooker`. Each diagnostic
  interceptor contains one unchanged `Chain.proceed()` call. Only the original
  mutually exclusive rewrite path also contains `proceed(Object[])`.
- Both DEX builds retain `onFragmentViewCreated`, `setPanelVisible`,
  `startPanelVisibleAnimation`, `collapseShade`, `animateCollapseShade`,
  `instantCollapseShade`, `collapsePanels`, and the transition log marker.
- The removed high-frequency diagnostic targets `updateExpansion`,
  `setExpansionHeight`, and `setQsExpansion` are absent from both APK DEX string
  inventories.
- Neither APK packages the libxposed compile-only implementation, an API 100
  stub, a legacy annotation callback, or a native library.
- Debug APK:
  `app/build/outputs/apk/debug/app-debug.apk`
- Debug SHA-256:
  `55e3abb3562efb2debe2c47756bcfc2f636ebf326465f6ab8be016103d4f7f17`
- Release APK:
  `app/build/outputs/apk/release/app-release.apk`
- Release SHA-256:
  `c6cfbc05874d30e552b0313059e00db495788a713f758ecc530a91b9ba1fdcda`
- Release remains debug-signed for packaging/R8 verification and is not a
  production release artifact.

## v0.0.3 final local build evidence

- Required comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` in 53s (89 actionable tasks; 86 executed,
  3 up-to-date); lint reports `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.3` (`versionCode=3`)
- compileSdk/targetSdk: 36 / 36; JDK toolchain: 21
- Both APKs contain `minApiVersion=101`, `targetApiVersion=101`,
  `staticScope=true`, and exactly one scope line: `com.android.systemui`.
- Debug `java_init.list` names
  `io.github.axiaobo7788.hyperosp.HyperOSPModule`. R8 rewrites the release entry
  to `n0`; DEX inspection confirms `n0` exists, extends `XposedModule`, has a
  public no-argument constructor, and retains the lifecycle callbacks.
- DEX inspection confirms the rewrite and all three diagnostic interceptor
  types implement `XposedInterface.Hooker`. Each diagnostic interceptor has one
  unchanged `Chain.proceed()` call; only the original rewrite interceptor also
  contains its mutually exclusive modified-argument `proceed(Object[])` path.
- Release DEX retains the diagnostic targets and log strings, including
  `onFragmentViewCreated`, `setExpansionHeight`, `setQsExpansion`, `mQsImpl`,
  and `mQsSameFragment`.
- Neither APK packages the libxposed compile-only implementation, an API 100
  stub, a legacy annotation callback, or a native library.
- Debug APK:
  `app/build/outputs/apk/debug/app-debug.apk`
- Debug SHA-256:
  `1da8cf6bc98bee6a7136676ea57389bdee229573b510768060fd1bec39e36229`
- Release APK:
  `app/build/outputs/apk/release/app-release.apk`
- Release SHA-256:
  `d94e2ddee50304cdb80821adab84adf858575913aaffcc3c70e73f6766b9ba73`
- Release remains debug-signed for packaging/R8 verification and is not a
  production release artifact.

## v0.0.2 historical local build evidence

- Bootstrap command: `./gradlew :app:assembleDebug` — passed against the formal
  API 101 dependency.
- Required comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` (89 actionable tasks; 86 executed, 3 up-to-date);
  lint reports
  `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.2` (`versionCode=2`)
- compileSdk/targetSdk: 36 / 36
- Dependency: formal `compileOnly("io.github.libxposed:api:101.0.0")` from Maven
  Central; the repository-local API 100 stub is deleted.
- Both APKs contain `minApiVersion=101`, `targetApiVersion=101`,
  `staticScope=true`, and exactly one scope line: `com.android.systemui`.
- The compile-only API is not defined or packaged in either APK; DEX contains
  runtime references to the API 101 no-argument `XposedModule`,
  `XposedInterface.Hooker`, `XposedInterface.Chain`, HookBuilder/intercept,
  `ExceptionMode.PROTECTIVE`, and both `Chain.proceed` forms.
- No `XposedHooker`, `BeforeInvocation`, `AfterInvocation`,
  `BeforeHookCallback`, or `AfterHookCallback` reference exists in either APK.
- Debug `java_init.list` names
  `io.github.axiaobo7788.hyperosp.HyperOSPModule`. R8 rewrites the release entry
  to `l`; DEX inspection confirms that `l` exists, extends `XposedModule`, has a
  public no-argument constructor, and retains `onModuleLoaded` and
  `onPackageLoaded`.
- The R8 hooker class implements `XposedInterface.Hooker`; its `intercept`
  bytecode calls unchanged `Chain.proceed()` or modified-argument
  `Chain.proceed(Object[])` exactly once.
- Neither APK contains a native library.
- Debug APK SHA-256:
  `e51f88152eb9bebdf9d55bd0dcf7a0cb3955a80c6d6ea93bfedf2fba6eeda229`
- Release APK SHA-256:
  `a8f653eb0f543e05766973111badac015a89446321fd2e0c78e43756a2a3cb35`
- Release is signed with the debug signing configuration as a packaging/R8
  verification artifact, not as a production release.

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
