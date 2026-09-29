# HyperOSP repository instructions

These instructions apply to the entire repository.

## Project boundary

- HyperOSP is an LSPosed module written in Kotlin with the modern libxposed API.
- The v0.0.5/M1 diagnostic scope is only `com.android.systemui`.
- Do not add `miui.systemui.plugin`, a settings UI, Magisk, KernelSU, or other
  companion components during M1.
- Do not install the APK, change LSPosed state or scope, run device-side
  `adb`/`su`/`pm`/`settings`/`device_config` commands, restart SystemUI, reboot a
  device, or modify boot/recovery/AVB state without explicit human approval at
  that checkpoint.

## Build and API constraints

- Use JDK 21, Kotlin, compileSdk 36, and targetSdk 36.
- Use the formal `io.github.libxposed:api:101.0.0` dependency and target
  libxposed API 101 for M1. Do not introduce legacy
  `XposedHelpers` or `XposedBridge` APIs.
- The required local verification command is `./gradlew :app:assembleDebug`.
- Keep `META-INF/xposed/module.prop` on API 101 with `staticScope=true` and keep
  `scope.list` restricted to `com.android.systemui` for M1.
- API 100 is rejected because the verified API 101 test framework disables
  API 100 modules. Do not target API 102 during the M1 API migration.

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
- Do not report local compilation as successful device validation. The v0.0.3
  API 101 run verified the class rewrite, legacy lifecycle/delegate, standard
  wiring, final `mQs`, expansion bounds, and enabled policy state. The v0.0.4
  run rejected the assumed `NotificationPanelExpandController` visibility
  setter/field path and showed that explicit `ShadeControllerImpl` collapse
  calls do not explain every hide. The next unknown is the live
  `NotificationPanelViewController` gesture-end/fling decision.
- Diagnostic interceptors must be observation-only: do not alter arguments,
  receivers, return values, or original exceptions. Rate-limit frame-adjacent
  logging and invoke each original method exactly once.
- For v0.0.5, do not restore the noisy `updateExpansion`/`setQsExpansion`
  traces and do not compensate for, suppress, or override a collapse or fling
  result. Enumerate the live `NotificationPanelViewController` signatures
  before observing `setExpandedHeight`, `setExpandedHeightInternal`,
  `endMotionEvent`, `flingExpands`, or `flingToHeight`. Treat an unresolved
  `flingToHeight` target index as diagnostic evidence, not permission to guess.
- `PanelInteractiveManager` diagnostics may classify fields, property getters,
  StateFlow values, and generated `Function0` classes. They must not invoke an
  OEM lambda or mutate a flow/property.
- For the next human-run diagnostic baseline, recommend temporarily excluding
  HyperCeiler, RestoreSplashScreen, and every other SystemUI-scoped module so
  HyperOSP is the only SystemUI hook module, and use the debug APK.
  RestoreSplashScreen produced unrelated `NullPointerException` and
  `NoSuchMethodError` logs during the first API 101 run.

## Git hygiene

- Do not force-push, destructively reset the template, or rewrite shared
  history.
- Keep changes small, reviewable, and on `feat/aosp-qs-legacy-poc` until M1 is
  handed off.
## Expanded project operating contract

This file is the persistent operating contract for Codex / coding agents working on HyperOSP.

## Mission

Restore AOSP / Pixel-like Android system surfaces on HyperOS while preserving Xiaomi's vendor stack and device-specific functionality.

Prefer activating AOSP implementations already present in HyperOS over copying Pixel APKs or maintaining a SystemUI fork.

## Operating modes

### Mode A — HIGH_AUTONOMY (default)

Use this mode unless the human explicitly requests Human Verification mode.

The agent should autonomously:

- inspect and refactor the repository;
- research upstream AOSP, libxposed, HyperCeiler, HyperStar, and related public code;
- inspect user-provided APKs and decompiled artifacts;
- create / edit source, tests, documentation, scripts, and CI;
- run builds, static checks, unit tests, and local analysis repeatedly until clean;
- fix build failures without stopping for permission;
- keep `README.md`, `docs/PROJECT_MEMORY.md`, and `docs/PROGRESS.md` current;
- make small logical commits on a working branch;
- prefer evidence from source / runtime output over guesses;
- continue to the next unblocked engineering task instead of asking what to do next.

When multiple reasonable implementation choices exist, choose the smallest reversible implementation and document the choice.

