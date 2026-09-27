/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class HyperOSPModule : XposedModule() {

    @Volatile
    private var processName = UNKNOWN_PROCESS
    private val installAttempted = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        processName = param.processName
        safeLog("HyperOSP: module loaded; process=$processName")
    }

    @SuppressLint("NewApi")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SYSTEM_UI_PACKAGE) return

        safeLog(
            "HyperOSP: process/package: $processName/${param.packageName}; " +
                "firstPackage=${param.isFirstPackage}",
        )

        if (!param.isFirstPackage || !installAttempted.compareAndSet(false, true)) return

        try {
            installLegacyQsHook(param.defaultClassLoader)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing hook", throwable)
        }
    }

    // API 101 exposes hook(Executable) and invokes this path from its
    // Android-Q-or-newer onPackageLoaded callback. M1 targets Android 16.
    @SuppressLint("PrivateApi", "NewApi")
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

        try {
            hook(target.method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(LegacyQsFragmentHooker(this, target.classNameArgumentIndex))
            safeLog(
                "HyperOSP: hook installed: ${target.method.declaringClass.name}#" +
                    "${target.method.name}; classNameIndex=${target.classNameArgumentIndex}",
            )
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing method hook", throwable)
            return
        }

        try {
            LegacyQsDiagnostics(this).install(classLoader)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing QS diagnostics", throwable)
        }

        try {
            PanelCollapseDiagnostics(this).install(classLoader)
        } catch (throwable: Throwable) {
            safeLog("HyperOSP: caught exception while installing panel diagnostics", throwable)
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

    @SuppressLint("NewApi")
    internal fun installDiagnosticHook(
        method: Method,
        hooker: XposedInterface.Hooker,
        label: String,
    ): Boolean = try {
        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
        safeLog("HyperOSP: diagnostic hook installed: $label")
        true
    } catch (throwable: Throwable) {
        safeLog("HyperOSP: caught exception while installing diagnostic hook: $label", throwable)
        false
    }

    internal fun safeLog(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) {
                log(Log.INFO, LOG_TAG, message)
            } else {
                log(Log.ERROR, LOG_TAG, message, throwable)
            }
        } catch (_: Throwable) {
            // Logging must never become a new SystemUI failure path.
        }
    }

    private data class HookTarget(
        val method: Method,
        val classNameArgumentIndex: Int,
    )

    private class LegacyQsFragmentHooker(
        private val module: HyperOSPModule,
        private val classNameArgumentIndex: Int,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val modifiedArguments = try {
                buildModifiedArguments(chain)
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception during class-name replacement",
                    throwable,
                )
                null
            }

            return if (modifiedArguments == null) {
                chain.proceed()
            } else {
                chain.proceed(modifiedArguments)
            }
        }

        private fun buildModifiedArguments(chain: XposedInterface.Chain): Array<Any?>? {
            val arguments = chain.args
            if (classNameArgumentIndex !in arguments.indices) {
                module.safeLog(
                    "HyperOSP: hook callback compatibility check failed; " +
                        "classNameIndex=$classNameArgumentIndex args=${arguments.size}",
                )
                return null
            }

            val requestedClassName = arguments[classNameArgumentIndex] as? String ?: return null
            if (requestedClassName != MIUI_QS_FRAGMENT) return null

            val modifiedArguments = arguments.toTypedArray()
            modifiedArguments[classNameArgumentIndex] = LEGACY_QS_FRAGMENT
            module.safeLog(
                "HyperOSP: actual class-name replacement: " +
                    "$MIUI_QS_FRAGMENT -> $LEGACY_QS_FRAGMENT",
            )
            return modifiedArguments
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
        private const val LOG_TAG = "HyperOSP"
        private const val UNKNOWN_PROCESS = "<unknown>"
    }
}
