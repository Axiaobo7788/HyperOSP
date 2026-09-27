# HyperOSP repository instructions

These instructions apply to the entire repository.

## Project boundary

- HyperOSP is an LSPosed module written in Kotlin with the modern libxposed API.
- The v0.0.1/M1 scope is only `com.android.systemui`.
- Do not add `miui.systemui.plugin`, a settings UI, Magisk, KernelSU, or other
  companion components during M1.
- Do not install the APK, change LSPosed state or scope, run device-side
  `adb`/`su`/`pm`/`settings`/`device_config` commands, restart SystemUI, reboot a
  device, or modify boot/recovery/AVB state without explicit human approval at
  that checkpoint.

## Build and API constraints

- Use JDK 21, Kotlin, compileSdk 36, and targetSdk 36.
- Use modern libxposed API 100 or newer. Do not introduce legacy
  `XposedHelpers` or `XposedBridge` APIs.
- The required local verification command is `./gradlew :app:assembleDebug`.
- Keep `META-INF/xposed/module.prop` on API 100 with `staticScope=true` and keep
  `scope.list` restricted to `com.android.systemui` for M1.

## Hook safety

- Initialize only for the first load of the exact `com.android.systemui`
  package.
- Resolve classes and methods against the target process class loader.
- Validate the complete candidate method signature and discover the single
  `String` argument index from reflection; never hard-code that index.
- Replace only an exact `com.android.systemui.qs.MiuiQSFragment` class-name
  argument with `com.android.systemui.qs.QSFragmentLegacy`.
- Treat every compatibility mismatch as a logged no-op. Catch hook-owned
  failures so HyperOSP cannot intentionally crash SystemUI.
- Prefix module diagnostics with `HyperOSP:`.

## Licensing and status

- New HyperOSP-authored source and documentation use `GPL-3.0-only` unless a
  file says otherwise.
- Preserve the Apache-2.0 provenance and license text for material inherited
  from JingMatrix/libxposed-example; keep `THIRD_PARTY_NOTICES.md` accurate.
- Do not report local compilation as successful device validation. The first
  device/LSPosed run remains a human-gated checkpoint.

## Git hygiene

- Do not force-push, destructively reset the template, or rewrite shared
  history.
- Keep changes small, reviewable, and on `feat/aosp-qs-legacy-poc` until M1 is
  handed off.
