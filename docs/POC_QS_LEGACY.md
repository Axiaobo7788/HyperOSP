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
  state are device-verified. v0.0.4 collapse-source diagnostics still require
  the next human-gated device run.
- v0.0.4 clean debug, lint, and release/R8 builds pass locally; APK metadata,
  SystemUI-only scope, API 101 entry points, and diagnostic Hooker references
  were statically inspected.

No installation, LSPosed activation, scope change, SystemUI restart, or device
command was performed during local implementation.

## v0.0.4 diagnostic validation (human-gated)

1. Before enabling HyperOSP, confirm that LSPosed Manager is reachable and
   that the framework's normal safe-mode/rescue route is available.
2. Temporarily remove `com.android.systemui` scope from HyperCeiler,
   RestoreSplashScreen, and any other module, or disable those modules for this
   one baseline run.
3. Install `app/build/outputs/apk/debug/app-debug.apk`.
4. Enable only HyperOSP and verify its scope contains only
   `com.android.systemui`.
5. Capture/export LSPosed logs, then perform one controlled SystemUI restart or
   device reboot using the tester's established procedure.
6. Before pulling down, confirm the `HyperOSP:` sequence reports
   module load, the SystemUI process/package, both fragments found, hook target
   found, replacement hook installed, and diagnostic-hook install outcomes.
7. Make one controlled pull-down attempt in `use_control_panel=0`, then stop and
   preserve the complete `HyperOSP:` sequence. The key evidence is the
   reflected collapse-method inventory, `startPanelVisibleAnimation` caller,
   and the compact stack attached to `panelVisible true -> false`.
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
