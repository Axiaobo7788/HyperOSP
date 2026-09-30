# HyperOSP project memory

Last updated: 2026-09-30

This file records durable project facts. Transient build output and one-off
tool failures belong in `PROGRESS.md` instead.

## Verified target facts

- Target device: Xiaomi 15 Pro, codename `haotian`.
- Target software: HyperOS 3 based on Android 16.
- The inspected target SystemUI APK contains:
  - `com.android.systemui.qs.MiuiQSFragment`
  - `com.android.systemui.qs.QSFragmentLegacy`
  - `com.android.systemui.qs.composefragment.QSFragmentCompose`
  - `com.android.systemui.qs.QSPanel`
  - `com.android.systemui.qs.QuickQSPanel`
- M1 must attempt only `MiuiQSFragment` to `QSFragmentLegacy`. Compose QS is
  explicitly out of scope.
- Android 16 QPR2 AOSP source retains the candidate private, non-static method
  `FragmentHostManager.ExtensionFragmentManager#instantiateWithInjections`
  with `(Context, String, Bundle)` parameters and an `android.app.Fragment`
  return type. The v0.0.2 device run subsequently confirmed the compatible
  Xiaomi runtime target and successful interception.

## Verified runtime framework compatibility

- **Verified runtime:** 当前测试框架使用 Xposed/libxposed API101，API100
  HyperOSP 会被管理器自动禁用，因此 API100 路线已否决。
- M1 now uses `minApiVersion=101` and `targetApiVersion=101`. API 102 is not a
  migration target for this checkpoint.
- Because the manager disabled v0.0.1 before module execution, that attempt did
  not validate the hook point, class replacement, QS rendering, or SystemUI
  stability.

## Verified runtime: first API 101 M1 run

- HyperOSP API 101 loaded successfully in `com.android.systemui`.
- `MiuiQSFragment`, `QSFragmentLegacy`, and the compatible
  `ExtensionFragmentManager#instantiateWithInjections` target resolved.
- The exact `MiuiQSFragment -> QSFragmentLegacy` class-name rewrite executed.
- SystemUI remained alive; no `ClassCastException`, `InflateException`, fatal
  exception, or crash-loop attributable to this run was observed.
- With `settings system use_control_panel = 0`, the classic notification shade
  could not expand. During the attempted pull-down,
  `NotificationPanelExpandController` reported the panel invisible and its
  expansion height remained `0.0`; `NotificationHeaderExpandController`
  likewise remained at progress `0.0`.
- The run therefore proves fragment-request rewriting and process survival,
  but it does not prove that `QSFragmentLegacy` was completely bound into the
  HyperOS notification-shade expansion path.
- HyperCeiler and RestoreSplashScreen were also scoped to
  `com.android.systemui` during this run. RestoreSplashScreen emitted explicit
  `NullPointerException` and `NoSuchMethodError` failures. Those failures are
  not assigned to HyperOSP, but they make the run a contaminated baseline; the
  next diagnostic run should temporarily exclude other SystemUI modules.

## Verified runtime: second API 101 M1 run

- The v0.0.3 run again executed the exact
  `MiuiQSFragment -> QSFragmentLegacy` rewrite.
- `QSFragmentLegacy#onViewCreated` executed and initialized `mQsImpl` from
  `null` to `com.android.systemui.qs.QSImpl`.
- The runtime invoked `setPanelView`, `setCollapseExpandAction`,
  `setHeaderClickable`, `setOverscrolling`, `setInSplitShade`,
  `setListening(true)`, and `setQsExpansion`.
- `QuickSettingsControllerImpl.mQs` ultimately referenced the actual
  `QSFragmentLegacy` instance.
- `mMinExpansionHeight` and `mMaxExpansionHeight` had valid values (one
  observed pair was `372 / 2267`), and both `mExpansionEnabledPolicy` and
  `mExpansionEnabledAmbient` became `true` during the run.
- HyperOS exposes the OEM callback as
  `QuickSettingsControllerImpl$QsFragmentListener#onFragmentViewCreated(android.app.Fragment):void`,
  not the AOSP-style `(String, Fragment)` form assumed by v0.0.3's matcher.
