# Third-party notices

## JingMatrix/libxposed-example

HyperOSP was bootstrapped from the public
[`JingMatrix/libxposed-example`](https://github.com/JingMatrix/libxposed-example)
repository, including its Git history, Gradle wrapper/build foundation, and
modern libxposed metadata layout.

- Upstream repository: https://github.com/JingMatrix/libxposed-example
- Imported baseline commit: `87e9cb8` (`Add native hook example`)
- Upstream license: Apache License 2.0
- Retained license text: `LICENSES/Apache-2.0.txt`

Files subsequently authored specifically for HyperOSP are licensed under
`GPL-3.0-only` unless stated otherwise. The Apache-2.0 terms continue to apply
to material retained or adapted from the upstream example; the Git history is
kept to make that provenance auditable.

## libxposed API 100 compile-time stub

`app/libs/libxposed-api-100-55efdf9.jar` contains compile-time-only API classes
built from libxposed/api commit
`55efdf9d159195261d7326e9e125965a90025a12` (`Add two methods for
constructors`). The source history was obtained from the public mirror at
https://gitlab.com/xposed_grp/LSPosed/libxposed/api.

The stub is Apache-2.0 licensed. It is declared as a Gradle `compileOnly`
dependency and is not packaged into the HyperOSP APK; the Xposed framework
provides the runtime implementation. The Apache-2.0 text is retained at
`LICENSES/Apache-2.0.txt`.

The committed stub JAR SHA-256 is
`a99a8dd3ac87b3fddd0730b01880848bb07f1292e29fdfcfe513152e524b2953`.

## Build dependencies

HyperOSP compiles against the modern libxposed API and uses the Android Gradle
Plugin and Kotlin Gradle plugin. Those dependencies are not redistributed as
HyperOSP source and remain under their respective upstream licenses.
