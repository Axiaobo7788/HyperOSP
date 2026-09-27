# HyperOSP progress

Last updated: 2026-09-27

## Current checkpoint

Repository bootstrap is in progress. The working tree began as an unmodified
JingMatrix/libxposed-example clone at `87e9cb8`; the HyperOSP remote repository
was not readable from the current unauthenticated shell/browser environment,
and no HyperOSP document objects were present locally. These documents were
therefore bootstrapped from the explicit project handoff constraints without
resetting or discarding the template history.

## Milestones

| Milestone | State | Evidence / remaining work |
| --- | --- | --- |
| Safe repository bootstrap | In progress | Template history preserved; remotes and target branch being organized |
| Minimal HyperOSP module | Pending | Remove UI/native demos, rename package, set metadata and scope |
| M1 legacy QS hook | Pending | Implement signature-checked, fail-safe class-name substitution |
| Local debug build | Pending | Run `./gradlew :app:assembleDebug` |
| Physical-device LSPosed validation | Human-gated | No installation or device action has been performed |

## Validation boundary

Do not interpret a local APK build as proof that SystemUI starts, that the
legacy fragment renders correctly, or that the device recovery path works.
Those claims require the explicit human-gated device procedure documented at
the M1 handoff.
