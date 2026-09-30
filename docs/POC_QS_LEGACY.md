# M1: AOSP legacy Quick Settings proof of concept

## Goal

Within the first package-load callback for the exact
`com.android.systemui` package, replace only the requested fragment class name

```text
com.android.systemui.qs.MiuiQSFragment
```

with

```text
com.android.systemui.qs.QSFragmentLegacy
```

No other fragment request may be changed. In particular, M1 does not select
`QSFragmentCompose`.

## Final local hook point

M1 uses:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
    instantiateWithInjections(Context, String, Bundle)
```

Android 16 QPR2 AOSP source confirms that this method is the final step used by
the extension manager before consulting the injection map or falling back to
platform `Fragment.instantiate`:
https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/packages/SystemUI/src/com/android/systemui/fragments/FragmentHostManager.java

The v0.0.2 device run confirmed that Xiaomi's runtime exposes a compatible
target and that the rewrite interceptor executes. HyperOSP still resolves the
nested class from SystemUI's class loader, enumerates methods named
`instantiateWithInjections`, and accepts exactly one compatible,
non-ambiguous candidate. The method must be non-static, return exact type
`android.app.Fragment`, and contain exactly one `android.content.Context`, one
`java.lang.String`, and one `android.os.Bundle` parameter. The `String`
position is derived from the reflected parameter list and retained for the
hook callback; it is never assumed from the signature text above.

## Installation algorithm

1. In API 101 `onModuleLoaded`, record the process and log module load with the
   `HyperOSP:` prefix. The entry class has a no-argument constructor and does no
   pre-attach initialization.
2. Ignore every package except the exact `com.android.systemui` package.
3. Log package and process, then ignore non-first package-load callbacks.
4. Probe `MiuiQSFragment` and `QSFragmentLegacy` with the supplied class loader
   without initializing them. If either is missing, log and return.
5. Resolve `FragmentHostManager$ExtensionFragmentManager`; if missing, log and
   return.
6. Find one and only one signature-compatible
   `instantiateWithInjections` method and derive its sole `String` argument
   index. If the method is missing or ambiguous, log and return.
7. Install one API 101 `XposedInterface.Hooker` through
   `hook(method).setExceptionMode(PROTECTIVE).intercept(hooker)`.
8. In `intercept(Chain)`, bounds-check the discovered index and compare the
   argument safely. For an exact `MiuiQSFragment` match, copy the immutable
   Chain arguments, replace only that copied element, and return
   `chain.proceed(modifiedArgs)`. All other calls return `chain.proceed()`.
9. Catch and log every HyperOSP-owned failure so compatibility drift produces a
   no-op rather than a deliberate SystemUI crash.

## Verified runtime: v0.0.2

The first API 101 device run established all of the following:

- the API 101 module loaded;
- both fragment classes and the hook target resolved;
- the exact `MiuiQSFragment -> QSFragmentLegacy` replacement executed;
- SystemUI remained alive without an observed `ClassCastException`,
  `InflateException`, fatal exception, or crash-loop;
- in MIUI classic mode (`use_control_panel=0`), the notification shade could
  not expand;
- during an attempted pull-down, panel visibility remained false and expansion
  remained `0.0`.

The rewrite is therefore no longer the immediate unknown. The unresolved
boundary is the hand-off from the instantiated legacy fragment into HyperOS's
shade/expansion controllers.

This run was not a clean module baseline: HyperCeiler and RestoreSplashScreen
were also scoped to SystemUI, and RestoreSplashScreen produced explicit
`NullPointerException` and `NoSuchMethodError` logs. The next run should
temporarily exclude other SystemUI-scoped modules before interpreting the
diagnostic sequence.

## v0.0.3 observation-only diagnostics

v0.0.3 retains the one behavioral hook above without changing its target or
replacement. It adds optional API 101 interceptors that only observe and log:

1. `QuickSettingsControllerImpl$QsFragmentListener#onFragmentViewCreated` entry
   and normal completion, including tag, actual fragment identity, controller
   `mQs` identity, and whether `mQs` is the same legacy-fragment instance;
2. `QSFragmentLegacy#onViewCreated`, including whether its reflected `mQsImpl`
   delegate is null before and after lifecycle initialization;
