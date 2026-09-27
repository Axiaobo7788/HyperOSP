/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observation-only diagnostics for the OEM notification-panel close path.
 *
 * Targets are enumerated from the live SystemUI ClassLoader. No collapse
 * method is hooked until reflection has confirmed its declaring class and full
 * runtime signature. Interceptors never replace arguments or return values and
 * invoke the original exactly once.
 */
internal class PanelCollapseDiagnostics(
    private val module: HyperOSPModule,
) {

    fun install(classLoader: ClassLoader) {
        val panelController = loadOptionalClass(classLoader, PANEL_EXPAND_CONTROLLER)
        if (panelController != null) {
            runDiagnosticStage("panel visibility") {
                installPanelVisibilityDiagnostics(panelController)
            }
            runDiagnosticStage("OEM arbitration inventory") {
                logArbitrationInventory(panelController)
            }
        }

        runDiagnosticStage("collapse call inventory") {
            installCollapseCallDiagnostics(classLoader)
        }
    }

    private fun runDiagnosticStage(label: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception during panel diagnostic stage: $label",
                throwable,
            )
        }
    }

    private fun installPanelVisibilityDiagnostics(panelClass: Class<*>) {
        val fields = allInstanceFields(panelClass)
        val panelVisibleField = resolvePanelVisibleField(fields)
        val stateFields = fields
            .filter(::isInterestingStateField)
            .mapNotNull { makeAccessible(it, "panel state ${it.name}") }
            .distinctBy { "${it.declaringClass.name}#${it.name}" }

        module.safeLog(
            "HyperOSP: diagnostic panel fields: panelVisible=" +
                "${panelVisibleField?.name ?: MISSING}; related=" +
                stateFields.joinToString { "${it.name}:${it.type.name}" }.ifEmpty { NONE },
        )

        val visibilitySetters = declaredConcreteInstanceMethods(panelClass).filter { method ->
            method.name == SET_PANEL_VISIBLE &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType),
                )
        }

        if (visibilitySetters.size != 1 || panelVisibleField == null) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$PANEL_EXPAND_CONTROLLER#$SET_PANEL_VISIBLE " +
                    "(compatible=${visibilitySetters.size}, " +
                    "panelVisibleField=${panelVisibleField?.name ?: MISSING})",
            )
            logRelevantMethodInventory(panelClass, SET_PANEL_VISIBLE)
        } else {
            val method = visibilitySetters.single()
            module.safeLog(
                "HyperOSP: diagnostic hook target found: ${signature(method)}; " +
                    "booleanIndex=0 panelVisibleField=${panelVisibleField.name}",
            )
            module.installDiagnosticHook(
                method = method,
                hooker = PanelVisibleHooker(
                    module = module,
                    panelVisibleField = panelVisibleField,
                    stateFields = stateFields,
                    limiter = LogLimiter(TRANSITION_INITIAL_LOGS, TRANSITION_EVERY_N_EVENTS),
                ),
                label = signature(method),
            )
        }

        val animationMethods = declaredConcreteInstanceMethods(panelClass).filter { method ->
            method.name == START_PANEL_VISIBLE_ANIMATION &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.count { it == Boolean::class.javaPrimitiveType } == 1
        }

        if (animationMethods.size != 1) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$PANEL_EXPAND_CONTROLLER#$START_PANEL_VISIBLE_ANIMATION " +
                    "(compatible=${animationMethods.size})",
            )
            logRelevantMethodInventory(panelClass, START_PANEL_VISIBLE_ANIMATION)
        } else {
            val method = animationMethods.single()
            val visibleIndex = method.parameterTypes.indexOfFirst {
                it == Boolean::class.javaPrimitiveType
            }
            module.safeLog(
                "HyperOSP: diagnostic hook target found: ${signature(method)}; " +
                    "directionBooleanIndex=$visibleIndex",
            )
            module.installDiagnosticHook(
                method = method,
                hooker = PanelVisibleAnimationHooker(
                    module = module,
                    visibleArgumentIndex = visibleIndex,
                    panelVisibleField = panelVisibleField,
                    limiter = LogLimiter(DEFAULT_INITIAL_LOGS, DEFAULT_EVERY_N_CALLS),
                ),
                label = signature(method),
            )
        }
    }

    private fun installCollapseCallDiagnostics(classLoader: ClassLoader) {
        val installedSignatures = mutableSetOf<String>()

        COLLAPSE_OWNER_CANDIDATES.forEach { className ->
            val owner = tryLoadClass(classLoader, className) ?: return@forEach
            val methods = declaredConcreteInstanceMethods(owner)
                .filter { it.name in COLLAPSE_METHOD_NAMES }
                .sortedBy(::signature)

            if (methods.isEmpty()) return@forEach

            module.safeLog(
                "HyperOSP: diagnostic collapse owner found: $className; methods=" +
                    methods.joinToString(transform = ::signature),
            )
            methods.forEach methodLoop@{ method ->
                val reflectedSignature = signature(method)
                if (!installedSignatures.add(reflectedSignature)) return@methodLoop

                module.installDiagnosticHook(
                    method = method,
                    hooker = CollapseCallHooker(
                        module = module,
                        label = reflectedSignature,
                        limiter = LogLimiter(DEFAULT_INITIAL_LOGS, DEFAULT_EVERY_N_CALLS),
                    ),
                    label = reflectedSignature,
                )
            }
        }

        if (installedSignatures.isEmpty()) {
            module.safeLog(
                "HyperOSP: diagnostic collapse methods missing after reflected inventory: " +
                    COLLAPSE_METHOD_NAMES.joinToString(),
            )
        }
    }

    private fun logArbitrationInventory(panelClass: Class<*>) {
        val fieldInventory = allInstanceFields(panelClass)
            .filter(::isArbitrationRelated)
            .map { field ->
                "${field.declaringClass.name}#${field.name}:${field.type.name}"
            }
            .sorted()
        val methodInventory = declaredConcreteInstanceMethods(panelClass)
            .filter(::isArbitrationRelated)
            .map(::signature)
            .sorted()

        module.safeLog(
            "HyperOSP: diagnostic OEM arbitration inventory: " +
                "fields=${fieldInventory.joinToString().ifEmpty { NONE }}; " +
                "methods=${methodInventory.joinToString().ifEmpty { NONE }}",
        )
    }

    private fun resolvePanelVisibleField(fields: List<Field>): Field? {
        val booleanFields = fields.filter { it.type == Boolean::class.javaPrimitiveType }
        val candidate = PANEL_VISIBLE_FIELD_NAMES.firstNotNullOfOrNull { expectedName ->
            booleanFields.singleOrNull { it.name == expectedName }
        }
        return makeAccessible(candidate, "panelVisible")
    }

    private fun isInterestingStateField(field: Field): Boolean {
        val name = field.name.lowercase(Locale.ROOT)
        return PANEL_STATE_FIELD_TOKENS.any(name::contains)
    }

    private fun isArbitrationRelated(field: Field): Boolean {
        val description = "${field.name}:${field.type.name}".lowercase(Locale.ROOT)
        return ARBITRATION_TOKENS.any(description::contains)
    }

    private fun isArbitrationRelated(method: Method): Boolean {
        val description = buildString {
            append(method.name)
            append(':')
            append(method.returnType.name)
            method.parameterTypes.forEach {
                append(':')
                append(it.name)
            }
        }.lowercase(Locale.ROOT)
        return ARBITRATION_TOKENS.any(description::contains)
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

    private fun allInstanceFields(owner: Class<*>): List<Field> = buildList {
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            addAll(current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) })
            current = current.superclass
        }
    }

    @Suppress("DEPRECATION")
    private fun makeAccessible(field: Field?, label: String): Field? {
        if (field == null) return null
        return try {
            field.isAccessible = true
            field
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception while making panel field accessible: $label",
                throwable,
            )
            null
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
            "HyperOSP: caught exception while inspecting panel methods: ${owner.name}",
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

    private fun signature(method: Method): String =
        "${method.declaringClass.name}#${method.name}(" +
            method.parameterTypes.joinToString { it.name } +
            "):${method.returnType.name}"

    private class PanelVisibleHooker(
        private val module: HyperOSPModule,
        private val panelVisibleField: Field,
        private val stateFields: List<Field>,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val receiver = chain.thisObject
            val before = readBooleanSafely(panelVisibleField, receiver)
            val requested = try {
                chain.args.getOrNull(0) as? Boolean
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while reading setPanelVisible arguments",
                    throwable,
                )
                null
            }
            val stack = if (before == true) safeCompactStackTrace() else null

            val result = try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original setPanelVisible exception; " +
                        "rethrowing unchanged",
                    throwable,
                )
                throw throwable
            }

            val after = readBooleanSafely(panelVisibleField, receiver)
            if (before == true && after == false) {
                val event = limiter.next()
                if (limiter.shouldLog(event)) {
                    try {
                        val state = stateFields.joinToString { field ->
                            "${field.name}=${safeFieldValue(field, receiver)}"
                        }
                        module.safeLog(
                            "HyperOSP: diagnostic panelVisible true -> false; " +
                                "event=$event requested=$requested state={$state}; " +
                                "stack=${stack ?: NONE}",
                        )
                    } catch (throwable: Throwable) {
                        module.safeLog(
                            "HyperOSP: caught exception while logging panelVisible transition",
                            throwable,
                        )
                    }
                }
            }
            return result
        }
    }

    private class PanelVisibleAnimationHooker(
        private val module: HyperOSPModule,
        private val visibleArgumentIndex: Int,
        private val panelVisibleField: Field?,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = limiter.next()
            if (limiter.shouldLog(call)) {
                try {
                    val requestedVisible = chain.args.getOrNull(visibleArgumentIndex) as? Boolean
                    val direction = when (requestedVisible) {
                        true -> "expand/show"
                        false -> "collapse/hide"
                        null -> "unknown"
                    }
                    val currentVisible = panelVisibleField?.let {
                        readBooleanSafely(it, chain.thisObject)
                    }
                    module.safeLog(
                        "HyperOSP: diagnostic startPanelVisibleAnimation; call=$call " +
                            "direction=$direction requestedVisible=$requestedVisible " +
                            "panelVisibleBefore=$currentVisible " +
                            "caller=${callerSummary()}",
                    )
                } catch (throwable: Throwable) {
                    module.safeLog(
                        "HyperOSP: caught exception while logging panel visibility animation",
                        throwable,
                    )
                }
            }

            return try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original startPanelVisibleAnimation " +
                        "exception; rethrowing unchanged",
                    throwable,
                )
                throw throwable
            }
        }
    }

    private class CollapseCallHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = limiter.next()
            if (limiter.shouldLog(call)) {
                try {
                    module.safeLog(
                        "HyperOSP: diagnostic collapse call; call=$call method=$label " +
                            "args=${safeArguments(chain.args)} caller=${callerSummary()}",
                    )
                } catch (throwable: Throwable) {
                    module.safeLog(
                        "HyperOSP: caught exception while logging collapse call: $label",
                        throwable,
                    )
                }
            }

            return try {
                chain.proceed()
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: diagnostic observed original collapse call exception; " +
                        "method=$label; rethrowing unchanged",
                    throwable,
                )
                throw throwable
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
        private const val PANEL_EXPAND_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelExpandController"
        private const val SET_PANEL_VISIBLE = "setPanelVisible"
        private const val START_PANEL_VISIBLE_ANIMATION = "startPanelVisibleAnimation"
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"

        private const val DEFAULT_INITIAL_LOGS = 8
        private const val DEFAULT_EVERY_N_CALLS = 20
        private const val TRANSITION_INITIAL_LOGS = 12
        private const val TRANSITION_EVERY_N_EVENTS = 20
        private const val MAX_STACK_FRAMES = 12
        private const val MAX_CALLER_FRAMES = 3

        private val PANEL_VISIBLE_FIELD_NAMES = listOf("panelVisible", "mPanelVisible")

        private val PANEL_STATE_FIELD_TOKENS = listOf(
            "panelvisible",
            "controlcenterinteractive",
            "notificationpanelinteractive",
            "usecontrolcenter",
            "expandedheight",
            "expansionheight",
        )

        private val ARBITRATION_TOKENS = listOf(
            "controlcenterinteractive",
            "usecontrolcenter",
            "notificationpanelinteractive",
            "miuiqs",
            "panelvisible",
        )

        private val COLLAPSE_METHOD_NAMES = setOf(
            "collapseShade",
            "animateCollapseShade",
            "instantCollapseShade",
            "collapsePanels",
        )

        private val COLLAPSE_OWNER_CANDIDATES = listOf(
            PANEL_EXPAND_CONTROLLER,
            "com.android.systemui.shade.ShadeControllerImpl",
            "com.android.systemui.shade.ShadeControllerSceneImpl",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.statusbar.CommandQueue",
            "com.android.systemui.qs.QSTileHost",
        )

        private val STACK_FILTER_PREFIXES = listOf(
            "io.github.axiaobo7788.hyperosp.",
            "io.github.libxposed.",
            "org.lsposed.",
            "java.lang.Thread",
            "java.lang.reflect.",
            "jdk.internal.reflect.",
        )

        private fun readBooleanSafely(field: Field, receiver: Any?): Boolean? = try {
            if (receiver == null) null else field.getBoolean(receiver)
        } catch (_: Throwable) {
            null
        }

        private fun safeFieldValue(field: Field, receiver: Any?): String = try {
            safeValue(if (receiver == null) null else field.get(receiver))
        } catch (throwable: Throwable) {
            "<unreadable:${throwable.javaClass.simpleName}>"
        }

        private fun safeArguments(arguments: List<Any?>): String =
            arguments.joinToString(prefix = "[", postfix = "]", transform = ::safeValue)

        private fun safeValue(value: Any?): String = when (value) {
            null -> "<null>"
            is Boolean, is Number, is Char, is String -> value.toString()
            else -> "${value.javaClass.name}@${System.identityHashCode(value).toString(16)}"
        }

        private fun callerSummary(): String = stackFrames()
            .take(MAX_CALLER_FRAMES)
            .joinToString(" <- ", transform = ::formatFrame)
            .ifEmpty { NONE }

        private fun safeCompactStackTrace(): String = try {
            stackFrames()
                .take(MAX_STACK_FRAMES)
                .joinToString(" <- ", transform = ::formatFrame)
                .ifEmpty { NONE }
        } catch (throwable: Throwable) {
            "<stack-unavailable:${throwable.javaClass.simpleName}>"
        }

        private fun stackFrames(): Sequence<StackTraceElement> =
            Thread.currentThread().stackTrace.asSequence().filterNot { frame ->
                STACK_FILTER_PREFIXES.any(frame.className::startsWith)
            }

        private fun formatFrame(frame: StackTraceElement): String =
            "${frame.className}#${frame.methodName}:${frame.lineNumber}"
    }
}
