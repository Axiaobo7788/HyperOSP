# HyperOSP progress

Last updated: 2026-09-30

## Current checkpoint

HyperOSP v0.0.6 is implemented as an observation-only M1 causality build. It
retains the device-verified fragment rewrite and replaces the broad v0.0.5
inventory with exact target-DEX paths: touch sessions, the static R8
end-motion accessor, `isFalseTouch`, `fling$2`, `flingToHeight`, collapse
overloads, direct `PanelInteractiveManager` StateFlow reads, and distinct R8
Runnable roles. Every diagnostic invokes the original exactly once and does
not modify arguments, results, exceptions, scheduling, or SystemUI state.

The v0.0.5 device run proved that the legacy backend reaches full shade
expansion (about 2400 px / fraction 1.0) before NPVC selects `expand=false` and
targets height zero. It also observed a collapse attributed to Xiaomi's
`boostRunnable$1` class. Static DEX analysis now shows that this is an R8 merged
class: the held `boostRunnable` field is class id 0 and only performs a CPU
boost; the class-id-1 collapse role is allocated in `onEmptySpaceClick()` and
posted without delay. v0.0.6 correlates those independent paths with
`gesture#N` identifiers.

The comprehensive build, lint, R8, metadata, entry, DEX-marker, exclusion, and
hash checks all pass locally. No APK installation, LSPosed change, device
command, or SystemUI restart was performed while preparing this diagnostic.

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
| Minimal HyperOSP module | Complete | v0.0.6/package identity, API 101 metadata, formal compile-only API dependency and SystemUI-only scope retained |
| API 101 migration | Complete | No-argument module entry, `onModuleLoaded`, `Hooker#intercept(Chain)`, protective HookBuilder and copied-argument proceed path implemented |
| M1 legacy QS hook | Runtime partially verified | Exact rewrite and full standard legacy-QS lifecycle/wiring succeeded; shade opens/draws but is actively hidden shortly afterward |
| v0.0.3 QS wiring diagnostics | Runtime complete | Delegate, wiring, final mQs, expansion bounds, and enabled-state evidence captured; missing-wiring hypothesis rejected |
| v0.0.4 collapse diagnostics | Runtime complete | Assumed NotificationPanelExpandController setter/field path rejected; ShadeController calls are not a complete cause; old caller filtering failed |
| v0.0.5 NPVC gesture diagnostics | Implementation complete | Runtime method inventory, actual height growth, end-motion state, original fling result, target height, filtered SystemUI callers, and PanelInteractiveManager inventory are observation-only |
| v0.0.5 causality run | Runtime complete | Full expansion reached; ordinary end-motion selected `expand=false` / target 0; an R8 merged class-id-1 collapse path also ran |
| v0.0.6 DEX causality analysis | Complete | CPU-boost field role and temporary empty-space collapse role separated; exact scheduler conditions and inlined fling decision recovered |
| v0.0.6 causality diagnostics | Implementation complete | Gesture ids, exact decision inputs, direct StateFlow reads, schedule checks, Runnable class ids, collapse origins, and filtered callers are observation-only |
| Local debug build | Complete | Clean debug build passes on JDK 21; metadata, scope, entry, version, DEX references, exclusions, and SHA-256 verified |
| Lint and release/R8 packaging | Complete | `lintDebug` reports no issues; minified release builds and rewritten `u0` entry is valid |
| API 100 compatibility | Rejected by runtime | API 101 manager automatically disabled v0.0.1 before module execution |
| First API 101 device run | Completed with functional blocker | Rewrite succeeded and SystemUI lived; shade expansion stayed at `0.0` |
| Clean v0.0.6 causality run | Human-gated | After local verification, use the debug APK with HyperOSP as the only SystemUI hook module and capture one controlled pull-down sequence |

## Validation boundary

Do not interpret v0.0.6 compilation or target-APK DEX analysis as proof of the
next live gesture's exact inputs. Runtime must still correlate the ordinary
gesture decision and the class-id-1 posted collapse by gesture id. It must also
show whether any interactive StateFlow is true at the relevant decision.

当前核心研究状态：“QSFragmentLegacy backend 已成功实例化、绑定并达到 full
shade expansion。M1 当前 blocker 已缩小到 NotificationPanelViewController 的
gesture/fling collapse decision，以及 Xiaomi
NotificationPanelViewControllerInjector boostRunnable 合并类中的独立 collapse
路径。”