3. calls and normal completion for `setPanelView`,
   `setCollapseExpandAction`, `setHeaderClickable`, `setOverscrolling`,
   `setInSplitShade`, `setListening`, and `setQsExpansion`, together with the
   delegate identity;
4. `QuickSettingsControllerImpl#setListening`, `setExpansionHeight`, and
   `updateExpansion`, together with `mQs` and whichever known expansion-state
   fields exist on the runtime class;
5. the runtime superclass/interface relationships of `MiuiQSFragment` and
   `QSFragmentLegacy`, including any discoverable Xiaomi-specific `MiuiQS`
   interface candidate.

Each diagnostic target is resolved by a compatible signature. A missing,
changed, or ambiguous target logs a diagnostic no-op without preventing the
original class-name rewrite. High-frequency expansion calls log an initial
bounded sample and then every 30th call. Diagnostic code never changes
arguments, receivers, or results; every original method is called exactly
once. If an original SystemUI method throws, HyperOSP records that observation
and rethrows the same exception unchanged.

The AOSP source rationale is narrow: its listener assigns `mQs`, performs the
listed wiring, and ends with `updateExpansion()`, while AOSP
`QSFragmentLegacy` initializes a nullable `mQsImpl` during `onViewCreated` and
only forwards several setters when that delegate exists. Xiaomi-specific
method bodies and any `instanceof MiuiQS` branch remain unverified until the
device logs supply evidence.

## Verified runtime: v0.0.3 diagnostics

The second API 101 device run established that the class rewrite is followed
by a complete standard legacy-QS initialization path:

- `QSFragmentLegacy#onViewCreated` ran;
- `mQsImpl` changed from `null` to `QSImpl`;
- every observed panel/collapse/header/overscroll/split/listening/expansion
  wiring method ran;
- `QuickSettingsControllerImpl.mQs` held `QSFragmentLegacy`;
- valid minimum and maximum expansion heights were established; and
- both observed expansion-enable policy fields became `true`.

This rejects the v0.0.3 working hypothesis that the legacy fragment failed to
complete normal AOSP wiring. It also revealed an OEM signature difference:

```text
QuickSettingsControllerImpl$QsFragmentListener
    onFragmentViewCreated(android.app.Fragment): void
```

The panel instead opens and draws, then becomes hidden/invisible about
100-300 ms later. The current diagnostic boundary is therefore the Xiaomi
notification-panel visibility/collapse arbitration path.

## v0.0.4 observation-only collapse diagnostics

v0.0.4 keeps the sole behavioral fragment-name rewrite unchanged and makes no
QS wiring modification. Its diagnostics are deliberately low-volume:

1. accept exactly one compatible OEM one-argument or AOSP two-argument
   `onFragmentViewCreated` callback and observe final `mQs` identity;
2. resolve `NotificationPanelExpandController` through SystemUI's ClassLoader,
   require an exact `void setPanelVisible(boolean)` method plus an accessible
   boolean `panelVisible`/`mPanelVisible` field, and otherwise no-op;
3. after the original setter returns, emit one rate-limited event only when
   the reflected field actually changed from `true` to `false`; only that event
   includes a compact Java caller stack;
4. if reflection finds one compatible `startPanelVisibleAnimation` method with
   exactly one boolean direction input, log its show/hide direction and a
   short caller summary;
5. search a bounded set of SystemUI/AOSP owner classes for concrete reflected
   methods named `collapseShade`, `animateCollapseShade`,
   `instantCollapseShade`, or `collapsePanels`; print each full runtime
   signature before installing a read-only interceptor;
6. inventory panel-controller fields/methods whose names or types mention
   `controlCenterInteractive`, `useControlCenter`, panel interactivity,
   `MiuiQS`, or panel visibility; and
7. remove the v0.0.3 frame-adjacent `updateExpansion`, `setExpansionHeight`,
   and `setQsExpansion` sampling hooks.

Every diagnostic interceptor calls the original exactly once with the original
receiver and arguments, returns its unmodified result, and rethrows an original
SystemUI exception unchanged. Missing fields/classes, ambiguous signatures,
reflection failures, and hook-install failures are logged no-ops.

