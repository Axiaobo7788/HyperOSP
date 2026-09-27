# HyperOSP project memory

Last updated: 2026-09-27

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

## Repository and toolchain facts

- The local repository was cloned from JingMatrix/libxposed-example at commit
  `87e9cb8` and retains that history as provenance.
- The inherited template already selected compileSdk/targetSdk 36, JDK 21,
  Kotlin, and modern libxposed API 100.
- The template's annotation-based API 100 ABI matches libxposed/api commit
  `55efdf9d159195261d7326e9e125965a90025a12`, before the later removal of
  `@XposedHooker` callback annotations. Its `BeforeHookCallback.getArgs()`
  contract explicitly permits modifying the returned argument array.
- HyperOSP's namespace and Kotlin package are
  `io.github.axiaobo7788.hyperosp`.
- M1's only static scope is `com.android.systemui`.

## Safety and validation boundary

- A missing class, changed signature, ambiguous overload, or hook exception
  must result in a `HyperOSP:` diagnostic and no behavior change.
- Reflection must discover the `String className` argument position from the
  compatible method signature rather than assuming an index.
- Under the selected API 100 ABI, argument replacement is performed by writing
  the discovered index in `BeforeHookCallback.args`; legacy `XposedHelpers` is
  not used.
- Local compilation verifies source, resource, and packaging consistency only.
  It does not verify LSPosed loading, OEM runtime behavior, UI correctness, or
  recovery behavior on a physical device.
- Any physical-device action is human-gated.

## Licensing

- HyperOSP-authored work uses `GPL-3.0-only`.
- Retained JingMatrix/libxposed-example material remains subject to Apache-2.0;
  attribution is recorded in `THIRD_PARTY_NOTICES.md`.
