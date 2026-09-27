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

The Xiaomi implementation remains an OEM/runtime compatibility hypothesis
until device logs confirm it. HyperOSP resolves the nested class from
SystemUI's class loader, enumerates methods named `instantiateWithInjections`,
and accepts exactly one compatible, non-ambiguous candidate. The method must
be non-static, return exact type `android.app.Fragment`, and contain exactly
one `android.content.Context`, one `java.lang.String`, and one
`android.os.Bundle` parameter. The `String` position is derived from the
reflected parameter list and retained for the hook callback; it is never
assumed from the signature text above.

## Installation algorithm

1. Log module construction with the `HyperOSP:` prefix.
2. Ignore every package except the exact `com.android.systemui` package.
3. Log package and process, then ignore non-first package-load callbacks.
4. Probe `MiuiQSFragment` and `QSFragmentLegacy` with the supplied class loader
   without initializing them. If either is missing, log and return.
5. Resolve `FragmentHostManager$ExtensionFragmentManager`; if missing, log and
   return.
6. Find one and only one signature-compatible
   `instantiateWithInjections` method and derive its sole `String` argument
   index. If the method is missing or ambiguous, log and return.
7. Install one modern libxposed API 100 before-hook. The selected API contract
   explicitly permits mutation through `BeforeHookCallback.getArgs()`.
8. In the callback, bounds-check the discovered index and compare the argument
   safely. Only an exact match for `MiuiQSFragment` is replaced with
   `QSFragmentLegacy`; all other values are untouched.
9. Catch and log every HyperOSP-owned failure so compatibility drift produces a
   no-op rather than a deliberate SystemUI crash.

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
- Debug compilation, lint, release/R8, Xposed metadata, scope, compile-only API
  exclusion, entry-name adaptation, and hook annotation retention pass locally.
- The Xiaomi method, actual hook installation, replacement execution, QS UI,
  and SystemUI stability still require the first human-gated device run.

No installation, LSPosed activation, scope change, SystemUI restart, or device
command was performed during local implementation.

## First device validation (human-gated)

1. Before enabling HyperOSP, confirm that LSPosed Manager is reachable and
   that the framework's normal safe-mode/rescue route is available.
2. Install `app/build/outputs/apk/debug/app-debug.apk`.
3. Enable only HyperOSP and verify its scope contains only
   `com.android.systemui`.
4. Capture/export LSPosed logs, then perform one controlled SystemUI restart or
   device reboot using the tester's established procedure.
5. Before opening Quick Settings, confirm the `HyperOSP:` sequence reports
   module load, the SystemUI process/package, both fragments found, hook target
   found, and hook installed.
6. Open Quick Settings once and confirm exactly one class-name replacement log.
   Check expansion, collapse, tile interaction, notifications, lock screen,
   orientation/configuration changes, and a second QS open for stability.
7. Treat any missing compatibility check as a safe no-op result, not a pass.
   Treat a SystemUI crash, boot loop, broken shade, or repeated replacement
   failure as a failed M1 device test.

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