The inspected APK contains the suspicious strings
`controlCenterInteractive, not excepted notification panel expand.` and
`not excepted notification panel expand.`, but the APK itself is not currently
available in the local workspace and public sources do not contain Xiaomi's
body. Their precise owning method and predicate path remain unverified. v0.0.4
is intended to identify that live path from the `true -> false` caller stack
without guessing a DEX signature or changing behavior.

## Verified runtime: v0.0.4 diagnostics

The third API 101 device run rejected the central v0.0.4 assumption:

- `NotificationPanelExpandController` has no live `setPanelVisible`,
  `startPanelVisibleAnimation`, or `panelVisible` field on this build;
- `ShadeControllerImpl` collapse methods exist and do run;
- the installed `ShadeControllerSceneImpl` hooks did not run in the captured
  interaction;
- some `notification_panel_hidden` events had no preceding observed
  `ShadeControllerImpl` collapse call; and
- the caller stack captured from inside the API 101 interceptor stopped at
  HyperOSP/libxposed trampoline frames.

The rewrite, OEM one-argument listener, and final controller `mQs` binding all
continued to succeed. The repeated failure sequence was `panel_open`,
`notification_panel_revealed`, `NotificationShade reportDrawFinished`, then
roughly 150-500 ms later `notification_panel_hidden`.

The `NotificationPanelExpandController` visibility-setter direction is now
rejected. Explicit `ShadeController` collapse remains secondary correlation
evidence, not the leading cause.

当前核心假设：“Legacy QS 已实例化和绑定；failed pull-down 更像
NotificationPanelViewController 手势结束/fling 判定回弹，而非
QuickSettings wiring 或显式 ShadeController collapse。”

## v0.0.5 observation-only gesture diagnostics

v0.0.5 leaves the fragment rewrite and QS binding observation unchanged. Its
primary target is the reflected live class:

```text
com.android.systemui.shade.NotificationPanelViewController
```

It inventories and observes these exact method names without guessing an OEM
overload:

- `setExpandedHeight` and `setExpandedHeightInternal`: accept only reflected
  `void` methods with one primitive float/double input. Logging is sampled,
  while a module-owned tracker records whether the **actual reflected expanded
  height field** grew from zero during the gesture. The field is never written.
- `endMotionEvent`: log indexed arguments, readable expanded-height/fraction
  state, the gesture summary, available `PanelInteractiveManager` state, and
  filtered real SystemUI callers before and after normal completion.
- `flingExpands`: log indexed inputs before execution and the original boolean
  result after execution. The result is not replaced.
- `flingToHeight`: log every indexed input and, only for the exact AOSP
  descriptor `(float, boolean, float, float, boolean): void`, label index 2 as
  `targetHeight` and explicitly report whether it is zero. A different OEM
  descriptor is logged with an unresolved target rather than guessed.

Android 16 AOSP source confirms the above five-argument `flingToHeight` shape
and the `endMotionEvent -> flingExpands -> flingToHeight` decision flow. That
source guides the strict descriptor gate but does not establish Xiaomi's live
signature; the v0.0.5 runtime inventory must do that.

The diagnostic also inventories
`com.miui.systemui.shade.PanelInteractiveManager` members named
`controlCenterInteractive`, `notificationInteractive`, and
`entirePanelTouchable`. It classifies fields and return types as boolean,
StateFlow, `Function0`/lambda, or other object; hooks only reflected no-argument
property-like methods; reads StateFlow through `getValue()`; and never invokes
an OEM lambda. Exact generated `$property$1` classes are inventoried by name.

Caller capture scans past the interceptor instead of truncating the first few
frames. It removes Thread/VMStack, reflection, libxposed/LSPosed, and HyperOSP
frames, then prefers up to ten `com.android.systemui` or
`com.miui.systemui` frames. `ShadeControllerImpl` and
`ShadeControllerSceneImpl` collapse observations remain at two initial logs
and every fiftieth call.

No v0.0.5 hook forces expansion, changes `flingExpands`, suppresses collapse,
writes panel state, invokes a property lambda, or selects Compose QS.

## Verified runtime: v0.0.5 diagnostics