- During each attempted pull-down the observed sequence was `panel_open`,
  `notification_panel_revealed`, `NotificationShade reportDrawFinished`, then
  about 100-300 ms later `notification_panel_hidden` and
  `NotificationShade View.INVISIBLE`. The shade therefore opens and draws,
  then is actively hidden.

## Verified runtime: v0.0.4 diagnostic run

- The exact `MiuiQSFragment -> QSFragmentLegacy` rewrite continued to execute.
- The HyperOS one-argument `QsFragmentListener(Fragment)` callback completed,
  and `QuickSettingsControllerImpl.mQs` still held that same
  `QSFragmentLegacy` instance.
- The live `NotificationPanelExpandController` class did **not** expose the
  assumed `setPanelVisible`, `startPanelVisibleAnimation`, or `panelVisible`
  field. That v0.0.4 investigation direction is rejected for this OEM build.
- Reflected collapse methods on `ShadeControllerImpl` existed and were called.
  `ShadeControllerSceneImpl` hooks installed but no invocation was observed in
  this run.
- Not every `notification_panel_hidden` event had a preceding observed
  `ShadeControllerImpl` collapse call. Explicit ShadeController collapse is
  therefore insufficient as a complete explanation.
- The v0.0.4 caller stack contained only HyperOSP/libxposed trampoline frames;
  it did not identify a real SystemUI caller.
- The repeated failure sequence was `panel_open`,
  `notification_panel_revealed`, `NotificationShade reportDrawFinished`, then
  roughly 150-500 ms later `notification_panel_hidden`.

## Verified runtime: v0.0.5 causality run

- The class rewrite still succeeded, and `QSFragmentLegacy` remained the
  controller's bound QS instance.
- During a failed interaction, the shade's actual expanded height reached
  roughly 2400 and its expanded fraction reached 1.0. This rejects the claim
  that HyperOS cannot host or fully expand this legacy backend.
- The live OEM end-motion path proceeded through an NPVC fling decision with
  `expand=false`, followed by `flingToHeight(... targetHeight=0)`.
- A call attributed at runtime to the R8 class
  `NotificationPanelViewControllerInjector$boostRunnable$1` also reached
  `NotificationPanelViewController.collapse()` and then the same zero-height
  fling route. Static DEX analysis below distinguishes the merged class's
  semantic roles; the class name alone is not a causal identity.
- `PanelInteractiveManager` exposed all three expected `ReadonlyStateFlow`
  instances: `controlCenterInteractive`, `notificationInteractive`, and
  `entirePanelTouchable`.

## Verified runtime: v0.0.6 external-touch timing

- In a normal failed pull-down, the last observed MOVE still had
  `mExpandedHeight` around 2176, well above 2000.
- Only a few milliseconds later, at entry to
  `NotificationPanelViewController$TouchHandler#onTouchEvent(ACTION_UP)`, both
  `mExpandedHeight` and `mExpandedFraction` were already zero.
- This reset precedes `endMotionEvent`, `isFalseTouch`, `fling$2`, and
  `flingToHeight`; those later zero-target events can therefore be downstream
  cleanup rather than the first cause.
- `isFalseTouch` returned `false` in an observed failing gesture. False-touch
  is not required for this failure.
- The observed external path is `StatusBarWindowView -> PhoneStatusBarView ->
  MiuiStatusBarTouchHandler -> MiuiShadeTouchHandlerImpl#handleExternalTouch ->
  NotificationPanelViewControllerInjector#handleExternalTouch ->
  NotificationPanelViewController$TouchHandler#onTouchEvent`.
- **Verified runtime/environment dependency:** the target device requires
  HyperCeiler's `system_control_center_unlock_old` behavior to expose MIUI
  classic mode. For this diagnostic, keep only that prerequisite enabled,
  disable other HyperCeiler SystemUI tweaks, and keep RestoreSplashScreen and
  other SystemUI modules disabled. HyperOSP does not yet implement an unlock
  shim.

## Rejected hypothesis and current hypothesis

