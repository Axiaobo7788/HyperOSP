/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observation-only diagnostics for the notification-shade gesture decision.
 *
 * Every target is first enumerated from the live SystemUI ClassLoader. Hookers
 * preserve the original receiver, arguments, result, and exception, and invoke
 * the original exactly once.
 */
internal class ShadeGestureDiagnostics(
    private val module: HyperOSPModule,
) {

    fun install(classLoader: ClassLoader) {
        val panelViewController = loadOptionalClass(classLoader, NOTIFICATION_PANEL_CONTROLLER)
        val interactiveManager = loadOptionalClass(classLoader, PANEL_INTERACTIVE_MANAGER)

        if (panelViewController != null) {
            runDiagnosticStage("NotificationPanelViewController") {
                installPanelGestureDiagnostics(panelViewController, interactiveManager)
            }
        }

        if (interactiveManager != null) {
            runDiagnosticStage("PanelInteractiveManager") {
                installInteractiveManagerDiagnostics(classLoader, interactiveManager)
            }
        }

        runDiagnosticStage("sparse ShadeController collapse inventory") {
            installSparseCollapseDiagnostics(classLoader)
        }
    }

    private fun runDiagnosticStage(label: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception during shade diagnostic stage: $label",
                throwable,
            )
        }
    }

    private fun installPanelGestureDiagnostics(
        owner: Class<*>,
        interactiveManager: Class<*>?,
    ) {
        val methods = declaredConcreteInstanceMethods(owner)
        val stateFields = resolveExpandedStateFields(owner)
        val expandedHeightField = resolvePrimaryExpandedHeightField(stateFields)
        val interactiveManagerField = resolveInteractiveManagerField(owner, interactiveManager)
        val gestureTracker = GestureTracker()

        module.safeLog(
            "HyperOSP: diagnostic NPVC state inventory: fields=" +
                stateFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE } +
                "; primaryExpandedHeight=" +
                (expandedHeightField?.let(::fieldSignature) ?: MISSING) +
                "; panelInteractiveManagerField=" +
                (interactiveManagerField?.let(::fieldSignature) ?: MISSING),
        )

        NPVC_METHOD_NAMES.forEach { methodName ->
            val inventory = methods.filter { it.name == methodName }.sortedBy(::signature)
            module.safeLog(
                "HyperOSP: diagnostic NPVC method inventory: $methodName=" +
                    inventory.joinToString(transform = ::signature).ifEmpty { NONE },
            )
        }

        installHeightDiagnostics(methods, stateFields, expandedHeightField, gestureTracker)
        installEndMotionDiagnostics(
            methods = methods,
            stateFields = stateFields,
            gestureTracker = gestureTracker,
            interactiveManagerField = interactiveManagerField,
            interactiveFields = interactiveManager?.let(::resolveInteractiveFields).orEmpty(),
        )
        installFlingExpandsDiagnostics(methods, stateFields, gestureTracker)
        installFlingToHeightDiagnostics(methods, stateFields, gestureTracker)
    }

    private fun installHeightDiagnostics(
        methods: List<Method>,
        stateFields: List<Field>,
        expandedHeightField: Field?,
        gestureTracker: GestureTracker,
    ) {
        HEIGHT_METHOD_NAMES.forEach { methodName ->
            val compatible = methods.filter { method ->
                method.name == methodName &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    isPrimitiveFloatingPoint(method.parameterTypes.single())
            }

            if (compatible.isEmpty()) {
                module.safeLog(
                    "HyperOSP: diagnostic hook target missing: " +
                        "$NOTIFICATION_PANEL_CONTROLLER#$methodName; " +
                        "requires void method with one float/double argument",
                )
                return@forEach
            }

            compatible.sortedBy(::signature).forEach { method ->
                val label = signature(method)
                module.safeLog(
                    "HyperOSP: diagnostic hook target found: $label; heightIndex=0",
                )
                module.installDiagnosticHook(
                    method = method,
                    hooker = ExpandedHeightHooker(
                        module = module,
                        label = label,
                        stateFields = stateFields,
                        expandedHeightField = expandedHeightField,
                        gestureTracker = gestureTracker,
                        limiter = LogLimiter(HEIGHT_INITIAL_LOGS, HEIGHT_EVERY_N_CALLS),
                    ),
                    label = label,
                )
            }
        }
    }

    private fun installEndMotionDiagnostics(
        methods: List<Method>,
        stateFields: List<Field>,
        gestureTracker: GestureTracker,
        interactiveManagerField: Field?,
        interactiveFields: List<Field>,
    ) {
        val compatible = methods.filter { method ->
            method.name == END_MOTION_EVENT && method.returnType == Void.TYPE
        }

        if (compatible.isEmpty()) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$NOTIFICATION_PANEL_CONTROLLER#$END_MOTION_EVENT",
            )
            return
        }

        compatible.sortedBy(::signature).forEach { method ->
            val label = signature(method)
            module.safeLog("HyperOSP: diagnostic hook target found: $label")
            module.installDiagnosticHook(
                method = method,
                hooker = EndMotionEventHooker(
                    module = module,
                    label = label,
                    stateFields = stateFields,
                    gestureTracker = gestureTracker,
                    interactiveManagerField = interactiveManagerField,
                    interactiveFields = interactiveFields,
                ),
                label = label,
            )
        }
    }

    private fun installFlingExpandsDiagnostics(
        methods: List<Method>,
        stateFields: List<Field>,
        gestureTracker: GestureTracker,
    ) {
        val compatible = methods.filter { method ->
            method.name == FLING_EXPANDS && method.returnType == Boolean::class.javaPrimitiveType
        }

        if (compatible.isEmpty()) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$NOTIFICATION_PANEL_CONTROLLER#$FLING_EXPANDS returning boolean",
            )
            return
        }

        compatible.sortedBy(::signature).forEach { method ->
            val label = signature(method)
            module.safeLog("HyperOSP: diagnostic hook target found: $label")
            module.installDiagnosticHook(
                method = method,
                hooker = FlingExpandsHooker(
                    module = module,
                    label = label,
                    stateFields = stateFields,
                    gestureTracker = gestureTracker,
                ),
                label = label,
            )
        }
    }

    private fun installFlingToHeightDiagnostics(
        methods: List<Method>,
        stateFields: List<Field>,
        gestureTracker: GestureTracker,
    ) {
        val compatible = methods.filter { method ->
            method.name == FLING_TO_HEIGHT && method.returnType == Void.TYPE
        }

        if (compatible.isEmpty()) {
            module.safeLog(
                "HyperOSP: diagnostic hook target missing: " +
                    "$NOTIFICATION_PANEL_CONTROLLER#$FLING_TO_HEIGHT",
            )
            return
        }

        compatible.sortedBy(::signature).forEach { method ->
            val label = signature(method)
            val targetHeightIndex = resolveTargetHeightIndex(method)
            module.safeLog(
                "HyperOSP: diagnostic hook target found: $label; targetHeightIndex=" +
                    (targetHeightIndex?.toString() ?: UNRESOLVED),
            )
            module.installDiagnosticHook(
                method = method,
                hooker = FlingToHeightHooker(
                    module = module,
                    label = label,
                    targetHeightIndex = targetHeightIndex,
                    stateFields = stateFields,
                    gestureTracker = gestureTracker,
                ),
                label = label,
            )
        }
    }

    private fun installInteractiveManagerDiagnostics(
        classLoader: ClassLoader,
        managerClass: Class<*>,
    ) {
        val fieldCandidates = allInstanceFields(managerClass)
            .filter(::isInteractiveMember)
            .sortedBy(::fieldSignature)
        val fields = resolveInteractiveFields(managerClass)
        val readableFieldSignatures = fields.mapTo(mutableSetOf(), ::fieldSignature)
        val methods = declaredConcreteInstanceMethods(managerClass)
            .filter(::isInteractiveMember)
            .sortedBy(::signature)

        module.safeLog(
            "HyperOSP: diagnostic PanelInteractiveManager field inventory: " +
                fieldCandidates.joinToString { field ->
                    "${fieldSignature(field)}[${classifyType(field.type)}," +
                        "readable=${fieldSignature(field) in readableFieldSignatures}]"
                }.ifEmpty { NONE },
        )
        module.safeLog(
            "HyperOSP: diagnostic PanelInteractiveManager method inventory: " +
                methods.joinToString { method ->
                    "${signature(method)}[${classifyType(method.returnType)}]"
                }.ifEmpty { NONE },
        )

        INTERACTIVE_PROPERTY_NAMES.forEach { propertyName ->
            val className = "$PANEL_INTERACTIVE_MANAGER\$$propertyName\$1"
            val lambdaClass = tryLoadClass(classLoader, className)
            module.safeLog(
                "HyperOSP: diagnostic PanelInteractiveManager lambda inventory: " +
                    if (lambdaClass == null) {
                        "$className=$MISSING"
                    } else {
                        "$className(super=${lambdaClass.superclass?.name ?: NONE}, " +
                            "interfaces=${allInterfaceNames(lambdaClass).ifEmpty { NONE }})"
                    },
            )
        }

        val getterCandidates = methods.filter(::isInteractiveGetterCandidate)
        if (getterCandidates.isEmpty()) {
            module.safeLog(
                "HyperOSP: diagnostic PanelInteractiveManager property getters missing; " +
                    "runtime values will rely on NPVC field linkage",
            )
        }

        getterCandidates.forEach { method ->
            val label = signature(method)
            module.installDiagnosticHook(
                method = method,
                hooker = InteractiveGetterHooker(
                    module = module,
                    label = label,
                    stateFields = fields,
                    limiter = LogLimiter(INTERACTIVE_INITIAL_LOGS, INTERACTIVE_EVERY_N_CALLS),
                ),
                label = label,
            )
        }
    }

    private fun installSparseCollapseDiagnostics(classLoader: ClassLoader) {
        val installedSignatures = mutableSetOf<String>()

        SHADE_CONTROLLER_OWNERS.forEach { className ->
            val owner = tryLoadClass(classLoader, className) ?: run {
                module.safeLog("HyperOSP: diagnostic sparse collapse owner missing: $className")
                return@forEach
            }
            val methods = declaredConcreteInstanceMethods(owner)
                .filter { it.name in COLLAPSE_METHOD_NAMES }
                .sortedBy(::signature)

            module.safeLog(
                "HyperOSP: diagnostic sparse collapse inventory: $className=" +
                    methods.joinToString(transform = ::signature).ifEmpty { NONE },
            )
            methods.forEach methodLoop@{ method ->
                val label = signature(method)
                if (!installedSignatures.add(label)) return@methodLoop
                module.installDiagnosticHook(
                    method = method,
                    hooker = SparseCollapseHooker(
                        module = module,
                        label = label,
                        limiter = LogLimiter(COLLAPSE_INITIAL_LOGS, COLLAPSE_EVERY_N_CALLS),
                    ),
                    label = label,
                )
            }
        }
    }

    private fun resolveExpandedStateFields(owner: Class<*>): List<Field> =
        allInstanceFields(owner)
            .filter { field ->
                isPrimitiveFloatingPoint(field.type) &&
                    EXPANDED_STATE_TOKENS.any(
                        field.name.lowercase(Locale.ROOT).replace("_", "")::contains,
                    )
            }
            .mapNotNull { makeAccessible(it, "NPVC state ${it.name}") }
            .distinctBy(::fieldSignature)
            .sortedBy(::fieldSignature)

    private fun resolveInteractiveFields(owner: Class<*>): List<Field> =
        allInstanceFields(owner)
            .filter(::isInteractiveMember)
            .mapNotNull { makeAccessible(it, "PanelInteractiveManager state ${it.name}") }
            .distinctBy(::fieldSignature)
            .sortedBy(::fieldSignature)

    private fun resolvePrimaryExpandedHeightField(fields: List<Field>): Field? =
        fields.firstOrNull { field ->
            field.name.lowercase(Locale.ROOT).replace("_", "") in
                setOf("mexpandedheight", "expandedheight")
        }

    private fun resolveInteractiveManagerField(
        owner: Class<*>,
        interactiveManager: Class<*>?,
    ): Field? {
        if (interactiveManager == null) return null
        val candidates = allInstanceFields(owner).filter { field ->
            field.type == interactiveManager || interactiveManager.isAssignableFrom(field.type)
        }
        module.safeLog(
            "HyperOSP: diagnostic NPVC PanelInteractiveManager linkage inventory: " +
                candidates.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
        )
        return makeAccessible(candidates.singleOrNull(), "NPVC PanelInteractiveManager")
    }

    private fun resolveTargetHeightIndex(method: Method): Int? {
        val expected = arrayOf(
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        return if (method.parameterTypes.contentEquals(expected)) AOSP_TARGET_HEIGHT_INDEX else null
    }

    private fun isInteractiveMember(field: Field): Boolean =
        isInteractiveName(field.name) || isInteractiveName(field.type.name)

    private fun isInteractiveMember(method: Method): Boolean =
        isInteractiveName(method.name) || isInteractiveName(method.returnType.name)

    private fun isInteractiveGetterCandidate(method: Method): Boolean =
        method.parameterCount == 0 && isInteractiveName(method.name)

    private fun isInteractiveName(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT).replace("_", "")
        return INTERACTIVE_PROPERTY_NAMES.any { token ->
            normalized.contains(token.lowercase(Locale.ROOT))
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
                "HyperOSP: caught exception while making diagnostic field accessible: $label",
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
            "HyperOSP: caught exception while inspecting diagnostic methods: ${owner.name}",
            throwable,
        )
        emptyList()
    }

    private fun signature(method: Method): String =
        "${method.declaringClass.name}#${method.name}(" +
            method.parameterTypes.joinToString { it.name } +
            "):${method.returnType.name}"

    private fun fieldSignature(field: Field): String =
        "${field.declaringClass.name}#${field.name}:${field.type.name}"

    private class ExpandedHeightHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateFields: List<Field>,
        private val expandedHeightField: Field?,
        private val gestureTracker: GestureTracker,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val receiver = safely { chain.thisObject }
            val requestedHeight = safely { (chain.args.getOrNull(0) as? Number)?.toDouble() }
            val actualHeightBefore = readNumberSafely(expandedHeightField, receiver)
            val observationBefore = safely {
                actualHeightBefore?.let { gestureTracker.observeHeight(receiver, it) }
            }
            val sampledCall = limiter.next()
            val sampled = limiter.shouldLog(sampledCall)

            val result = proceedUnchanged(module, chain, label)
            val actualHeightAfter = readNumberSafely(expandedHeightField, receiver)
            val observationAfter = safely {
                actualHeightAfter?.let { gestureTracker.observeHeight(receiver, it) }
            }

            if (
                sampled || observationBefore?.importantTransition == true ||
                observationAfter?.importantTransition == true
            ) {
                safelyLog(module, "expanded height completed") {
                    module.safeLog(
                        "HyperOSP: diagnostic expanded height; phase=completed method=$label " +
                            "sample=$sampledCall requested=$requestedHeight " +
                            "actual=$actualHeightAfter " +
                            "gesture=${observationAfter ?: gestureTracker.snapshot(receiver)} " +
                            "state=${stateSnapshot(stateFields, receiver)}",
                    )
                }
            }
            return result
        }
    }

    private class EndMotionEventHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateFields: List<Field>,
        private val gestureTracker: GestureTracker,
        private val interactiveManagerField: Field?,
        private val interactiveFields: List<Field>,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val receiver = safely { chain.thisObject }
            logPhase("before", chain, receiver)
            val result = proceedUnchanged(module, chain, label)
            logPhase("completed", chain, receiver)
            try {
                gestureTracker.finishGesture(receiver)
            } catch (throwable: Throwable) {
                module.safeLog(
                    "HyperOSP: caught exception while finishing diagnostic gesture state",
                    throwable,
                )
            }
            return result
        }

        private fun logPhase(
            phase: String,
            chain: XposedInterface.Chain,
            receiver: Any?,
        ) {
            safelyLog(module, "endMotionEvent $phase") {
                val manager = safeFieldGet(interactiveManagerField, receiver)
                module.safeLog(
                    "HyperOSP: diagnostic endMotionEvent; phase=$phase method=$label " +
                        "args=${safeArguments(chain.args)} " +
                        "gesture=${gestureTracker.snapshot(receiver)} " +
                        "state=${stateSnapshot(stateFields, receiver)} " +
                        "panelInteractive=${interactiveSnapshot(interactiveFields, manager)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
        }
    }

    private class FlingExpandsHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateFields: List<Field>,
        private val gestureTracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val receiver = safely { chain.thisObject }
            safelyLog(module, "flingExpands before") {
                module.safeLog(
                    "HyperOSP: diagnostic flingExpands; phase=before method=$label " +
                        "args=${safeArguments(chain.args)} " +
                        "gesture=${gestureTracker.snapshot(receiver)} " +
                        "state=${stateSnapshot(stateFields, receiver)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }

            val result = proceedUnchanged(module, chain, label)

            safelyLog(module, "flingExpands completed") {
                module.safeLog(
                    "HyperOSP: diagnostic flingExpands; phase=completed method=$label " +
                        "originalResult=$result resultIsBoolean=${result is Boolean} " +
                        "gesture=${gestureTracker.snapshot(receiver)} " +
                        "state=${stateSnapshot(stateFields, receiver)}",
                )
            }
            return result
        }
    }

    private class FlingToHeightHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val targetHeightIndex: Int?,
        private val stateFields: List<Field>,
        private val gestureTracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val receiver = safely { chain.thisObject }
            safelyLog(module, "flingToHeight") {
                val targetHeight = targetHeightIndex?.let { index ->
                    (chain.args.getOrNull(index) as? Number)?.toDouble()
                }
                module.safeLog(
                    "HyperOSP: diagnostic flingToHeight; method=$label " +
                        "args=${safeArguments(chain.args)} " +
                        "targetHeight=${targetHeight ?: UNRESOLVED} " +
                        "targetIsZero=${targetHeight?.let { kotlin.math.abs(it) <= ZERO_EPSILON }} " +
                        "gesture=${gestureTracker.snapshot(receiver)} " +
                        "state=${stateSnapshot(stateFields, receiver)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class InteractiveGetterHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateFields: List<Field>,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = proceedUnchanged(module, chain, label)
            val call = limiter.next()
            if (limiter.shouldLog(call)) {
                safelyLog(module, "PanelInteractiveManager getter") {
                    module.safeLog(
                        "HyperOSP: diagnostic PanelInteractiveManager getter; call=$call " +
                            "method=$label result=${describeRuntimeValue(result)} " +
                            "fields=${interactiveSnapshot(stateFields, chain.thisObject)} " +
                            "systemUiCaller=${realCallerSummary()}",
                    )
                }
            }
            return result
        }
    }

    private class SparseCollapseHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val limiter: LogLimiter,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = limiter.next()
            if (limiter.shouldLog(call)) {
                safelyLog(module, "sparse collapse") {
                    module.safeLog(
                        "HyperOSP: diagnostic sparse collapse call; call=$call method=$label " +
                            "args=${safeArguments(chain.args)} " +
                            "systemUiCaller=${realCallerSummary()}",
                    )
                }
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class GestureTracker {
        private val states = Collections.synchronizedMap(IdentityHashMap<Any, GestureState>())

        fun observeHeight(receiver: Any?, height: Double): GestureSnapshot? {
            if (receiver == null) return null
            synchronized(states) {
                val state = states.getOrPut(receiver) { GestureState() }
                val previousHeight = state.lastHeight
                val wasAtZero = state.sawZero
                val isAtZero = kotlin.math.abs(height) <= ZERO_EPSILON
                if (isAtZero) state.sawZero = true
                val grewFromZeroNow = wasAtZero && !isAtZero && !state.grewFromZero
                if (grewFromZeroNow) state.grewFromZero = true
                val returnedToZeroNow = isAtZero &&
                    previousHeight != null && kotlin.math.abs(previousHeight) > ZERO_EPSILON
                state.heightUpdates += 1
                state.lastHeight = height
                state.maxHeight = maxOf(state.maxHeight, height)
                return state.snapshot(
                    previousHeight = previousHeight,
                    importantTransition = grewFromZeroNow || returnedToZeroNow,
                )
            }
        }

        fun snapshot(receiver: Any?): GestureSnapshot? {
            if (receiver == null) return null
            synchronized(states) {
                return states[receiver]?.snapshot()
            }
        }

        fun finishGesture(receiver: Any?) {
            if (receiver == null) return
            synchronized(states) {
                val state = states.getOrPut(receiver) { GestureState() }
                state.sequence += 1
                state.sawZero = state.lastHeight?.let { kotlin.math.abs(it) <= ZERO_EPSILON } == true
                state.grewFromZero = false
                state.maxHeight = state.lastHeight ?: 0.0
                state.heightUpdates = 0
            }
        }
    }

    private class GestureState {
        var sequence: Int = 1
        var sawZero: Boolean = false
        var grewFromZero: Boolean = false
        var lastHeight: Double? = null
        var maxHeight: Double = 0.0
        var heightUpdates: Int = 0

        fun snapshot(
            previousHeight: Double? = lastHeight,
            importantTransition: Boolean = false,
        ): GestureSnapshot = GestureSnapshot(
            sequence = sequence,
            updates = heightUpdates,
            previousHeight = previousHeight,
            lastHeight = lastHeight,
            maxHeight = maxHeight,
            sawZero = sawZero,
            grewFromZero = grewFromZero,
            importantTransition = importantTransition,
        )
    }

    private data class GestureSnapshot(
        val sequence: Int,
        val updates: Int,
        val previousHeight: Double?,
        val lastHeight: Double?,
        val maxHeight: Double,
        val sawZero: Boolean,
        val grewFromZero: Boolean,
        val importantTransition: Boolean,
    )

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
        private const val NOTIFICATION_PANEL_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController"
        private const val PANEL_INTERACTIVE_MANAGER =
            "com.miui.systemui.shade.PanelInteractiveManager"
        private const val END_MOTION_EVENT = "endMotionEvent"
        private const val FLING_EXPANDS = "flingExpands"
        private const val FLING_TO_HEIGHT = "flingToHeight"
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"
        private const val UNRESOLVED = "<unresolved>"
        private const val AOSP_TARGET_HEIGHT_INDEX = 2
        private const val ZERO_EPSILON = 0.001

        private const val HEIGHT_INITIAL_LOGS = 4
        private const val HEIGHT_EVERY_N_CALLS = 60
        private const val INTERACTIVE_INITIAL_LOGS = 6
        private const val INTERACTIVE_EVERY_N_CALLS = 50
        private const val COLLAPSE_INITIAL_LOGS = 2
        private const val COLLAPSE_EVERY_N_CALLS = 50
        private const val MAX_SYSTEM_UI_CALLERS = 10
        private const val MAX_FALLBACK_CALLERS = 16

        private val HEIGHT_METHOD_NAMES = setOf("setExpandedHeight", "setExpandedHeightInternal")
        private val NPVC_METHOD_NAMES = listOf(
            "setExpandedHeight",
            "setExpandedHeightInternal",
            END_MOTION_EVENT,
            FLING_EXPANDS,
            FLING_TO_HEIGHT,
        )
        private val EXPANDED_STATE_TOKENS = listOf("expandedheight", "expandedfraction")
        private val INTERACTIVE_PROPERTY_NAMES = listOf(
            "controlCenterInteractive",
            "notificationInteractive",
            "entirePanelTouchable",
        )
        private val COLLAPSE_METHOD_NAMES = setOf(
            "collapseShade",
            "animateCollapseShade",
            "instantCollapseShade",
        )
        private val SHADE_CONTROLLER_OWNERS = listOf(
            "com.android.systemui.shade.ShadeControllerImpl",
            "com.android.systemui.shade.ShadeControllerSceneImpl",
        )
        private val INTERNAL_STACK_PREFIXES = listOf(
            "io.github.axiaobo7788.hyperosp.",
            "io.github.libxposed.",
            "org.lsposed.",
            "de.robv.android.xposed.",
            "java.lang.Thread",
            "java.lang.reflect.",
            "jdk.internal.reflect.",
            "sun.reflect.",
            "dalvik.system.VMStack",
        )

        private fun isPrimitiveFloatingPoint(type: Class<*>): Boolean =
            type == Float::class.javaPrimitiveType || type == Double::class.javaPrimitiveType

        private fun proceedUnchanged(
            module: HyperOSPModule,
            chain: XposedInterface.Chain,
            label: String,
        ): Any? = try {
            chain.proceed()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: diagnostic observed original exception; method=$label; " +
                    "rethrowing unchanged",
                throwable,
            )
            throw throwable
        }

        private fun safelyLog(module: HyperOSPModule, label: String, block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                module.safeLog("HyperOSP: caught exception while logging $label", throwable)
            }
        }

        private fun <T> safely(block: () -> T): T? = try {
            block()
        } catch (_: Throwable) {
            null
        }

        private fun stateSnapshot(fields: List<Field>, receiver: Any?): String =
            fields.joinToString(prefix = "{", postfix = "}") { field ->
                "${field.name}=${safeFieldValue(field, receiver)}"
            }

        private fun interactiveSnapshot(fields: List<Field>, receiver: Any?): String {
            if (receiver == null) return MISSING
            return fields.joinToString(prefix = "{", postfix = "}") { field ->
                "${field.name}=${describeRuntimeValue(safeFieldGet(field, receiver))}"
            }.ifEmpty { NONE }
        }

        private fun safeFieldGet(field: Field?, receiver: Any?): Any? = try {
            if (field == null || receiver == null) null else field.get(receiver)
        } catch (_: Throwable) {
            null
        }

        private fun safeFieldValue(field: Field, receiver: Any?): String = try {
            safeValue(if (receiver == null) null else field.get(receiver))
        } catch (throwable: Throwable) {
            "<unreadable:${throwable.javaClass.simpleName}>"
        }

        private fun readNumberSafely(field: Field?, receiver: Any?): Double? =
            (safeFieldGet(field, receiver) as? Number)?.toDouble()

        private fun safeArguments(arguments: List<Any?>): String =
            arguments.mapIndexed { index, value ->
                "arg$index(${value?.javaClass?.name ?: "null"})=${safeValue(value)}"
            }.joinToString(prefix = "[", postfix = "]")

        private fun safeValue(value: Any?): String = when (value) {
            null -> "<null>"
            is Boolean, is Number, is Char, is String -> value.toString()
            is MotionEvent -> safely {
                "MotionEvent(action=${value.actionMasked},x=${value.x},y=${value.y}," +
                    "eventTime=${value.eventTime})"
            } ?: identity(value)
            else -> identity(value)
        }

        private fun identity(value: Any): String =
            "${value.javaClass.name}@${System.identityHashCode(value).toString(16)}"

        private fun classifyType(type: Class<*>): String = when {
            type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType ->
                "boolean"
            implementsNamedType(type, STATE_FLOW) -> "StateFlow"
            implementsNamedType(type, FUNCTION_ZERO) -> "Function0/lambda"
            else -> "object"
        }

        private fun describeRuntimeValue(value: Any?): String = when {
            value == null -> "<null>"
            value is Boolean || value is Number || value is Char || value is String ->
                "${safeValue(value)}[${value.javaClass.name}]"
            implementsNamedType(value.javaClass, STATE_FLOW) -> {
                val stateValue = safely {
                    value.javaClass.methods.firstOrNull { method ->
                        method.name == "getValue" && method.parameterCount == 0
                    }?.invoke(value)
                }
                "${identity(value)}[StateFlow,value=${safeValue(stateValue)}]"
            }
            implementsNamedType(value.javaClass, FUNCTION_ZERO) ->
                "${identity(value)}[Function0/lambda,not-invoked]"
            else -> identity(value)
        }

        private fun implementsNamedType(type: Class<*>, expectedName: String): Boolean {
            val visited = mutableSetOf<Class<*>>()
            fun visit(current: Class<*>?): Boolean {
                if (current == null || !visited.add(current)) return false
                if (current.name == expectedName) return true
                return current.interfaces.any(::visit) || visit(current.superclass)
            }
            return visit(type)
        }

        private fun allInterfaceNames(type: Class<*>): String {
            val names = linkedSetOf<String>()
            fun visit(current: Class<*>?) {
                if (current == null) return
                current.interfaces.forEach { interfaceClass ->
                    if (names.add(interfaceClass.name)) visit(interfaceClass)
                }
                visit(current.superclass)
            }
            visit(type)
            return names.joinToString()
        }

        private fun realCallerSummary(): String = try {
            val externalFrames = Thread.currentThread().stackTrace.asSequence()
                .filterNot { frame ->
                    INTERNAL_STACK_PREFIXES.any(frame.className::startsWith)
                }
                .toList()
            val systemUiFrames = externalFrames.filter { frame ->
                frame.className.startsWith("com.android.systemui.") ||
                    frame.className.startsWith("com.miui.systemui.")
            }
            val selected = if (systemUiFrames.isNotEmpty()) {
                systemUiFrames.take(MAX_SYSTEM_UI_CALLERS)
            } else {
                externalFrames.take(MAX_FALLBACK_CALLERS)
            }
            selected.joinToString(" <- ", transform = ::formatFrame).ifEmpty { NONE }
        } catch (throwable: Throwable) {
            "<stack-unavailable:${throwable.javaClass.simpleName}>"
        }

        private fun formatFrame(frame: StackTraceElement): String =
            "${frame.className}#${frame.methodName}:${frame.lineNumber}"

        private const val STATE_FLOW = "kotlinx.coroutines.flow.StateFlow"
        private const val FUNCTION_ZERO = "kotlin.jvm.functions.Function0"
    }
}