The next device run resolved the broad v0.0.5 questions:

- the rewrite and final `QSFragmentLegacy` binding still succeeded;
- actual shade height reached roughly 2400 and expanded fraction reached 1.0,
  proving the legacy backend can reach full expansion on this host;
- the ordinary NPVC end-motion route selected `expand=false` and reached
  `flingToHeight(... targetHeight=0)`; and
- a collapse attributed to the R8 class
  `NotificationPanelViewControllerInjector$boostRunnable$1` also reached the
  zero-height route.

The remaining task is causal, not structural: explain the inputs that produce
the ordinary false decision, and determine whether the asynchronous Runnable
collapse belongs to the same gesture.

## v0.0.6 target-DEX causality analysis

Static analysis of the exact ignored local `MiuiSystemUI.apk` established that
the suspicious class name is an R8 merge artifact:

```text
NotificationPanelViewControllerInjector$boostRunnable$1.run()
  classId=0 -> BoostHelper.boostWithCpuFreq(2000 ms, panelView)
  classId=1 -> NotificationPanelViewController.collapse(1.0f, false)
  classId=2 -> dismissViewController.updateShow()
  default   -> top-padding update
```

The Injector's field named `boostRunnable` is constructed with class id 0. Its
only verified scheduler is
`NotificationPanelViewControllerInjector$2#onAppearanceChanged(boolean,
boolean)`: on an animated transition to not-appeared, it uses
`bgHandler.post()` with no delay if the Runnable is not already queued. That
path is performance work and does not collapse the panel.

The class-id-1 collapse role is instead created inside
`NotificationPanelViewController#onEmptySpaceClick()`. For bar state 0/SHADE,
unless fold notifications are currently displayed, the method posts the
temporary instance to the panel view without delay. Its call to
`collapse(1.0f,false)` synchronously selects `expand=false` and target height
zero. Neither verified scheduler contains a cancellation/removal path for its
class-id-0 or class-id-1 Runnable; nearby `removeCallbacks` calls target other
named Runnables. Neither role statically references the selected QS fragment,
control-center setting, or `PanelInteractiveManager` flows.

The ordinary fling decision is also OEM/R8-shaped. The target contains no
standalone hookable `flingExpands` method; the boolean is inlined in:

```text
NotificationPanelViewController
  -$$Nest$mendMotionEvent(
      NotificationPanelViewController,
      MotionEvent,
      float,
      float,
      boolean
  ): void
```

The DEX decision uses unlocking/falsing, minimum velocity, direction, expanded
fraction thresholds (0.5 normally / 0.8 on keyguard), the allowed-small-
expansion flag within 300 ms, an active QS expansion animator, and heads-up
collapse-snooze state. It passes the final boolean to
`fling$2(float,float,boolean,boolean)`, which selects max distance or zero and
then calls `flingToHeight(float,boolean,float,float,boolean)`.

The same end-motion body can call `onEmptySpaceClick()` on its tap/no-motion
branch. That is a plausible connection to the class-id-1 post but remains a
runtime hypothesis until gesture-id logs correlate it.

## v0.0.6 observation-only causality diagnostics

v0.0.6 resolves the exact live descriptors above and installs independent,
fail-safe observations for:

1. `TouchHandler#onTouchEvent(MotionEvent)`, assigning a new `gesture#N` on
   `ACTION_DOWN` and retaining it through sampled MOVE, UP/CANCEL, fling, and
   asynchronous diagnostics;
2. the static end-motion accessor and `isFalseTouch(float,float,int)`, including
   full safe-to-read decision state and the original false-touch result;
3. `fling$2(float,float,boolean,boolean)` and the five-argument
   `flingToHeight`, including the actual expand boolean, target, velocity, and
   collapse origin;
4. exact collapse overloads, `onEmptySpaceClick()`, and the merged Runnable's
   `run()`, distinguishing class id 0 CPU boost from class id 1 empty-space
   collapse;
5. `Injector$2#onAppearanceChanged(boolean,boolean)`, including the CPU-boost
   post condition and queue state; and
6. direct, read-only values from
   `NPVC.mNotifInjector.panelInteractiveManager` for
   `controlCenterInteractive`, `notificationInteractive`, and
   `entirePanelTouchable`.