- **Rejected by verified runtime:** `QSFragmentLegacy` failed to complete the
  standard AOSP lifecycle, delegate initialization, controller binding, or QS
  wiring.
- **Rejected by verified runtime:**
  `NotificationPanelExpandController#setPanelVisible`,
  `startPanelVisibleAnimation`, or a `panelVisible` field owns the observed
  close on this build.
- **Rejected by verified runtime:** Legacy QS cannot reach full shade
  expansion. The observed height/fraction reached approximately 2400 / 1.0.
- **Rejected by verified runtime:** end-motion/fling is necessarily the first
  reset cause. Expansion can already be zero when NPVC TouchHandler enters
  ACTION_UP.
- **Current highest-priority question:** which branch or synchronous write in
  the Xiaomi external-touch hand-off first changes expansion from above 2000
  to zero before NPVC begins ACTION_UP handling?
- **当前核心研究状态：**“Legacy QS backend 已成功运行并可达到 full
  expansion。当前 M1 blocker 已缩小到 Xiaomi external-touch ACTION_UP
  handoff。需要定位 MiuiShadeTouchHandlerImpl /
  NotificationPanelViewControllerInjector 之间哪个分支将 expandedHeight 从
  >2000 重置为 0。”

## Verified target APK DEX: v0.0.6 causality analysis

The locally supplied `MiuiSystemUI.apk` is an ignored, untracked analysis input.
Its SHA-256 is
`e1ef38a00753d5dbcd864ddf4c2d6a2fbb0e9aee2a0c438c0cc32e3153b2d3c7`.
It must not be committed or packaged.

- `NotificationPanelViewControllerInjector$boostRunnable$1` is an R8-merged
  `Runnable` with `$r8$classId` and `this$0` fields. Its `run()` roles are:
  class id 0 calls `BoostHelper.boostWithCpuFreq(2000L, panelView)`; class id 1
  calls `NotificationPanelViewController.collapse(1.0f, false)`; class id 2
  updates the dismiss view; the default role recomputes top padding.
- The Injector constructor creates and retains its field named `boostRunnable`
  with class id 0. `NotificationPanelViewControllerInjector$2#onAppearanceChanged`
  posts that CPU-boost Runnable with no delay only when appearance changed to
  false, animation is requested, and `bgHandler.hasCallbacks(boostRunnable)` is
  false. It never calls collapse.
- The class-id-1 collapse instance is created only in
  `NotificationPanelViewController#onEmptySpaceClick()`. In bar state 0/SHADE,
  when fold notifications are not being shown, it posts the temporary Runnable
  to `NotificationPanelView` without a delay. Its `run()` calls
  `collapse(1.0f,false)`, which leads through `fling$2(... expand=false)` to
  `flingToHeight(... targetHeight=0)`.
- No `removeCallbacks` or cancellation branch targets the retained class-id-0
  field or the transient class-id-1 instance in their verified scheduling
  paths. Nearby removals in the owner classes target other named Runnables.
- Neither the class-id-0 CPU boost route nor the class-id-1 empty-space collapse
  route contains a DEX dependency on `MiuiQSFragment`, `MiuiQS`,
  `useControlCenter`, or any of the three `PanelInteractiveManager` flows.
- HyperOS inlines the ordinary `flingExpands` decision into the static R8
  accessor
  `-$$Nest$mendMotionEvent(NotificationPanelViewController, MotionEvent, float,
  float, boolean):void`; there is no independently hookable runtime
  `flingExpands` method in the inspected class.
- The inlined decision checks unlocking/falsing, velocity versus
  `mMinVelocityPxPerSecond`, expanded fraction (0.5 outside keyguard, 0.8 on
  keyguard), the short-expansion allowance within 300 ms, velocity direction,
  an active QS expansion animator, and finally heads-up `mCollapseSnoozes`.
  It passes the resulting boolean to
  `fling$2(float,float,boolean,boolean):void`, which selects max panel distance
  for expansion or zero for collapse, then calls the five-argument
  `flingToHeight`.