## v0.0.6 local build evidence

- Fast source check: `./gradlew :app:compileDebugKotlin` — `BUILD SUCCESSFUL`.
- Required comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` in 1m 25s (89 actionable tasks; 86 executed,
  3 up-to-date); lint text report says `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.6` (`versionCode=6`)
- compileSdk/targetSdk: 36 / 36; JDK toolchain: 21
- Both APKs contain `minApiVersion=101`, `targetApiVersion=101`,
  `staticScope=true`, and exactly one scope line: `com.android.systemui`.
- Debug `java_init.list` names
  `io.github.axiaobo7788.hyperosp.HyperOSPModule`. R8 rewrites the release entry
  to `u0`; DEX inspection confirms `u0` is public, extends `XposedModule`, has a
  public no-argument constructor, and retains public `onModuleLoaded` and
  `onPackageLoaded` callbacks.
- Debug DEX contains all ten v0.0.6 diagnostic Hooker implementations and the
  gesture, end-motion, false-touch, fling, target-height, schedule-check,
  interactive-flow, merged-Runnable-role, collapse-origin, and caller markers.
  Release DEX retains the same diagnostic markers after R8.
- The diagnostic source has a single unchanged
  `proceedUnchanged -> Chain.proceed()` call. The separate behavioral fragment
  rewrite remains the only copied-argument `proceed(modifiedArguments)` path.
- Neither APK defines/packages libxposed, an API 100 stub, a legacy annotation
  callback, a native library, the removed `ShadeGestureDiagnostics`, or the
  local `MiuiSystemUI.apk`. The target APK is covered by root `.gitignore` and
  is not tracked.
- Debug APK:
  `app/build/outputs/apk/debug/app-debug.apk`
- Debug SHA-256:
  `1332bcee9eba3676e853585209fe4d70f2966ccd96eb1d85ccda90e60984154e`
- Release APK:
  `app/build/outputs/apk/release/app-release.apk`
- Release SHA-256:
  `602c11ff5e1d01c841857d11819841a99fa1e7f97f9225979992d036f15f560b`
- Release remains debug-signed for packaging/R8 verification and is not a
  production release artifact.

## v0.0.5 local build evidence

- Fast source check: `./gradlew :app:compileDebugKotlin` — `BUILD SUCCESSFUL`.
- Required comprehensive command:
  `./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease`
- Result: `BUILD SUCCESSFUL` in 21s (89 actionable tasks; 85 executed,
  4 up-to-date); lint reports `No issues found.`
- Package: `io.github.axiaobo7788.hyperosp`
- Version: `0.0.5` (`versionCode=5`)
- compileSdk/targetSdk: 36 / 36; JDK toolchain: 21
- Both APKs contain `minApiVersion=101`, `targetApiVersion=101`,
  `staticScope=true`, and exactly one scope line: `com.android.systemui`.
- Debug `java_init.list` names
  `io.github.axiaobo7788.hyperosp.HyperOSPModule`. R8 rewrites the release entry
  to `t0`; DEX inspection confirms `t0` is public, extends `XposedModule`, has a
  public no-argument constructor, and retains public `onModuleLoaded` and
  `onPackageLoaded` callbacks.
- Debug DEX contains the six v0.0.5 Hooker implementations for actual height,
  end-motion, fling decision/result, target height, interactive getter, and
  sparse collapse observation. All use the one unchanged
  `proceedUnchanged -> Chain.proceed()` path; the separate fragment rewrite
  retains its mutually exclusive copied-argument proceed path.
- Both builds retain NPVC inventory, height/end-motion/fling,
  `PanelInteractiveManager`, sparse collapse, and filtered-caller log markers.
  The rejected `panelVisible true -> false` and
  `startPanelVisibleAnimation` diagnostic markers are absent.
- Neither APK packages libxposed, an API 100 stub, a legacy annotation
  callback, or a native library.
- Debug APK:
  `app/build/outputs/apk/debug/app-debug.apk`
- Debug SHA-256:
  `391d3c019cc227d571507c34a34a7d743010ba384b769c1cbd90cb5ad6b94b68`
- Release APK:
  `app/build/outputs/apk/release/app-release.apk`
- Release SHA-256:
  `08aedc6eb1626b5c519097be4ae00ef7f49e88a31a5bb853139e901988758ce4`
- Release remains debug-signed for packaging/R8 verification and is not a
  production release artifact.

## v0.0.4 historical local build evidence

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