Collapse observations carry exactly one of `GESTURE_DECISION`,
`XIAOMI_BOOST_RUNNABLE`, or `OTHER`. The requested
`XIAOMI_BOOST_RUNNABLE` label is retained for runtime-log compatibility, but it
means the R8 class-id-1 category—not the actual class-id-0 field named
`boostRunnable`.

MOVE/height events are sampled. StateFlow values are read only with
`getValue()`; no collector is started. Every interceptor proceeds once with the
original arguments and result, does not alter exceptions, and cannot force
expansion, suppress collapse, skip a Runnable, or mutate OEM state.

## Required diagnostics

The module must emit `HyperOSP:`-prefixed diagnostics for:

- module loaded
- process/package
- each source/target fragment class found or missing
- hook target found or missing
- hook installed
- an actual class-name replacement
- any caught exception

## Local validation status

- Target classes were verified in the target APK before this bootstrap.
- The upstream Android 16 method and flow are source-confirmed.
- API 100 v0.0.1 was disabled by the API 101 test framework before its module
  logic could run; the API 100 route is rejected.
- API 101 hook installation, replacement execution, and SystemUI survival are
  device-verified for v0.0.2; usable QS rendering and expansion are not.
- v0.0.3 callback, binding, delegate, wiring, expansion bounds, and policy
  state are device-verified.
- v0.0.4 runtime rejected the NotificationPanelExpandController visibility
  path and showed that explicit ShadeController collapse calls do not account
  for every hide.
- v0.0.5 runtime verified full expansion and both zero-height collapse routes.
- v0.0.6 target-APK analysis verified the merged Runnable roles, separate
  scheduling paths, static R8 end-motion accessor, inlined decision inputs,
  concrete fling descriptors, and direct interactive-manager field graph.
- v0.0.6 local comprehensive build/package verification passed and is recorded
  in `docs/PROGRESS.md`.

No installation, LSPosed activation, scope change, SystemUI restart, or device
command was performed during local implementation.

## v0.0.6 causality validation (human-gated)

1. Before enabling HyperOSP, confirm that LSPosed Manager is reachable and
   that the framework's normal safe-mode/rescue route is available.
2. Temporarily remove `com.android.systemui` scope from RestoreSplashScreen,
   HyperCeiler, and every other module so HyperOSP is the only SystemUI hook
   module for this baseline.
3. Install `app/build/outputs/apk/debug/app-debug.apk`.
4. Enable only HyperOSP and verify its scope contains only
   `com.android.systemui`.
5. Capture/export LSPosed logs, then perform one controlled SystemUI restart or
   device reboot using the tester's established procedure.
6. Before pulling down, confirm the `HyperOSP:` sequence reports
   module load, the SystemUI process/package, both fragments found, hook target
   found, replacement hook installed, and diagnostic-hook install outcomes.
7. Make one slow, controlled desktop pull-down attempt in
   `use_control_panel=0`, then stop and preserve the complete `HyperOSP:`
   sequence. Group every line by `gesture#N`. The key evidence is the
   end-motion inputs, original `isFalseTouch` result, actual `fling$2` expand
   boolean, zero/nonzero target, three interactive-flow values, any
   `onEmptySpaceClick` schedule check, and the merged Runnable class id.
8. Do not broaden scope, enable Compose QS, or add a compensating behavior in
   this run. Treat a missing diagnostic target as evidence of OEM drift, not as
   permission to guess a replacement.

## Recovery on failure (human-gated)

1. Stop repeated QS interaction or repeated SystemUI restarts.
2. Disable HyperOSP in LSPosed Manager (or use the already-confirmed LSPosed
   safe-mode/rescue path if the UI is unavailable).
3. Remove HyperOSP's scope or uninstall the debug APK only after the module is
   disabled.
4. Restart SystemUI or reboot once using the tester's established recovery
   procedure, then verify the stock SystemUI and Quick Settings are restored.
5. Preserve the LSPosed/HyperOSP logs from the failed run before retrying. Do
   not modify Magisk/KernelSU, boot, recovery, partitions, or AVB merely to
   recover from this application-level PoC.