- The same end-motion accessor has a tap/no-motion branch that calls
  `onEmptySpaceClick()`. This is a plausible bridge between a gesture session
  and the class-id-1 posted collapse, but runtime correlation is still pending.
- `NotificationPanelViewController$TouchHandler#onTouchEvent(MotionEvent)` is
  the concrete touch entry. It directly reaches
  `mNotifInjector.panelInteractiveManager`; if
  `controlCenterInteractive` is true under its other arbitration conditions,
  it logs that notification-panel expansion is not expected and returns false.
- `PanelInteractiveManager` stores `controlCenterInteractive`,
  `notificationInteractive`, and `entirePanelTouchable` as direct
  `ReadonlyStateFlow` fields. The instance path is
  `NPVC.mNotifInjector -> injector.panelInteractiveManager`; no Dagger/Lazy
  lookup, collection, or lambda invocation is required to read current values.

## Verified target APK DEX: v0.0.7 external-touch and writer analysis

The same ignored APK, now stored at `research/apk/MiuiSystemUI.apk`, was
re-hashed before analysis. Its SHA-256 remains
`e1ef38a00753d5dbcd864ddf4c2d6a2fbb0e9aee2a0c438c0cc32e3153b2d3c7`.

- The exact outer signature is
  `MiuiShadeTouchHandlerImpl#handleExternalTouch(MotionEvent, String,
  kotlin.jvm.functions.Function1):boolean`.
- Its normal notification route invokes
  `NotificationPanelViewControllerInjector#handleExternalTouch(event)` first.
  Only after that call returns does an UP/CANCEL clear outer ownership flags
  (`statusBarHandling`, `externalSource`, `statusBarBlocking`, and
  `shouldBlockPullDownEvent`). It does not directly write expansion fields or
  invoke `setExpandedHeight`, `setExpandedHeightInternal`, `collapse`,
  `instantCollapse`, or `resetViews`.
- Other outer branches can route to Control Center, block an event, or create a
  copied ACTION_CANCEL when Dynamic Island takes ownership. Those are real
  arbitration branches, but none directly writes NPVC expansion in this
  method. The three `PanelInteractiveManager` flows are not read here.
- The exact injector signature is
  `NotificationPanelViewControllerInjector#handleExternalTouch(MotionEvent):boolean`.
  DOWN sets `handlingExternalTouch`; each accepted event sets
  `currentTouchExternal` and `NPVC.mUseExternalTouch`, synchronously calls
  `TouchHandler#onTouchEvent(event)`, then clears `mUseExternalTouch` in a
  finally path. UP/CANCEL subsequently clears `handlingExternalTouch` and
  `currentTouchExternal`. It contains no direct expansion write or reset call.
- `NotificationPanelViewController#setExpandedHeight(float)` delegates to the
  OEM `NotificationPanelViewControllerInjector#setExpandedHeightInternal$1(float)`.
  In the ordinary non-heads-up path, that method preserves the requested value
  on keyguard; outside keyguard it substitutes max panel height when
  `NotificationPanelExpandController.visible` is true, otherwise zero.
- The clamp constructs
  `NotificationPanelViewController$$ExternalSyntheticLambda24(panel, value)`
  and passes it to `NotificationShadeWindowControllerImpl#batchApplyWindowLayoutParams`.
  That method calls `Runnable.run()` synchronously before applying window
  layout params.
- `NotificationPanelViewController$$ExternalSyntheticLambda24#run()` is the
  concrete method that directly writes `mExpandedHeight`, derives and writes
  `mExpandedFraction`, and continues expansion-state propagation. This is the
  first stable field-writer observation target. Static analysis makes the OEM
  clamp a strong candidate, but runtime evidence is still required to identify
  the call that performs the failing >2000-to-zero transition.

## Verified source: QS hand-off path

- Current AOSP `QuickSettingsControllerImpl.QsFragmentListener` assigns the
  callback fragment to `mQs`, performs panel/collapse/header/overscroll/split
  shade wiring, attaches related listeners, and calls `updateExpansion()`.
- Current AOSP `QSFragmentLegacy` stores a nullable `mQsImpl`; its view-created
  lifecycle initializes that delegate, while multiple QS setter methods only
  forward when the delegate is non-null.
