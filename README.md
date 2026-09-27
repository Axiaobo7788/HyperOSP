# HyperOSP

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
- Xposed API >= 100
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
