/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Low-volume confirmation of the QS fragment hand-off.
 *
 * The v0.0.3 device run already proved that the legacy fragment delegate and
 * standard AOSP wiring initialize correctly. v0.0.5 therefore keeps only the
 * listener/binding observation and removes the frame-adjacent wiring and
 * expansion traces.
 */
internal class LegacyQsDiagnostics(
    private val module: HyperOSPModule,
) {

    fun install(classLoader: ClassLoader) {
        val listenerClass = loadOptionalClass(classLoader, QS_FRAGMENT_LISTENER)
        val controllerClass = loadOptionalClass(classLoader, QUICK_SETTINGS_CONTROLLER)
        val legacyClass = loadOptionalClass(classLoader, LEGACY_QS_FRAGMENT)
        val miuiClass = loadOptionalClass(classLoader, MIUI_QS_FRAGMENT)

        runDiagnosticStage("fragment type inventory") {
            logClassRelationship(classLoader, miuiClass, legacyClass)
        }

        if (listenerClass != null && controllerClass != null) {
            runDiagnosticStage("QsFragmentListener") {
                installFragmentListenerDiagnostic(listenerClass, controllerClass)
            }
        }
    }

    private fun runDiagnosticStage(label: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception during diagnostic stage: $label",
                throwable,
            )
        }
    }

    private fun installFragmentListenerDiagnostic(
        listenerClass: Class<*>,
        controllerClass: Class<*>,
    ) {
        val compatible = declaredConcreteInstanceMethods(listenerClass).filter { method ->
            val types = method.parameterTypes
            method.name == ON_FRAGMENT_VIEW_CREATED &&
                method.returnType == Void.TYPE &&
                types.size in 1..2 &&
                types.count { it.name == PLATFORM_FRAGMENT } == 1 &&
                types.count { it == String::class.java } <= 1 &&
                types.all { it.name == PLATFORM_FRAGMENT || it == String::class.java }
        }

        if (compatible.size != 1) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$QS_FRAGMENT_LISTENER#$ON_FRAGMENT_VIEW_CREATED " +
                    "(compatible=${compatible.size})",
            )
            logRelevantMethodInventory(listenerClass, ON_FRAGMENT_VIEW_CREATED)
            return
        }

        val method = compatible.single()
        val tagIndex = method.parameterTypes.indexOfFirst { it == String::class.java }
            .takeIf { it >= 0 }
        val fragmentIndex = method.parameterTypes.indexOfFirst { it.name == PLATFORM_FRAGMENT }
        val outerControllerField = resolveOuterControllerField(listenerClass, controllerClass)
        val qsField = resolveQsField(controllerClass)
        val signatureVariant = if (tagIndex == null) {
            "HyperOS-one-argument"
        } else {
            "AOSP-tag-and-fragment"
        }

        module.safeLog(
            "HyperOSP: diagnostic hook target found: ${signature(method)}; " +
                "variant=$signatureVariant tagIndex=${tagIndex ?: NONE} " +
                "fragmentIndex=$fragmentIndex " +
                "outerField=${outerControllerField?.name ?: MISSING} " +
                "qsField=${qsField?.name ?: MISSING}",
        )
        module.installDiagnosticHook(
            method = method,
            hooker = QsFragmentListenerHooker(
                module = module,
                tagIndex = tagIndex,
                fragmentIndex = fragmentIndex,
                outerControllerField = outerControllerField,
                qsField = qsField,
            ),
            label = signature(method),
        )
    }

    private fun logClassRelationship(
        classLoader: ClassLoader,
        miuiClass: Class<*>?,
        legacyClass: Class<*>?,
    ) {
        if (miuiClass == null || legacyClass == null) return

        val qsInterface = tryLoadClass(classLoader, QS_INTERFACE)
        module.safeLog(
            "HyperOSP: diagnostic fragment types: " +
                "MiuiQSFragment(super=${miuiClass.superclass?.name ?: NONE}, " +
                "interfaces=${interfaceNames(miuiClass)}); " +
                "QSFragmentLegacy(super=${legacyClass.superclass?.name ?: NONE}, " +
                "interfaces=${interfaceNames(legacyClass)}); " +
                "QS-compatible(miui=${qsInterface?.isAssignableFrom(miuiClass)}, " +
                "legacy=${qsInterface?.isAssignableFrom(legacyClass)})",
        )

        MIUI_QS_INTERFACE_CANDIDATES.forEach { className ->
            val candidate = tryLoadClass(classLoader, className) ?: return@forEach
            module.safeLog(
                "HyperOSP: diagnostic MiuiQS type found: $className; " +
                    "MiuiQSFragment=${candidate.isAssignableFrom(miuiClass)} " +
                    "QSFragmentLegacy=${candidate.isAssignableFrom(legacyClass)}",
            )
        }
    }

    private fun loadOptionalClass(classLoader: ClassLoader, className: String): Class<*>? =
        tryLoadClass(classLoader, className).also { result ->
            module.safeLog(
                "HyperOSP: diagnostic class ${if (result == null) "missing" else "found"}: " +
                    className,
            )
        }

    private fun tryLoadClass(classLoader: ClassLoader, className: String): Class<*>? = try {
        Class.forName(className, false, classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun resolveOuterControllerField(
        listenerClass: Class<*>,
        controllerClass: Class<*>,
    ): Field? {
        val exactTypeFields = allInstanceFields(listenerClass).filter { it.type == controllerClass }
        val candidate = when {
            exactTypeFields.size == 1 -> exactTypeFields.single()
            else -> exactTypeFields.singleOrNull { it.name == OUTER_REFERENCE_FIELD }
        }
        return makeAccessible(candidate, "listener outer controller")
    }

    private fun resolveQsField(controllerClass: Class<*>): Field? {
        val fields = allInstanceFields(controllerClass)
        val named = fields.singleOrNull { it.name == QS_FIELD }
        val typed = fields.filter { it.type.name == QS_INTERFACE }
        return makeAccessible(named ?: typed.singleOrNull(), "QuickSettingsControllerImpl mQs")
    }

    @Suppress("DEPRECATION")
    private fun makeAccessible(field: Field?, label: String): Field? {
        if (field == null) return null
        return try {
            field.isAccessible = true
            field
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception while making diagnostic field accessible: $label",
                throwable,
            )
            null
        }
    }

    private fun allInstanceFields(owner: Class<*>): List<Field> = buildList {
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            addAll(current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) })
            current = current.superclass
        }
    }

    private fun declaredConcreteInstanceMethods(owner: Class<*>): List<Method> = try {
        owner.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers)
        }
    } catch (throwable: Throwable) {
        module.safeLog(
            "HyperOSP: caught exception while inspecting diagnostic methods: ${owner.name}",
            throwable,
        )
        emptyList()
    }

    private fun logRelevantMethodInventory(owner: Class<*>, methodName: String) {
        val signatures = try {
            owner.declaredMethods
                .filter { it.name == methodName }
                .map(::signature)
                .sorted()
        } catch (_: Throwable) {
            emptyList()
        }
        module.safeLog(
            "HyperOSP: diagnostic method inventory: ${owner.name}#$methodName=" +
                signatures.joinToString().ifEmpty { NONE },
        )
    }

    private fun interfaceNames(owner: Class<*>): String =
        owner.interfaces.joinToString { it.name }.ifEmpty { NONE }

    private fun signature(method: Method): String =
        "${method.declaringClass.name}#${method.name}(" +
            method.parameterTypes.joinToString { it.name } +
            "):${method.returnType.name}"

    private class QsFragmentListenerHooker(
        private val module: HyperOSPModule,
        private val tagIndex: Int?,
        private val fragmentIndex: Int,
        private val outerControllerField: Field?,
        private val qsField: Field?,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val observedArguments = try {
                ObservedArguments(
                    fragment = chain.args.getOrNull(fragmentIndex),
                    tag = tagIndex?.let { chain.args.getOrNull(it) as? String },
                )
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while reading QsFragmentListener arguments",
                    throwable,
                )
                ObservedArguments(fragment = null, tag = null)
            }
            logSafely(
                phase = "before",
                chain = chain,
                tag = observedArguments.tag,
                fragment = observedArguments.fragment,
            )

            val result = try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original onFragmentViewCreated exception; " +
                        "rethrowing unchanged",
                    throwable,
                )
                throw throwable
            }

            logSafely(
                phase = "completed",
                chain = chain,
                tag = observedArguments.tag,
                fragment = observedArguments.fragment,
            )
            return result
        }

        private fun logSafely(
            phase: String,
            chain: XposedInterface.Chain,
            tag: String?,
            fragment: Any?,
        ) {
            try {
                val listener = chain.thisObject
                val controller = outerControllerField?.get(listener)
                val boundQs = if (controller != null) qsField?.get(controller) else null
                module.safeLog(
                    "HyperOSP: diagnostic QsFragmentListener $phase; " +
                        "tag=${tag ?: NOT_PRESENT}; " +
                        "fragment=${identity(fragment)}; " +
                        "isLegacy=${fragment?.javaClass?.name == LEGACY_QS_FRAGMENT}; " +
                        "mQs=${identity(boundQs)}; " +
                        "mQsSameFragment=${boundQs != null && boundQs === fragment}",
                )
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while logging QsFragmentListener $phase",
                    throwable,
                )
            }
        }

        private data class ObservedArguments(
            val fragment: Any?,
            val tag: String?,
        )
    }

    companion object {
        private const val MIUI_QS_FRAGMENT = "com.android.systemui.qs.MiuiQSFragment"
        private const val LEGACY_QS_FRAGMENT = "com.android.systemui.qs.QSFragmentLegacy"
        private const val QS_INTERFACE = "com.android.systemui.plugins.qs.QS"
        private const val QUICK_SETTINGS_CONTROLLER =
            "com.android.systemui.shade.QuickSettingsControllerImpl"
        private const val QS_FRAGMENT_LISTENER =
            "com.android.systemui.shade.QuickSettingsControllerImpl\$QsFragmentListener"
        private const val PLATFORM_FRAGMENT = "android.app.Fragment"
        private const val ON_FRAGMENT_VIEW_CREATED = "onFragmentViewCreated"
        private const val OUTER_REFERENCE_FIELD = "this\$0"
        private const val QS_FIELD = "mQs"
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"
        private const val NOT_PRESENT = "<not-present>"

        private val MIUI_QS_INTERFACE_CANDIDATES = listOf(
            "com.android.systemui.qs.MiuiQS",
            "com.android.systemui.plugins.miui.qs.MiuiQS",
        )

        private fun identity(value: Any?): String =
            if (value == null) {
                "<null>"
            } else {
                "${value.javaClass.name}@${System.identityHashCode(value).toString(16)}"
            }
    }
}
