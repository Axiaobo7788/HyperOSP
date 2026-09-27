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
kept to make that provenance auditable. Apache-2.0 material may be combined in
this GPLv3 project, but its original attribution and license notices remain in
force.

## libxposed API 101

HyperOSP compiles against the formal Maven Central artifact
`io.github.libxposed:api:101.0.0`. Its upstream project is
https://github.com/libxposed/api.

The API artifact is Apache-2.0 licensed. It is declared as a Gradle
`compileOnly` dependency and is not packaged into the HyperOSP APK; the Xposed
framework provides the runtime implementation. The Apache-2.0 text is retained
at `LICENSES/Apache-2.0.txt`. The former repository-local API 100 stub was
removed when v0.0.2 migrated to API 101.

## Build dependencies

HyperOSP compiles against the modern libxposed API and uses the Android Gradle
Plugin and Kotlin Gradle plugin. Those dependencies are not redistributed as
HyperOSP source and remain under their respective upstream licenses.

## Research references

HyperCeiler and HyperStar are research references for HyperOS hooking and compatibility patterns. Do not copy code without checking and preserving the source project's license and notices.