### Mode B — HUMAN_VERIFY

Enter this mode when the human says e.g. "人类核对模式", "human verify", or "review first".

In this mode:

- present the plan or diff before meaningful behavior changes;
- wait for explicit approval before committing or starting the next risky step;
- keep analysis and implementation separated so the human can inspect each stage.

The human can switch back by saying e.g. "高度自治模式" / "high autonomy".

## Hard human checkpoints in every mode

Even in HIGH_AUTONOMY, never perform these actions on a real connected Android device without explicit human approval:

- `adb`, `fastboot`, `su`, `pm disable`, `pm uninstall`, `device_config put/delete`, `settings put/delete`, reboot, slot changes, partition writes;
- enabling / disabling an LSPosed module or changing its scope;
- installing a newly built APK on the primary test device;
- killing or restarting SystemUI on the primary test device;
- flashing Magisk / KernelSU modules;
- bootloader, AVB, recovery, boot, vendor_boot, init_boot, or userdata operations.

Repository-only work, builds, decompilation, and public-source research do not require confirmation.

## Repository rules

- Primary language: Kotlin.
- Modern libxposed API 101 for M1; API 100 is runtime-incompatible with the
  current test framework and API 102 is intentionally deferred.
- Initial scope: `com.android.systemui` only.
- Minimum Android target for the first PoC: HyperOS 3 / Android 16.
- Keep device-specific compatibility isolated from generic hooks.
- Do not hard-code obfuscated names when a stable semantic target exists.
- Prefer runtime class / method existence checks.
- A missing target must cause a logged no-op, not a crash.
- Do not replace `MiuiSystemUI.apk` for the first implementation.
- Do not add `miui.systemui.plugin` scope until a feature demonstrably requires it.

## First milestone: AOSP legacy Quick Settings PoC

Target behavior:

```text
HyperOS asks FragmentHostManager to instantiate MiuiQSFragment
                         |
                         | HyperOSP interception
                         v
                 QSFragmentLegacy
```

Known candidate interception point from current APK analysis:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
    instantiateWithInjections(Context, String, Bundle)
```

Only rewrite the class-name argument when it is exactly:

```text
com.android.systemui.qs.MiuiQSFragment
```

to:

```text
com.android.systemui.qs.QSFragmentLegacy
```

Do not rewrite unrelated fragments.

Before enabling the hook:

1. verify both classes exist in the target ClassLoader;
2. verify the target method exists with a compatible signature;
3. log the compatibility result;
4. if any verification fails, leave SystemUI untouched.

## Logging

Use a consistent prefix:

```text
HyperOSP:
```

Log at least:

- module load + process name;
- detected HyperOS / Android information when available;
- target-class availability;
- hook installation success / failure;
- each actual QS class-name substitution;
- caught exceptions.

Avoid log spam during frame rendering or repeated UI callbacks.

## Build / test expectations

For every code change:

1. run the fastest relevant local check;
2. run the full debug build before declaring the change ready;
3. update progress documentation when a milestone state changes;
4. do not claim real-device success until the human confirms it.

A successful compile is not a successful SystemUI test.

## Safety / recovery design

SystemUI is critical. Every hook must be reversible and defensive.

- No hook should be required for the phone to boot.
- Never intentionally throw from a hook callback.
- Catch unexpected reflection / class-loading failures.
- Prefer one narrow hook over several broad hooks.
- Keep the first APK installable even if the feature is disabled.
- Add a user-visible master switch only after the PoC works; until then keep code simple.

## Project memory discipline

Before starting substantial work, read:

1. `docs/PROJECT_MEMORY.md`
2. `docs/PROGRESS.md`
3. relevant feature research notes

When new facts are discovered, classify them as:

- **Verified runtime**
- **Verified APK/source**
- **Hypothesis**
- **Rejected**

Do not silently promote a hypothesis into a verified fact.

## Git discipline

In HIGH_AUTONOMY mode:

- use small focused commits;
- do not rewrite shared history;
- do not force-push;
- keep experimental changes on a feature branch when they can affect boot-critical behavior;
- documentation-only bootstrap changes may land directly on the default branch.

Recommended branch for the first implementation:

```text
feat/aosp-qs-legacy-poc
```

## Licensing

HyperOSP project code: GPL-3.0-only.

The libxposed example lineage is Apache-2.0. Preserve upstream notices for copied / adapted code and record third-party provenance.