- These AOSP facts justify observing callback completion, `mQs` identity,
  `mQsImpl` readiness, and expansion calls. They do not prove that Xiaomi's
  fork has identical bodies.

## Active hypotheses

- A call to the OEM height clamp between the final MOVE and NPVC ACTION_UP may
  see `expandHelper.visible=false`, substitute zero, and synchronously invoke
  the actual writer. This is a static candidate, not yet a verified runtime
  cause.
- Outer ownership arbitration, a synthesized CANCEL, or a call from another
  synchronous SystemUI path could reach the writer before the observed UP
  entry. The three external boundaries plus writer caller stack must establish
  the actual order.
- `PanelInteractiveManager` may turn false before or after expansion is cleared.
  Direct `getValue()` snapshots at all boundaries and the writer transition
  will distinguish correlation order without collecting or mutating flows.
- End-motion false-touch/fling and the class-id-1 empty-space collapse remain
  downstream correlation evidence, not the leading root-cause hypotheses.

## Repository and toolchain facts

- The local repository was cloned from JingMatrix/libxposed-example at commit
  `87e9cb8` and retains that history as provenance.
- The inherited template selected compileSdk/targetSdk 36, JDK 21, and Kotlin.
- HyperOSP v0.0.7 continues to compile against the formal Maven Central dependency
  `io.github.libxposed:api:101.0.0` as `compileOnly`; the API is supplied by the
  framework at runtime and is not packaged in the APK.
- API 101 entry classes have a no-argument `XposedModule()` constructor. The
  framework attaches its interface before calling `onModuleLoaded`, which now
  owns HyperOSP's process-level initialization.
- API 101 hooks implement `XposedInterface.Hooker#intercept(Chain)`. Chain
  arguments are immutable, so replacement uses a copied array and
  `chain.proceed(modifiedArgs)`.
- The published API 101.0.0 source and current LSPosed/CorePatch implementation
  agree on the no-argument lifecycle and HookBuilder/Chain interceptor model.
  HyperCeiler's current main branch targets API 102, so it was treated as a
  compatibility-pattern reference rather than the M1 compile ABI.
- HyperOSP's namespace and Kotlin package are
  `io.github.axiaobo7788.hyperosp`.
- M1's only static scope is `com.android.systemui`.

## v0.0.6 diagnostic safety contract

- Each `ACTION_DOWN` starts a module-owned `gesture#N`. MOVE and expanded-height
  logs are sampled by time/height thresholds; no MotionEvent or OEM field is
  changed.
- The static end-motion accessor, `isFalseTouch`, `fling$2`, `flingToHeight`,
  collapse overloads, and the two R8 Runnable roles are observed without
  modifying arguments, return values, exceptions, scheduling, or panel state.
- Caller collection scans the full Java stack, filters Thread/VMStack,
  libxposed/LSPosed, reflection, and HyperOSP frames, and prefers the first real
  `com.android.systemui` / `com.miui.systemui` frames.
- The three interactive StateFlows are reached through the direct field graph
  and read only through side-effect-free `getValue()`; they are never collected
  or mutated.
- Collapse logs use exactly `GESTURE_DECISION`, `XIAOMI_BOOST_RUNNABLE`, or
  `OTHER`. The historical label `XIAOMI_BOOST_RUNNABLE` denotes the requested
  class-id-1 diagnostic category; static analysis establishes that the actual
  Injector field called `boostRunnable` is class id 0 and is not a collapse.

## v0.0.7 diagnostic safety contract

- The sole behavior hook remains the exact fragment class-name substitution.
  New v0.0.7 hooks only read/log before and after one unchanged original call.
- Terminal UP/CANCEL is logged at the outer Xiaomi handler, Injector handler,
  and NPVC TouchHandler. A shared `gesture#N` and `downTime` correlate the
  nested boundaries.
- The actual synthetic writer emits `ZERO-CROSSING` once per gesture only when
  the real height field changes from above 100 px to at most 1 px. It records
  both the setter's original request and the writer's effective value, state,
  interaction flows, MotionEvent context, and filtered SystemUI callers
  without writing any field.