## Original reverse-engineering design record

This preserved design record is the pre-implementation rationale from the
original HyperOSP documentation history. The implemented contract and current
validation status are documented above.

## Objective

Prove that HyperOS 3 SystemUI can host the AOSP `QSFragmentLegacy` already compiled inside `MiuiSystemUI.apk`.

This PoC must not patch or replace SystemUI files.

## Why this is plausible

The tested HyperOS 3 / Android 16 `MiuiSystemUI.apk` contains:

```text
com.android.systemui.qs.MiuiQSFragment
com.android.systemui.qs.QSFragmentLegacy
com.android.systemui.qs.composefragment.QSFragmentCompose
```

and relevant Dagger-provider symbols for all three families.

Changing:

```text
settings system use_control_panel
```

only switches between HyperOS's new Control Center and MIUI classic QS. It does not select AOSP QS.

Therefore the implementation should intercept fragment instantiation, not the user preference.

## Candidate runtime hook

Candidate target from APK analysis:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
```

candidate method:

```text
instantiateWithInjections(Context, String, Bundle)
```

Expected behavior:

```text
before:
  requested class = com.android.systemui.qs.MiuiQSFragment

after HyperOSP:
  requested class = com.android.systemui.qs.QSFragmentLegacy
```

The hook must not touch any other fragment.

## Compatibility gate

Before registering behavior:

1. Load `MiuiQSFragment` using the SystemUI ClassLoader.
2. Load `QSFragmentLegacy`.
3. Find the target `FragmentHostManager` nested class.
4. Find a compatible `instantiateWithInjections` overload.
5. Validate that the class-name parameter can be identified safely.

If any step fails:

```text
log compatibility failure
return
```

Do not attempt heuristic rewriting in the first PoC.

## Suggested hook behavior

Pseudo-code only:

```kotlin
if (packageName != "com.android.systemui") return
if (!isFirstPackage) return

val miuiQs = "com.android.systemui.qs.MiuiQSFragment"
val legacyQs = "com.android.systemui.qs.QSFragmentLegacy"

verifyClass(miuiQs)
verifyClass(legacyQs)
verifyTargetMethod()

hook(targetMethod).intercept { chain ->
    val index = classNameArgumentIndex
    if (chain.args[index] == miuiQs) {
        val modifiedArgs = chain.args.toTypedArray()
        modifiedArgs[index] = legacyQs
        log("QS backend: MiuiQSFragment -> QSFragmentLegacy")
        chain.proceed(modifiedArgs)
    } else {
        chain.proceed()
    }
}
```

## Why not hook the Dagger provider first

Hooking the class-name request is preferred for the first experiment because:

- the change is narrowly scoped to the one requested fragment;
- HyperOS's own FragmentService / Dagger path can still construct the target;
- fewer internal DI assumptions are overridden;
- rollback is simply disabling the module;
- it should be easier to reason about from logs.

If the target method is inlined / unavailable at runtime, revisit provider-level hooks.

## Why Legacy before Compose

Use:

```text
QSFragmentLegacy
```

first.

Do not target `QSFragmentCompose` in M1 even though Compose classes are present.

Reasons:

- Xiaomi's SystemUI contains explicit legacy compatibility references;
- the legacy path is closer to the existing MIUI classic view architecture;
- fewer SceneContainer / Compose feature flags should be involved;
- a successful legacy PoC proves the core backend-switch concept with lower risk.

## What a successful first boot proves

A successful render would establish that:

1. HyperOS's AOSP QS code is not dead code;
2. Xiaomi's notification shade can host it;
3. existing QSHost / tile infrastructure can bridge into the AOSP implementation;
4. HyperOSP can become a backend switcher instead of a visual imitation layer.

## What it does not prove

It does not automatically prove:

- all tiles work;
- Xiaomi-specific tiles work;
- brightness / media integrations are correct;
- lockscreen shade is stable;
- Compose QS is ready;
- the same hook works on another HyperOS build.

Those are separate validation steps.

## Recovery

Real-device testing must be done with an easy LSPosed disable / safe-mode path available.

The module itself must never modify SystemUI APKs, partitions, or boot files.
