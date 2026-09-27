# HyperOSP

HyperOSP is an experimental LSPosed module for narrowly scoped, fail-safe
HyperOS SystemUI compatibility experiments.

**Current status:** the v0.0.1 M1 source, debug APK, lint, and release/R8
packaging checks pass locally. The code is ready for the first human-gated
LSPosed device validation; it has not yet been installed or run on a phone.

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

## Hook contract

M1 resolves the following non-static method from SystemUI's class loader:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
    instantiateWithInjections(Context, String, Bundle): android.app.Fragment
```

It first verifies both fragment classes, then requires exactly one compatible
method. The sole `String` parameter index is discovered from reflection rather
than assumed. The API 100 before-hook mutates that argument only when its value
is the exact MiuiQSFragment class name. Missing classes, a changed or ambiguous
signature, invalid callback arguments, and hook errors all produce a
`HyperOSP:` diagnostic and a no-op.

M1 is initially a source-and-build proof of concept. A successful local build
does not prove that Xiaomi's runtime implementation accepts the legacy
fragment. Installation, LSPosed activation, scope changes, SystemUI restart,
and all other device operations are intentionally left to a human-gated test.

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