- The OEM height clamp is observed only on terminal/suspicious calls. End-motion,
  false-touch, fling, and target-height observations are one-per-gesture
  downstream summaries.
- No hook modifies a MotionEvent, argument, result, exception, height,
  fraction, ownership/tracking flag, StateFlow, collapse, or fling decision.

## Safety and validation boundary

- A missing class, changed signature, ambiguous overload, or hook exception
  must result in a `HyperOSP:` diagnostic and no behavior change.
- Reflection must discover the `String className` argument position from the
  compatible method signature rather than assuming an index.
- Under API 101, argument replacement is performed by copying `Chain.args`,
  changing only the discovered index, and invoking `chain.proceed(copy)`;
  non-matching calls invoke `chain.proceed()` unchanged.
- The hook builder uses `ExceptionMode.PROTECTIVE`, and HyperOSP catches its own
  pre-proceed inspection/rewrite failures. Exceptions from the original method
  invocation are not swallowed or retried, avoiding duplicate execution.
- The installed hook is guarded by an `AtomicBoolean` after the exact package
  and first-package checks, so each module instance makes at most one
  installation attempt.
- Local compilation verifies source, resource, and packaging consistency only.
  It does not verify LSPosed loading, OEM runtime behavior, UI correctness, or
  recovery behavior on a physical device.
- Any physical-device action is human-gated.

## Licensing

- HyperOSP-authored work uses `GPL-3.0-only`.
- Retained JingMatrix/libxposed-example material remains subject to Apache-2.0;
  attribution is recorded in `THIRD_PARTY_NOTICES.md`.

## Imported verified research from the original HyperOSP history

The following device and APK observations were preserved from the original
HyperOSP documentation history and remain distinct from the later local build
evidence above.

Last updated: 2026-09-27.

## Project intent

HyperOSP aims to keep HyperOS as the vendor / hardware / local-compatibility base while restoring AOSP / Pixel-like system UI surfaces where feasible.

It is not a ROM replacement project.

Core idea:

```text
HyperOS vendor stack + Xiaomi hardware support
                    +
       AOSP system UI implementations
```

Prefer redirecting HyperOS to AOSP code it already ships.

## Test environment

### Primary reverse-engineering device

- Device: Xiaomi 15 Pro
- Codename: `haotian`
- Current research branch: HyperOS 3 / Android 16
- Root available
- LSPosed available
- Work profile observed as Android user 12 ("壶中界")

Do not assume all findings apply to other HyperOS builds.

## Verified: file picker routing

HyperOS has a DeviceConfig key:

```text
namespace: securitycenter
key: hyper_refer_file_picker
```

On the tested build, setting it to `false` caused file selection to route to AOSP DocumentsUI.

Observed:

- LocalSend works with AOSP DocumentsUI.
- ChatGPT Android transiently froze once after the switch, then worked again; treat this as an unresolved intermittent issue, not a confirmed incompatibility.

Long-term goal: per-app routing rather than a global DeviceConfig switch.

## Verified: photo picker routing

`ACTION_PICK_IMAGES` handlers observed:

HyperOS handler:

```text
priority=1000
com.android.photopicker/.hyper.HyperMainActivity
```

AOSP legacy fallback:

```text
priority=100
com.android.providers.media.module/
com.android.providers.media.photopicker.PhotoPickerActivity
```

Important command behavior on this build:

- `pm disable-user --user 0 <component>` did **not** disable `HyperMainActivity`; output remained `enabled`.
- `pm disable --user 0 <component>` successfully disabled it.

After disabling only:

```text
com.android.photopicker/com.android.photopicker.hyper.HyperMainActivity
```

the resolved `PICK_IMAGES` activity became the AOSP legacy `PhotoPickerActivity`.

`dumpsys package com.android.photopicker` confirmed for user 0:

```text
disabledComponents:
  com.android.photopicker.PhotopickerUserSelectActivity
  com.android.photopicker.MainActivity
  com.android.photopicker.PhotopickerGetContentActivity
  com.android.photopicker.hyper.HyperMainActivity
```

