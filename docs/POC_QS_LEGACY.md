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

## Candidate hook

The initial candidate is:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
    instantiateWithInjections(Context, String, Bundle)
```

This is an OEM/runtime compatibility hypothesis, not yet device-validated.
HyperOSP must resolve the nested class from SystemUI's class loader, enumerate
methods named `instantiateWithInjections`, and accept exactly one compatible,
non-ambiguous candidate. The compatible signature must contain one
`android.content.Context`, one `java.lang.String`, and one `android.os.Bundle`
parameter. The `String` position is derived from the reflected parameter list
and retained for the hook callback; it is never assumed from the candidate
signature text above.

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
7. Install one modern libxposed before-hook.
8. In the callback, compare the reflected argument safely. Only an exact match
   for `MiuiQSFragment` is replaced with `QSFragmentLegacy`; all other values
   are untouched.
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

## Validation status

The target classes were verified in the target APK before this bootstrap. The
candidate method and its runtime signature still require reflection evidence
from the first human-gated device run. No installation, LSPosed activation,
scope change, SystemUI restart, or device command is authorized during local
implementation.
