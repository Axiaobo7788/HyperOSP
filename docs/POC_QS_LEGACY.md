# PoC: Restore AOSP Legacy Quick Settings

## Objective

Prove that HyperOS 3 SystemUI can host the AOSP `QSFragmentLegacy` already compiled inside `MiuiSystemUI.apk`.

This PoC must not patch or replace SystemUI files.

## Why this is plausible

The tested HyperOS 3 / Android 16 `MiuiSystemUI.apk` contains:

```text
com.android.systemui.qs.MiuiQSFragment
com.android.systemui.qs.QSFragmentLegacy
com.android.systemui.qs.composefragment.QSFragmentCompose
```

and relevant Dagger-provider symbols for all three families.

Changing:

```text
settings system use_control_panel
```

only switches between HyperOS's new Control Center and MIUI classic QS. It does not select AOSP QS.

Therefore the implementation should intercept fragment instantiation, not the user preference.

## Candidate runtime hook

Candidate target from APK analysis:

```text
com.android.systemui.fragments.FragmentHostManager$ExtensionFragmentManager
```

candidate method:

```text
instantiateWithInjections(Context, String, Bundle)
```

Expected behavior:

```text
before:
  requested class = com.android.systemui.qs.MiuiQSFragment

after HyperOSP:
  requested class = com.android.systemui.qs.QSFragmentLegacy
```

The hook must not touch any other fragment.

## Compatibility gate

Before registering behavior:

1. Load `MiuiQSFragment` using the SystemUI ClassLoader.
2. Load `QSFragmentLegacy`.
3. Find the target `FragmentHostManager` nested class.
4. Find a compatible `instantiateWithInjections` overload.
5. Validate that the class-name parameter can be identified safely.

If any step fails:

```text
log compatibility failure
return
```

Do not attempt heuristic rewriting in the first PoC.

## Suggested hook behavior

Pseudo-code only:

```kotlin
if (packageName != "com.android.systemui") return
if (!isFirstPackage) return

val miuiQs = "com.android.systemui.qs.MiuiQSFragment"
val legacyQs = "com.android.systemui.qs.QSFragmentLegacy"

verifyClass(miuiQs)
verifyClass(legacyQs)
verifyTargetMethod()

hookBefore(targetMethod) { callback ->
    val index = classNameArgumentIndex
    if (callback.args[index] == miuiQs) {
        callback.args[index] = legacyQs
        log("QS backend: MiuiQSFragment -> QSFragmentLegacy")
    }
}
```

## Why not hook the Dagger provider first

Hooking the class-name request is preferred for the first experiment because:

- the change is narrowly scoped to the one requested fragment;
- HyperOS's own FragmentService / Dagger path can still construct the target;
- fewer internal DI assumptions are overridden;
- rollback is simply disabling the module;
- it should be easier to reason about from logs.

If the target method is inlined / unavailable at runtime, revisit provider-level hooks.

## Why Legacy before Compose

Use:

```text
QSFragmentLegacy
```

first.

Do not target `QSFragmentCompose` in M1 even though Compose classes are present.

Reasons:

- Xiaomi's SystemUI contains explicit legacy compatibility references;
- the legacy path is closer to the existing MIUI classic view architecture;
- fewer SceneContainer / Compose feature flags should be involved;
- a successful legacy PoC proves the core backend-switch concept with lower risk.

## What a successful first boot proves

A successful render would establish that:

1. HyperOS's AOSP QS code is not dead code;
2. Xiaomi's notification shade can host it;
3. existing QSHost / tile infrastructure can bridge into the AOSP implementation;
4. HyperOSP can become a backend switcher instead of a visual imitation layer.

## What it does not prove

It does not automatically prove:

- all tiles work;
- Xiaomi-specific tiles work;
- brightness / media integrations are correct;
- lockscreen shade is stable;
- Compose QS is ready;
- the same hook works on another HyperOS build.

Those are separate validation steps.

## Recovery

Real-device testing must be done with an easy LSPosed disable / safe-mode path available.

The module itself must never modify SystemUI APKs, partitions, or boot files.