For work-profile user 12, `HyperMainActivity` remained explicitly enabled at the time of inspection.

Do not enable the other modern PhotoPicker activities casually; Xiaomi ships them disabled intentionally on this build.

## Verified: HyperOS control-center switch

System settings observed:

```text
settings/system/use_control_panel = 1
settings/system/force_use_control_panel = 0
```

Changing `use_control_panel` from `1` to `0` switched:

```text
HyperOS new Control Center
          ->
MIUI classic Quick Settings
```

It did **not** restore AOSP QS.

Therefore:

```text
use_control_panel
```

is a Xiaomi-new-vs-Xiaomi-classic selector, not an AOSP backend selector.

## Verified APK facts: MiuiSystemUI

Source artifact analyzed:

```text
/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk
```

Uploaded artifact SHA-256:

```text
e1ef38a00753d5dbcd864ddf4c2d6a2fbb0e9aee2a0c438c0cc32e3153b2d3c7
```

APK contains three DEX files.

String / DEX inspection confirms presence of:

### Xiaomi QS

- `com.android.systemui.qs.MiuiQSFragment`
- `MiuiQSPanel`
- `MiuiQuickQSPanel`
- `MiuiQSFragmentComponent`

### AOSP legacy QS

- `com.android.systemui.qs.QSFragmentLegacy`
- `QSPanel`
- `QuickQSPanel`
- `QSPanelController`

### AOSP Compose / scene QS

- `com.android.systemui.qs.composefragment.QSFragmentCompose`
- `QuickSettingsScene`
- `QuickSettingsShade`
- `QuickSettingsTheme`
- `QSFragmentComposeViewModel`

### Dependency-injection / providers

Observed symbols include:

- `miuiQSFragmentProvider`
- `qSFragmentLegacyProvider`
- `qSFragmentComposeProvider`
- `QSFragmentComponentImpl`
- `MiuiQSFragmentComponentImpl`

This is stronger evidence than mere interface remnants: substantial AOSP QS implementation remains compiled into HyperOS SystemUI.

## APK-analysis finding: first QS PoC

Current APK analysis indicates the default shade path asks for `MiuiQSFragment`, while HyperOS still carries explicit legacy-AOSP QS compatibility code.

A narrow candidate hook is:

```text
FragmentHostManager$ExtensionFragmentManager.instantiateWithInjections(...)
```

with a class-name rewrite only when:

```text
com.android.systemui.qs.MiuiQSFragment
```

is requested.

Rewrite target:

```text
com.android.systemui.qs.QSFragmentLegacy
```

Status classification: **Verified APK/source analysis; not yet verified on a real device.**

## Why Legacy before Compose

Although `QSFragmentCompose` appears substantially present, the first PoC targets `QSFragmentLegacy` because Xiaomi's SystemUI contains explicit references / compatibility handling for the legacy implementation.

Compose is a later milestone after the legacy path proves that AOSP QS can be reattached safely.

## Relevant package paths

Observed:

```text
com.android.systemui
/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk

miui.systemui.plugin
/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk
```

Standard AOSP plugin intent queries for:

- `com.android.systemui.action.PLUGIN_QS`
- `PLUGIN_QS_FACTORY`
- `PLUGIN_OVERLAY`

returned no services on the tested build.

Conclusion: Xiaomi's SystemUI plugin architecture is not simply the public AOSP QS plugin fallback path.

## Public projects worth mining

- `JingMatrix/libxposed-example` — modern libxposed project base retained for
  scaffold provenance; HyperOSP itself now compiles against API 101.0.0.
- `ReChronoRain/HyperCeiler` — mature HyperOS SystemUI / plugin hooks and compatibility patterns.
- `YunZiA/HyperStar` — HyperOS control-center customization and plugin scope examples.

Searches did not reveal a ready-made public implementation that switches HyperOS `MiuiQSFragment` directly to AOSP `QSFragmentLegacy` / `QSFragmentCompose`.

## License

Project code: GPL-3.0-only.

The libxposed example lineage is Apache-2.0; preserve required notices for adapted code.
