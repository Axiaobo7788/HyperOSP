/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class HyperOSPModule(base: XposedInterface, moduleParam: ModuleLoadedParam) :
    XposedModule(base, moduleParam) {

    private val processName = moduleParam.processName
    private val installAttempted = AtomicBoolean(false)

    init {
        safeLog("HyperOSP: module loaded; process=$processName")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) return

        safeLog(
            "HyperOSP: process/package: $processName/${param.packageName}; " +
                "firstPackage=${param.isFirstPackage}",
        )

        if (!param.isFirstPackage || !installAttempted.compareAndSet(false, true)) return

        try {
            installLegacyQsHook(param.classLoader)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing hook", throwable)
        }
    }

    @SuppressLint("PrivateApi")
    private fun installLegacyQsHook(classLoader: ClassLoader) {
        val miuiQsFound = probeClass(
            classLoader = classLoader,
            className = MIUI_QS_FRAGMENT,
            label = "MiuiQSFragment",
        )
        val legacyQsFound = probeClass(
            classLoader = classLoader,
            className = LEGACY_QS_FRAGMENT,
            label = "QSFragmentLegacy",
        )

        if (!miuiQsFound || !legacyQsFound) return

        val managerClass = try {
            Class.forName(EXTENSION_FRAGMENT_MANAGER, false, classLoader)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: hook target missing; manager class unavailable")
            safeLog("HyperOSP: caught exception while resolving hook target class", throwable)
            return
        }

        val target = resolveHookTarget(managerClass) ?: return
        LegacyQsFragmentHooker.configure(this, target.classNameArgumentIndex)

        try {
            hook(target.method, LegacyQsFragmentHooker::class.java)
            safeLog(
                "HyperOSP: hook installed: ${target.method.declaringClass.name}#" +
                    "${target.method.name}; classNameIndex=${target.classNameArgumentIndex}",
            )
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing method hook", throwable)
        }
    }

    private fun probeClass(
        classLoader: ClassLoader,
        className: String,
        label: String,
    ): Boolean = try {
        Class.forName(className, false, classLoader)
        safeLog("HyperOSP: $label found")
        true
    } catch (throwable: Throwable) {
        safeLog("HyperOSP: $label missing")
        safeLog("HyperOSP: caught exception while resolving $className", throwable)
        false
    }

    private fun resolveHookTarget(managerClass: Class<*>): HookTarget? {
        val compatibleMethods = try {
            managerClass.declaredMethods.filter(::hasCompatibleSignature)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: hook target missing; method inspection failed")
            safeLog("HyperOSP: caught exception while inspecting hook target", throwable)
            return null
        }

        if (compatibleMethods.size != 1) {
            val reason = if (compatibleMethods.isEmpty()) "no compatible method" else "ambiguous overloads"
            safeLog(
                "HyperOSP: hook target missing; $reason " +
                    "(compatible=${compatibleMethods.size})",
            )
            return null
        }

        val method = compatibleMethods.single()
        val stringIndexes = method.parameterTypes.withIndex()
            .filter { (_, type) -> type == String::class.java }
            .map { (index, _) -> index }

        if (stringIndexes.size != 1) {
            safeLog(
                "HyperOSP: hook target missing; String parameter count=${stringIndexes.size}",
            )
            return null
        }

        val classNameArgumentIndex = stringIndexes.single()
        safeLog(
            "HyperOSP: hook target found: ${method.declaringClass.name}#${method.name}; " +
                "classNameIndex=$classNameArgumentIndex",
        )
        return HookTarget(method, classNameArgumentIndex)
    }

    private fun hasCompatibleSignature(method: Method): Boolean {
        if (method.name != HOOK_METHOD_NAME || Modifier.isStatic(method.modifiers)) return false
        if (method.returnType.name != PLATFORM_FRAGMENT) return false

        val parameters = method.parameterTypes
        return parameters.size == 3 &&
            parameters.count { it == Context::class.java } == 1 &&
            parameters.count { it == String::class.java } == 1 &&
            parameters.count { it == Bundle::class.java } == 1
    }

    private fun safeLog(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) {
                log(message)
            } else {
                log(message, throwable)
            }
        } catch (_: Throwable) {
            // Logging must never become a new SystemUI failure path.
        }
    }

    private data class HookTarget(
        val method: Method,
        val classNameArgumentIndex: Int,
    )

    @XposedHooker
    class LegacyQsFragmentHooker private constructor() : XposedInterface.Hooker {

        companion object {
            @Volatile
            private var module: HyperOSPModule? = null

            @Volatile
            private var classNameArgumentIndex: Int = INVALID_ARGUMENT_INDEX

            fun configure(module: HyperOSPModule, classNameArgumentIndex: Int) {
                this.module = module
                this.classNameArgumentIndex = classNameArgumentIndex
            }

            @JvmStatic
            @BeforeInvocation
            fun beforeInvocation(callback: BeforeHookCallback) {
                val activeModule = module ?: return

                try {
                    val index = classNameArgumentIndex
                    val arguments = callback.args
                    if (index !in arguments.indices) {
                        activeModule.safeLog(
                            "HyperOSP: hook callback compatibility check failed; " +
                                "classNameIndex=$index args=${arguments.size}",
                        )
                        return
                    }

                    val requestedClassName = arguments[index] as? String ?: return
                    if (requestedClassName != MIUI_QS_FRAGMENT) return

                    arguments[index] = LEGACY_QS_FRAGMENT
                    activeModule.safeLog(
                        "HyperOSP: actual class-name replacement: " +
                            "$MIUI_QS_FRAGMENT -> $LEGACY_QS_FRAGMENT",
                    )
                } catch (throwable: Throwable) {
                    activeModule.safeLog(
                        "HyperOSP: caught exception during class-name replacement",
                        throwable,
                    )
                }
            }

            @JvmStatic
            @AfterInvocation
            fun afterInvocation(@Suppress("UNUSED_PARAMETER") callback: AfterHookCallback) = Unit
        }
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MIUI_QS_FRAGMENT = "com.android.systemui.qs.MiuiQSFragment"
        private const val LEGACY_QS_FRAGMENT = "com.android.systemui.qs.QSFragmentLegacy"
        private const val EXTENSION_FRAGMENT_MANAGER =
            "com.android.systemui.fragments.FragmentHostManager\$ExtensionFragmentManager"
        private const val HOOK_METHOD_NAME = "instantiateWithInjections"
        private const val PLATFORM_FRAGMENT = "android.app.Fragment"
        private const val INVALID_ARGUMENT_INDEX = -1
    }
}
