# HyperOSP

HyperOSP is an experimental LSPosed module for narrowly scoped, fail-safe
HyperOS SystemUI compatibility experiments.

The v0.0.1 milestone is a minimal proof of concept for Xiaomi 15 Pro
(`haotian`) on HyperOS 3 / Android 16. It attempts one transformation only:
when SystemUI asks its fragment injection manager to instantiate the exact
class name `com.android.systemui.qs.MiuiQSFragment`, substitute
`com.android.systemui.qs.QSFragmentLegacy`.

## M1 boundaries

- Package scope: `com.android.systemui` only
- Hook API: modern libxposed API 100
- Build: Kotlin, JDK 21, compileSdk/targetSdk 36
- No Compose QS substitution
- No `miui.systemui.plugin` scope
- No settings UI, Magisk, or KernelSU companion
- Compatibility failure is a logged no-op

M1 is initially a source-and-build proof of concept. A successful local build
does not prove that Xiaomi's runtime implementation accepts the legacy
fragment. Installation, LSPosed activation, scope changes, SystemUI restart,
and all other device operations are intentionally left to a human-gated test.

## Build

```shell
./gradlew :app:assembleDebug
```

The debug artifact is produced under `app/build/outputs/apk/debug/`.

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
