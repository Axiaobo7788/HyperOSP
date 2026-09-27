/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only M1 diagnostics for the QS fragment hand-off and expansion wiring.
 *
 * Every interceptor calls the original method exactly once and never changes
 * its receiver, arguments, or result. Diagnostic failures are caught locally;
 * exceptions from SystemUI's original method are logged and rethrown unchanged.
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
        if (legacyClass != null) {
            runDiagnosticStage("QSFragmentLegacy wiring") {
                installLegacyWiringDiagnostics(legacyClass)
            }
        }
        if (controllerClass != null) {
            runDiagnosticStage("QuickSettingsControllerImpl") {
                installControllerDiagnostics(controllerClass)
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
            method.name == ON_FRAGMENT_VIEW_CREATED &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.count { it == String::class.java } == 1 &&
                method.parameterTypes.count { it.name == PLATFORM_FRAGMENT } == 1
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
        val fragmentIndex = method.parameterTypes.indexOfFirst { it.name == PLATFORM_FRAGMENT }
        val outerControllerField = resolveOuterControllerField(listenerClass, controllerClass)
        val qsField = resolveQsField(controllerClass)

        module.safeLog(
            "HyperOSP: diagnostic hook target found: ${signature(method)}; " +
                "tagIndex=$tagIndex fragmentIndex=$fragmentIndex " +
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

    private fun installLegacyWiringDiagnostics(legacyClass: Class<*>) {
        val delegateField = resolveLegacyDelegateField(legacyClass)
        module.safeLog(
            "HyperOSP: diagnostic QSFragmentLegacy delegate field=" +
                (delegateField?.name ?: MISSING),
        )

        LEGACY_WIRING_METHODS.forEach { methodName ->
            val methods = declaredConcreteInstanceMethods(legacyClass)
                .filter { it.name == methodName }
                .filter(::hasDiagnosticWiringSignature)

            if (methods.isEmpty()) {
                module.safeLog(
                    "HyperOSP: diagnostic hook target missing: " +
                        "$LEGACY_QS_FRAGMENT#$methodName",
                )
                logRelevantMethodInventory(legacyClass, methodName)
                return@forEach
            }

            methods.forEach { method ->
                module.installDiagnosticHook(
                    method = method,
                    hooker = LegacyWiringHooker(
                        module = module,
                        label = "QSFragmentLegacy#$methodName",
                        delegateField = delegateField,
                        limiter = LogLimiter(limitFor(methodName), LOG_EVERY_N_CALLS),
                    ),
                    label = signature(method),
                )
            }
        }
    }

    private fun installControllerDiagnostics(controllerClass: Class<*>) {
        val qsField = resolveQsField(controllerClass)
        val stateFields = CONTROLLER_STATE_FIELDS.mapNotNull { fieldName ->
            findInstanceField(controllerClass, fieldName)
        }

        module.safeLog(
            "HyperOSP: diagnostic controller fields: " +
                "qs=${qsField?.name ?: MISSING}; " +
                "state=${stateFields.joinToString { it.name }.ifEmpty { MISSING }}",
        )

        installControllerMethodDiagnostic(
            controllerClass = controllerClass,
            methodName = UPDATE_EXPANSION,
            parameterPredicate = { it.isEmpty() },
            qsField = qsField,
            stateFields = stateFields,
            limiter = LogLimiter(HIGH_FREQUENCY_INITIAL_LOGS, LOG_EVERY_N_CALLS),
        )
        installControllerMethodDiagnostic(
            controllerClass = controllerClass,
            methodName = SET_EXPANSION_HEIGHT,
            parameterPredicate = { types ->
                types.size == 1 && types.single() == Float::class.javaPrimitiveType
            },
            qsField = qsField,
            stateFields = stateFields,
            limiter = LogLimiter(HIGH_FREQUENCY_INITIAL_LOGS, LOG_EVERY_N_CALLS),
        )
        installControllerMethodDiagnostic(
            controllerClass = controllerClass,
            methodName = SET_LISTENING,
            parameterPredicate = { types ->
                types.size == 1 && types.single() == Boolean::class.javaPrimitiveType
            },
            qsField = qsField,
            stateFields = stateFields,
            limiter = LogLimiter(DEFAULT_INITIAL_LOGS, LOG_EVERY_N_CALLS),
        )
    }

    private fun installControllerMethodDiagnostic(
        controllerClass: Class<*>,
        methodName: String,
        parameterPredicate: (Array<Class<*>>) -> Boolean,
        qsField: Field?,
        stateFields: List<Field>,
        limiter: LogLimiter,
    ) {
        val compatible = declaredConcreteInstanceMethods(controllerClass).filter { method ->
            method.name == methodName &&
                method.returnType == Void.TYPE &&
                parameterPredicate(method.parameterTypes)
        }

        if (compatible.size != 1) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$QUICK_SETTINGS_CONTROLLER#$methodName " +
                    "(compatible=${compatible.size})",
            )
            logRelevantMethodInventory(controllerClass, methodName)
            return
        }

        val method = compatible.single()
        module.installDiagnosticHook(
            method = method,
            hooker = ControllerMethodHooker(
                module = module,
                label = "QuickSettingsControllerImpl#$methodName",
                qsField = qsField,
                stateFields = stateFields,
                limiter = limiter,
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

        val qsInterface = loadOptionalClass(classLoader, QS_INTERFACE)
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
        val candidate = named ?: typed.singleOrNull()
        return makeAccessible(candidate, "QuickSettingsControllerImpl mQs")
    }

    private fun resolveLegacyDelegateField(legacyClass: Class<*>): Field? {
        val fields = allInstanceFields(legacyClass)
        val named = fields.singleOrNull { it.name == LEGACY_DELEGATE_FIELD }
        val typed = fields.filter { it.type.name == QS_IMPL }
        val candidate = named ?: typed.singleOrNull()
        return makeAccessible(candidate, "QSFragmentLegacy delegate")
    }

    private fun findInstanceField(owner: Class<*>, name: String): Field? =
        makeAccessible(
            allInstanceFields(owner).singleOrNull { it.name == name },
            "${owner.name}#$name",
        )

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

    private fun hasDiagnosticWiringSignature(method: Method): Boolean {
        if (method.returnType != Void.TYPE || method.parameterTypes.isEmpty()) return false
        return when (method.name) {
            SET_HEADER_CLICKABLE,
            SET_OVERSCROLLING,
            SET_IN_SPLIT_SHADE,
            SET_LISTENING,
            -> method.parameterTypes.contentEquals(
                arrayOf(Boolean::class.javaPrimitiveType),
            )

            SET_COLLAPSE_EXPAND_ACTION -> method.parameterTypes.contentEquals(
                arrayOf(Runnable::class.java),
            )

            SET_PANEL_VIEW -> method.parameterTypes.size == 1
            SET_QS_EXPANSION -> method.parameterTypes.all { it == Float::class.javaPrimitiveType }
            ON_VIEW_CREATED -> method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == PLATFORM_VIEW &&
                method.parameterTypes[1].name == PLATFORM_BUNDLE
            else -> false
        }
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
        private val tagIndex: Int,
        private val fragmentIndex: Int,
        private val outerControllerField: Field?,
        private val qsField: Field?,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val fragment = chain.args.getOrNull(fragmentIndex)
            val tag = chain.args.getOrNull(tagIndex) as? String
            logSafely("before", chain, tag, fragment)

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

            logSafely("completed", chain, tag, fragment)
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
                        "tag=${tag ?: NULL}; " +
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
    }

    private class LegacyWiringHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val delegateField: Field?,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = limiter.next()
            val shouldLog = limiter.shouldLog(call)
            if (shouldLog) logSafely("called", call, chain)

            val result = try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original $label exception; " +
                        "rethrowing unchanged",
                    throwable,
                )
                throw throwable
            }

            if (shouldLog) logSafely("completed", call, chain)
            return result
        }

        private fun logSafely(
            phase: String,
            call: Int,
            chain: XposedInterface.Chain,
        ) {
            try {
                val receiver = chain.thisObject
                val delegate = if (receiver != null) delegateField?.get(receiver) else null
                module.safeLog(
                    "HyperOSP: diagnostic $label $phase; call=$call " +
                        "args=${safeArguments(chain.args)} " +
                        "delegate=${identity(delegate)}",
                )
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while logging $label $phase",
                    throwable,
                )
            }
        }
    }

    private class ControllerMethodHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val qsField: Field?,
        private val stateFields: List<Field>,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = limiter.next()
            val shouldLog = limiter.shouldLog(call)
            if (shouldLog) logSafely("called", call, chain)

            val result = try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original $label exception; " +
                        "rethrowing unchanged",
                    throwable,
                )
                throw throwable
            }

            if (shouldLog) logSafely("completed", call, chain)
            return result
        }

        private fun logSafely(
            phase: String,
            call: Int,
            chain: XposedInterface.Chain,
        ) {
            try {
                val controller = chain.thisObject
                val qs = if (controller != null) qsField?.get(controller) else null
                val state = stateFields.joinToString { field ->
                    "${field.name}=${safeValue(field.get(controller))}"
                }
                module.safeLog(
                    "HyperOSP: diagnostic $label $phase; call=$call " +
                        "args=${safeArguments(chain.args)} " +
                        "mQs=${identity(qs)} " +
                        "state={$state}",
                )
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while logging $label $phase",
                    throwable,
                )
            }
        }
    }

    private class LogLimiter(
        private val initialLogs: Int,
        private val everyNthCall: Int,
    ) {
        private val calls = AtomicInteger(0)

        fun next(): Int = calls.incrementAndGet()

        fun shouldLog(call: Int): Boolean =
            call <= initialLogs || (everyNthCall > 0 && call % everyNthCall == 0)
    }

    companion object {
        private const val MIUI_QS_FRAGMENT = "com.android.systemui.qs.MiuiQSFragment"
        private const val LEGACY_QS_FRAGMENT = "com.android.systemui.qs.QSFragmentLegacy"
        private const val QS_IMPL = "com.android.systemui.qs.QSImpl"
        private const val QS_INTERFACE = "com.android.systemui.plugins.qs.QS"
        private const val QUICK_SETTINGS_CONTROLLER =
            "com.android.systemui.shade.QuickSettingsControllerImpl"
        private const val QS_FRAGMENT_LISTENER =
            "com.android.systemui.shade.QuickSettingsControllerImpl\$QsFragmentListener"
        private const val PLATFORM_FRAGMENT = "android.app.Fragment"
        private const val PLATFORM_VIEW = "android.view.View"
        private const val PLATFORM_BUNDLE = "android.os.Bundle"

        private const val ON_FRAGMENT_VIEW_CREATED = "onFragmentViewCreated"
        private const val ON_VIEW_CREATED = "onViewCreated"
        private const val UPDATE_EXPANSION = "updateExpansion"
        private const val SET_EXPANSION_HEIGHT = "setExpansionHeight"
        private const val SET_PANEL_VIEW = "setPanelView"
        private const val SET_COLLAPSE_EXPAND_ACTION = "setCollapseExpandAction"
        private const val SET_HEADER_CLICKABLE = "setHeaderClickable"
        private const val SET_OVERSCROLLING = "setOverscrolling"
        private const val SET_IN_SPLIT_SHADE = "setInSplitShade"
        private const val SET_LISTENING = "setListening"
        private const val SET_QS_EXPANSION = "setQsExpansion"

        private const val OUTER_REFERENCE_FIELD = "this\$0"
        private const val QS_FIELD = "mQs"
        private const val LEGACY_DELEGATE_FIELD = "mQsImpl"
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"
        private const val NULL = "<null>"

        private const val DEFAULT_INITIAL_LOGS = 8
        private const val HIGH_FREQUENCY_INITIAL_LOGS = 12
        private const val LOG_EVERY_N_CALLS = 30

        private val LEGACY_WIRING_METHODS = listOf(
            ON_VIEW_CREATED,
            SET_PANEL_VIEW,
            SET_COLLAPSE_EXPAND_ACTION,
            SET_HEADER_CLICKABLE,
            SET_OVERSCROLLING,
            SET_IN_SPLIT_SHADE,
            SET_LISTENING,
            SET_QS_EXPANSION,
        )

        private val CONTROLLER_STATE_FIELDS = listOf(
            "mExpansionHeight",
            "mMinExpansionHeight",
            "mMaxExpansionHeight",
            "mShadeExpandedFraction",
            "mExpanded",
            "mFullyExpanded",
            "mStackScrollerOverscrolling",
            "mSplitShadeEnabled",
            "mExpansionEnabledPolicy",
            "mExpansionEnabledAmbient",
            "mIsFullWidth",
        )

        private val MIUI_QS_INTERFACE_CANDIDATES = listOf(
            "com.android.systemui.qs.MiuiQS",
            "com.android.systemui.plugins.miui.qs.MiuiQS",
        )

        private fun limitFor(methodName: String): Int =
            if (methodName == SET_QS_EXPANSION) {
                HIGH_FREQUENCY_INITIAL_LOGS
            } else {
                DEFAULT_INITIAL_LOGS
            }

        private fun safeArguments(arguments: List<Any?>): String =
            arguments.joinToString(prefix = "[", postfix = "]", transform = ::safeValue)

        private fun safeValue(value: Any?): String = when (value) {
            null -> NULL
            is Boolean, is Number, is Char, is String -> value.toString()
            else -> identity(value)
        }

        private fun identity(value: Any?): String =
            if (value == null) {
                NULL
            } else {
                "${value.javaClass.name}@${System.identityHashCode(value).toString(16)}"
            }
    }
}
