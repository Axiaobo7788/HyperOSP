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
- Android 16 QPR2 AOSP source retains the candidate private, non-static method
  `FragmentHostManager.ExtensionFragmentManager#instantiateWithInjections`
  with `(Context, String, Bundle)` parameters and an `android.app.Fragment`
  return type. Xiaomi's runtime implementation still requires device-side
  reflection confirmation.

## Verified runtime framework compatibility

- **Verified runtime:** 当前测试框架使用 Xposed/libxposed API101，API100
  HyperOSP 会被管理器自动禁用，因此 API100 路线已否决。
- M1 now uses `minApiVersion=101` and `targetApiVersion=101`. API 102 is not a
  migration target for this checkpoint.
- Because the manager disabled v0.0.1 before module execution, that attempt did
  not validate the hook point, class replacement, QS rendering, or SystemUI
  stability.

## Repository and toolchain facts

- The local repository was cloned from JingMatrix/libxposed-example at commit
  `87e9cb8` and retains that history as provenance.
- The inherited template selected compileSdk/targetSdk 36, JDK 21, and Kotlin.
- HyperOSP v0.0.2 compiles against the formal Maven Central dependency
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
