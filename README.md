# HyperOSP

HyperOSP is an experimental LSPosed module for narrowly scoped, fail-safe
HyperOS SystemUI compatibility experiments.

**Current status:** the v0.0.3 API 101 device run proved that the exact
`MiuiQSFragment -> QSFragmentLegacy` replacement, `QSFragmentLegacy`
lifecycle, `QSImpl` delegate creation, normal AOSP wiring, controller `mQs`
binding, expansion bounds, and expansion-enabled policy state all complete.
The v0.0.4 device run further proved that the assumed
`NotificationPanelExpandController#setPanelVisible` /
`startPanelVisibleAnimation` / `panelVisible` path does not exist on this OEM
build. `ShadeControllerImpl` collapse methods do run, but not before every
observed hide, so that path cannot explain the blocker by itself.

v0.0.5 retains the fragment rewrite unchanged and moves observation to
`NotificationPanelViewController`: actual expanded-height transitions,
`endMotionEvent`, the original `flingExpands` result, and the reflected
`flingToHeight` target. It also inventories Xiaomi's
`PanelInteractiveManager` properties as fields, StateFlow values, getters, or
generated lambdas without invoking lambdas or changing state. Sparse
`ShadeController` logging remains supporting evidence only. Clean debug,
lint, and release/R8 builds pass locally. The next step is a human-gated
debug-APK run with HyperOSP as the only SystemUI hook module.

The v0.0.5 diagnostic milestone is a minimal proof of concept for Xiaomi 15 Pro
(`haotian`) on HyperOS 3 / Android 16. It attempts one transformation only:
when SystemUI asks its fragment injection manager to instantiate the exact
class name `com.android.systemui.qs.MiuiQSFragment`, substitute
`com.android.systemui.qs.QSFragmentLegacy`.

## M1 boundaries

- Package scope: `com.android.systemui` only
- Hook API: modern libxposed API 101 (`io.github.libxposed:api:101.0.0`)
- Build: Kotlin, JDK 21, compileSdk/targetSdk 36
- No Compose QS substitution
- No `miui.systemui.plugin` scope
- No settings UI, Magisk, or KernelSU companion
- Compatibility failure is a logged no-op

## Hook contract

M1 resolves the following non-static method from SystemUI's class loader:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
    instantiateWithInjections(Context, String, Bundle): android.app.Fragment
```

It first verifies both fragment classes, then requires exactly one compatible
method. The sole `String` parameter index is discovered from reflection rather
than assumed. The API 101 `XposedInterface.Hooker` copies the immutable Chain
arguments and calls `chain.proceed(modifiedArgs)` only when the exact
MiuiQSFragment class name is requested; every other call uses
`chain.proceed()`. Missing classes, a changed or ambiguous signature, invalid
callback arguments, and hook errors all produce a `HyperOSP:` diagnostic and a
no-op.

The v0.0.5 diagnostic hooks do not replace results, arguments, or receivers.
They accept the verified HyperOS one-argument
`QsFragmentListener#onFragmentViewCreated(Fragment)` signature (while retaining
the compatible AOSP two-argument form), observe the resulting `mQs` identity,
and then reflect the real `NotificationPanelViewController` methods before
installing read-only interceptors. Height logs are rate-limited; gesture-end
and fling decisions include actual expansion state and filtered SystemUI caller
frames. `flingExpands` results and `flingToHeight` arguments are returned and
forwarded unchanged. The old frame-adjacent QS wiring traces and the rejected
`NotificationPanelExpandController` visibility path are removed. Every
original method is invoked exactly once; an original SystemUI exception is
rethrown unchanged.

A successful local build does not prove which gesture predicate returns false
or whether the final target height is zero; that evidence requires the next
human-gated runtime log. Installation, LSPosed activation, scope changes,
SystemUI restart, and all other device operations remain human-gated.

## Build

```shell
./gradlew :app:assembleDebug
```

Additional local checks used for M1 are:

```shell
./gradlew clean :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The device-test artifact is
`app/build/outputs/apk/debug/app-debug.apk`. The release build is an R8 and
packaging check for this milestone, not a published release.

## Documentation

- [`docs/POC_QS_LEGACY.md`](docs/POC_QS_LEGACY.md) describes the hook contract.
- [`docs/PROGRESS.md`](docs/PROGRESS.md) separates completed local work from
  device validation.
- [`docs/PROJECT_MEMORY.md`](docs/PROJECT_MEMORY.md) records durable verified
  facts and constraints.

## License and provenance

New HyperOSP work is licensed under `GPL-3.0-only`. This repository was
bootstrapped from
[JingMatrix/libxposed-example](https://github.com/JingMatrix/libxposed-example),
whose retained material is Apache-2.0 licensed. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the license files for
details.

## Project vision and verified research basis

HyperOSP is an experimental LSPosed project for restoring AOSP / Pixel-like system surfaces on Xiaomi HyperOS **without replacing the HyperOS vendor stack**.

The project is not a custom ROM and is not intended to turn HyperOS into a Pixel ROM by copying Pixel system APKs. The preferred strategy is:

1. keep Xiaomi's hardware support, vendor services, local compatibility, camera / telephony / NFC stack, and device-specific integrations;
2. locate AOSP implementations that are still shipped inside HyperOS;
3. route HyperOS back to those AOSP implementations at runtime;
4. use the smallest possible hook, with a safe no-op fallback if a target is missing.

## Current target

The first proof of concept is **Quick Settings backend restoration** on HyperOS 3 / Android 16.

Observed on Xiaomi 15 Pro (`haotian`):

- `use_control_panel=1` -> HyperOS control center.
- `use_control_panel=0` -> MIUI classic Quick Settings, **not** AOSP Quick Settings.
- `MiuiSystemUI.apk` still contains:
  - `QSFragmentLegacy`
  - `QSFragmentCompose`
  - `QSPanel`
  - `QuickQSPanel`
  - `QuickSettingsScene`
  - `QuickSettingsShade`
  - `MiuiQSFragment`
- APK analysis indicates HyperOS still has Dagger providers for both AOSP and MIUI QS implementations.

The first implementation goal is therefore:

```text
MiuiQSFragment
      |
      | runtime redirection
      v
QSFragmentLegacy
```

`QSFragmentLegacy` is the first target because Xiaomi's SystemUI contains explicit compatibility paths for it. `QSFragmentCompose` is a later milestone.

## Other verified surface restoration experiments

### Photo picker

HyperOS registers:

```text
com.android.photopicker/.hyper.HyperMainActivity
priority=1000
```

while the AOSP legacy picker remains available as:

```text
com.android.providers.media.module/
  com.android.providers.media.photopicker.PhotoPickerActivity
priority=100
```

Disabling only `HyperMainActivity` for the user causes Android to fall back to the AOSP picker.

### File picker

Setting:

```text
securitycenter / hyper_refer_file_picker = false
```

routes file selection back to AOSP DocumentsUI on the tested build. This should eventually become per-app routing rather than a global switch.

## Design principles

- **Runtime hooks before system-file replacement.**
- **Reuse AOSP code already shipped by HyperOS before reimplementing UI.**
- **Do not patch or replace `MiuiSystemUI.apk` unless there is no safer option.**
- **Fail closed:** if a hook target is missing, do nothing instead of crashing SystemUI.
- **Small, independently switchable surfaces:** QS, photo picker, file picker, volume UI, notifications, lockscreen.
- **Compatibility first:** detect classes and capabilities at runtime instead of trusting a single HyperOS version string.

## Planned architecture

```text
HyperOSP
├── core
│   ├── compatibility detection
│   ├── logging
│   └── safe hook utilities
├── features
│   ├── quicksettings
│   ├── photopicker
│   ├── documentsui
│   ├── volume
│   ├── notifications
│   └── lockscreen
└── optional root companion
    ├── RRO / static resources
    ├── system properties
    └── boot-time configuration
```

The LSPosed module should own runtime behavior. A future Magisk / KernelSU companion may only handle static resources or boot-time state when LSPosed is the wrong tool.

## Development base

The project is bootstrapped from the modern libxposed example lineage, currently using the maintained example at:

- `JingMatrix/libxposed-example`
- Xposed API 101
- Android / compile SDK 36
- JDK 21

The example project is Apache-2.0 licensed. HyperOSP's own code is GPL-3.0-only; original third-party notices must be preserved where applicable.

## Status

See:

- [Project memory](docs/PROJECT_MEMORY.md)
- [Progress](docs/PROGRESS.md)
- [Quick Settings PoC notes](docs/POC_QS_LEGACY.md)
- [Agent instructions](AGENTS.md)

## License

HyperOSP is licensed under **GNU GPL v3.0 only (GPL-3.0-only)** unless a file states otherwise.

Third-party code retains its original license and copyright notices.
