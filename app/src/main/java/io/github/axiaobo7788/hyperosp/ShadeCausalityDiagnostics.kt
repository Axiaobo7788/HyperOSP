/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Observation-only diagnostics for the two shade-collapse paths verified in
 * the target MiuiSystemUI.apk.
 *
 * Targets are resolved from the live SystemUI ClassLoader and checked against
 * DEX-verified signatures. Every interceptor invokes the original exactly once
 * without changing its receiver, arguments, result, exception, or SystemUI
 * state.
 */
internal class ShadeCausalityDiagnostics(
    private val module: HyperOSPModule,
) {

    fun install(classLoader: ClassLoader) {
        val panelClass = loadOptionalClass(classLoader, NOTIFICATION_PANEL_CONTROLLER)
        val touchHandlerClass = loadOptionalClass(classLoader, TOUCH_HANDLER)
        val injectorClass = loadOptionalClass(classLoader, NOTIFICATION_PANEL_INJECTOR)
        val managerClass = loadOptionalClass(classLoader, PANEL_INTERACTIVE_MANAGER)
        val runnableClass = loadOptionalClass(classLoader, MERGED_RUNNABLE)
        val appearanceClass = loadOptionalClass(classLoader, APPEARANCE_CALLBACK)

        if (panelClass == null || injectorClass == null) {
            module.safeLog("HyperOSP: causality diagnostics unavailable; required NPVC classes missing")
            return
        }

        val tracker = GestureTracker()
        val origins = CollapseOriginContext()
        val interactiveReader = InteractiveStateReader(
            module,
            panelClass,
            injectorClass,
            managerClass,
        )
        val stateReader = PanelStateReader(module, panelClass, injectorClass, interactiveReader)

        module.safeLog(
            "HyperOSP: v0.0.6 causality diagnostics; observationOnly=true; " +
                "collapseOrigins=$ORIGIN_GESTURE,$ORIGIN_XIAOMI_RUNNABLE,$ORIGIN_OTHER",
        )
        stateReader.logInventory()
        interactiveReader.logInventory()

        stage("TouchHandler gesture entry") {
            if (touchHandlerClass == null) {
                module.safeLog("HyperOSP: causality target missing: $TOUCH_HANDLER")
            } else {
                installTouch(touchHandlerClass, stateReader, tracker)
            }
        }
        stage("NPVC endMotionEvent") {
            installEndMotion(panelClass, stateReader, tracker, origins)
        }
        stage("NPVC false-touch decision") {
            installFalseTouch(panelClass, stateReader, tracker)
        }
        stage("NPVC fling decision") {
            installFling(panelClass, stateReader, tracker, origins)
        }
        stage("NPVC fling target") {
            installFlingToHeight(panelClass, stateReader, tracker, origins)
        }
        stage("NPVC collapse entry") {
            installCollapse(panelClass, stateReader, tracker, origins)
        }
        stage("shade height sampling") {
            installHeight(panelClass, injectorClass, stateReader, tracker)
        }
        stage("empty-space collapse scheduling") {
            installEmptySpace(panelClass, stateReader, tracker, origins)
        }
        stage("R8 merged runnable roles") {
            if (runnableClass == null) {
                module.safeLog("HyperOSP: causality target missing: $MERGED_RUNNABLE")
            } else {
                installMergedRunnable(
                    runnableClass,
                    injectorClass,
                    stateReader,
                    tracker,
                    origins,
                )
            }
        }
        stage("CPU boost scheduling") {
            if (appearanceClass == null) {
                module.safeLog("HyperOSP: causality target missing: $APPEARANCE_CALLBACK")
            } else {
                installAppearance(
                    appearanceClass,
                    injectorClass,
                    stateReader,
                    tracker,
                )
            }
        }
    }

    private fun installTouch(
        owner: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
    ) {
        val inventory = declaredMethods(owner)
            .filter { method ->
                method.name in TOUCH_METHOD_NAMES ||
                    method.parameterTypes.any { it == MotionEvent::class.java }
            }
            .sortedBy(::signature)
        module.safeLog(
            "HyperOSP: causality TouchHandler inventory: " +
                inventory.joinToString(transform = ::signature).ifEmpty { NONE },
        )
        val candidates = inventory.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ON_TOUCH_EVENT &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
        }
        val outerField = exactField(owner, OUTER_REFERENCE_FIELD, NOTIFICATION_PANEL_CONTROLLER)
        if (candidates.size != 1 || outerField == null) {
            resolutionFailure("$TOUCH_HANDLER#$ON_TOUCH_EVENT", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            TouchHooker(
                module,
                signature(method),
                outerField,
                stateReader,
                tracker,
            ),
        )
    }

    private fun installEndMotion(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val named = declaredMethods(panelClass)
            .filter { it.name.contains(END_MOTION_EVENT) }
            .sortedBy(::signature)
        module.safeLog(
            "HyperOSP: causality NPVC method inventory: $END_MOTION_EVENT=" +
                named.joinToString(transform = ::signature).ifEmpty { NONE },
        )
        val candidates = named.mapNotNull { resolveEndMotion(it, panelClass) }
        if (candidates.size != 1) {
            resolutionFailure(
                "$NOTIFICATION_PANEL_CONTROLLER#$END_MOTION_EVENT",
                candidates.map(EndMotionTarget::method),
            )
            return
        }
        val target = candidates.single()
        val label = signature(target.method)
        install(
            target.method,
            EndMotionHooker(module, label, target, stateReader, tracker, origins),
        )
    }

    private fun installFalseTouch(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
    ) {
        val candidates = declaredMethods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == IS_FALSE_TOUCH &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }
        if (candidates.size != 1) {
            resolutionFailure("$NOTIFICATION_PANEL_CONTROLLER#$IS_FALSE_TOUCH", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            FalseTouchHooker(module, signature(method), stateReader, tracker),
        )
    }

    private fun installFling(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val inventory = declaredMethods(panelClass)
            .filter { it.name.startsWith(FLING_PREFIX) }
            .sortedBy(::signature)
        module.safeLog(
            "HyperOSP: causality NPVC fling inventory: " +
                inventory.joinToString(transform = ::signature).ifEmpty { NONE },
        )
        val candidates = inventory.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name != FLING_TO_HEIGHT &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                )
        }
        if (candidates.size != 1) {
            resolutionFailure(
                "$NOTIFICATION_PANEL_CONTROLLER#fling(float,float,boolean,boolean)",
                candidates,
            )
            return
        }
        val method = candidates.single()
        install(
            method,
            FlingHooker(module, signature(method), stateReader, tracker, origins),
        )
    }

    private fun installFlingToHeight(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val candidates = declaredMethods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == FLING_TO_HEIGHT &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                )
        }
        if (candidates.size != 1) {
            resolutionFailure("$NOTIFICATION_PANEL_CONTROLLER#$FLING_TO_HEIGHT", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            FlingToHeightHooker(module, signature(method), stateReader, tracker, origins),
        )
    }

    private fun installCollapse(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val candidates = declaredMethods(panelClass)
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.name == COLLAPSE &&
                    method.returnType == Void.TYPE &&
                    (
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Float::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                            ),
                        ) ||
                            method.parameterTypes.contentEquals(
                                arrayOf(
                                    Float::class.javaPrimitiveType,
                                    Boolean::class.javaPrimitiveType,
                                    Boolean::class.javaPrimitiveType,
                                ),
                            )
                        )
            }
            .sortedBy(::signature)
        if (candidates.isEmpty()) {
            resolutionFailure("$NOTIFICATION_PANEL_CONTROLLER#$COLLAPSE", candidates)
            return
        }
        module.safeLog(
            "HyperOSP: causality NPVC collapse inventory: " +
                candidates.joinToString(transform = ::signature),
        )
        candidates.forEach { method ->
            install(
                method,
                CollapseHooker(module, signature(method), stateReader, tracker, origins),
            )
        }
    }

    private fun installHeight(
        panelClass: Class<*>,
        injectorClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
    ) {
        val targets = buildList {
            addAll(
                declaredMethods(panelClass).filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.name == SET_EXPANDED_HEIGHT &&
                        oneFloatVoid(method)
                },
            )
            addAll(
                declaredMethods(injectorClass).filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.name.startsWith(SET_EXPANDED_HEIGHT_INTERNAL) &&
                        oneFloatVoid(method)
                },
            )
        }.sortedBy(::signature)
        module.safeLog(
            "HyperOSP: causality expanded-height inventory: " +
                targets.joinToString(transform = ::signature).ifEmpty { NONE },
        )
        targets.forEach { method ->
            install(
                method,
                HeightHooker(
                    module,
                    signature(method),
                    method.declaringClass == panelClass,
                    stateReader,
                    tracker,
                ),
            )
        }
    }

    private fun installEmptySpace(
        panelClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val candidates = declaredMethods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ON_EMPTY_SPACE_CLICK &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 0
        }
        if (candidates.size != 1) {
            resolutionFailure("$NOTIFICATION_PANEL_CONTROLLER#$ON_EMPTY_SPACE_CLICK", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            EmptySpaceHooker(module, signature(method), stateReader, tracker, origins),
        )
    }

    private fun installMergedRunnable(
        owner: Class<*>,
        injectorClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
        origins: CollapseOriginContext,
    ) {
        val classIdField = exactField(owner, R8_CLASS_ID_FIELD, "int")
        val outerField = exactField(owner, OUTER_REFERENCE_FIELD, injectorClass.name)
        val candidates = declaredMethods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == RUN &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 0
        }
        module.safeLog(
            "HyperOSP: causality merged-runnable inventory: " +
                "classIdField=${classIdField?.let(::fieldSignature) ?: MISSING}; " +
                "outerField=${outerField?.let(::fieldSignature) ?: MISSING}; " +
                "run=${candidates.joinToString(transform = ::signature).ifEmpty { NONE }}; " +
                "DEX roles={0=CPU_BOOST_FIELD,1=EMPTY_SPACE_COLLAPSE," +
                "2=DISMISS_VIEW,default=TOP_PADDING}",
        )
        if (classIdField == null || outerField == null || candidates.size != 1) {
            resolutionFailure("$MERGED_RUNNABLE#$RUN", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            MergedRunnableHooker(
                module,
                signature(method),
                classIdField,
                outerField,
                stateReader,
                tracker,
                origins,
            ),
        )
    }

    private fun installAppearance(
        owner: Class<*>,
        injectorClass: Class<*>,
        stateReader: PanelStateReader,
        tracker: GestureTracker,
    ) {
        val outerField = exactField(owner, OUTER_REFERENCE_FIELD, injectorClass.name)
        val candidates = declaredMethods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ON_APPEARANCE_CHANGED &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                )
        }
        if (outerField == null || candidates.size != 1) {
            resolutionFailure("$APPEARANCE_CALLBACK#$ON_APPEARANCE_CHANGED", candidates)
            return
        }
        val method = candidates.single()
        install(
            method,
            AppearanceHooker(
                module,
                signature(method),
                outerField,
                stateReader,
                tracker,
            ),
        )
    }

    private fun resolveEndMotion(method: Method, panelClass: Class<*>): EndMotionTarget? {
        if (method.returnType != Void.TYPE) return null
        val types = method.parameterTypes
        val eventIndexes = types.withIndex()
            .filter { it.value == MotionEvent::class.java }
            .map { it.index }
        if (eventIndexes.size != 1) return null
        return if (Modifier.isStatic(method.modifiers)) {
            val panelIndexes = types.withIndex()
                .filter { it.value == panelClass }
                .map { it.index }
            if (
                panelIndexes.size == 1 &&
                types.count { it == Float::class.javaPrimitiveType } == 2 &&
                types.count { it == Boolean::class.javaPrimitiveType } == 1
            ) {
                EndMotionTarget(method, panelIndexes.single(), eventIndexes.single())
            } else {
                null
            }
        } else if (
            method.declaringClass == panelClass &&
            types.count { it == Float::class.javaPrimitiveType } == 2 &&
            types.count { it == Boolean::class.javaPrimitiveType } == 1
        ) {
            EndMotionTarget(method, null, eventIndexes.single())
        } else {
            null
        }
    }

    private fun stage(label: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception during causality diagnostic stage: $label",
                throwable,
            )
        }
    }

    private fun install(method: Method, hooker: XposedInterface.Hooker) {
        module.installDiagnosticHook(method, hooker, signature(method))
    }

    private fun resolutionFailure(label: String, methods: List<Method>) {
        module.safeLog(
            "HyperOSP: causality target missing/ambiguous: $label; " +
                "compatible=${methods.size}; inventory=" +
                methods.joinToString(transform = ::signature).ifEmpty { NONE },
        )
    }

    private fun loadOptionalClass(classLoader: ClassLoader, className: String): Class<*>? =
        tryLoadClass(classLoader, className).also { result ->
            module.safeLog(
                "HyperOSP: causality class " +
                    (if (result == null) "missing" else "found") +
                    ": $className",
            )
        }

    private fun tryLoadClass(classLoader: ClassLoader, className: String): Class<*>? = try {
        Class.forName(className, false, classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun declaredMethods(owner: Class<*>): List<Method> = try {
        owner.declaredMethods.filter { method ->
            !Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)
        }
    } catch (throwable: Throwable) {
        module.safeLog(
            "HyperOSP: caught exception while inspecting causality methods: ${owner.name}",
            throwable,
        )
        emptyList()
    }

    private fun exactField(owner: Class<*>, name: String, typeName: String): Field? {
        val fields = allInstanceFields(owner).filter { field ->
            field.name == name && field.type.name == typeName
        }
        return accessible(fields.singleOrNull(), "${owner.name}#$name")
    }

    @Suppress("DEPRECATION")
    private fun accessible(field: Field?, label: String): Field? {
        if (field == null) return null
        return try {
            field.isAccessible = true
            field
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception while making causality field accessible: $label",
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

    private fun oneFloatVoid(method: Method): Boolean =
        method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))

    private data class EndMotionTarget(
        val method: Method,
        val panelArgumentIndex: Int?,
        val eventArgumentIndex: Int,
    )

    private class TouchHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val outerPanelField: Field,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val event = safely { chain.args.singleOrNull() as? MotionEvent }
            val panel = safeFieldGet(outerPanelField, safely { chain.thisObject })
            val before = event?.let(tracker::observeTouchBefore)
            if (event != null && before != null && event.actionMasked != MotionEvent.ACTION_MOVE) {
                safelyLog(module, "touch before") {
                    module.safeLog(
                        "HyperOSP: ${before.label()} touch=${actionName(event.actionMasked)} " +
                            "phase=before method=$label event=${motionSummary(event)} " +
                            "state=${stateReader.snapshot(panel)} " +
                            "interactive=${stateReader.interactiveSnapshot(panel)}",
                    )
                }
            }
            val result = proceedUnchanged(module, chain, label)
            val height = stateReader.expandedHeight(panel)
            val after = event?.let { tracker.observeTouchAfter(it, height) }
            if (
                event != null && after != null &&
                event.actionMasked == MotionEvent.ACTION_MOVE &&
                after.logMove
            ) {
                safelyLog(module, "touch sampled move") {
                    module.safeLog(
                        "HyperOSP: ${after.snapshot.label()} touch=MOVE phase=completed " +
                            "method=$label event=${motionSummary(event)} " +
                            "height=${height ?: MISSING} maxHeight=${after.snapshot.maxHeight} " +
                            "state=${stateReader.snapshot(panel)}",
                    )
                }
            }
            if (event != null && after != null && event.actionMasked in TERMINAL_ACTIONS) {
                safelyLog(module, "touch terminal completed") {
                    module.safeLog(
                        "HyperOSP: ${after.snapshot.label()} " +
                            "touch=${actionName(event.actionMasked)} phase=completed " +
                            "originalResult=$result height=${height ?: MISSING} " +
                            "maxHeight=${after.snapshot.maxHeight}",
                    )
                }
            }
            return result
        }
    }

    private class EndMotionHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val target: EndMotionTarget,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = target.panelArgumentIndex?.let(chain.args::getOrNull)
                ?: safely { chain.thisObject }
            val event = chain.args.getOrNull(target.eventArgumentIndex) as? MotionEvent
            val gesture = event?.let(tracker::ensureGesture) ?: tracker.snapshot()
            safelyLog(module, "endMotionEvent before") {
                module.safeLog(
                    "HyperOSP: ${gesture.label()} endMotionEvent phase=before " +
                        "method=$label args=${safeArguments(chain.args)} " +
                        "decisionInputs=${stateReader.decisionSnapshot(panel)} " +
                        "interactive=${stateReader.interactiveSnapshot(panel)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
            val result = origins.withOrigin(ORIGIN_GESTURE) {
                proceedUnchanged(module, chain, label)
            }
            safelyLog(module, "endMotionEvent completed") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} endMotionEvent phase=completed " +
                        "decisionInputs=${stateReader.decisionSnapshot(panel)} " +
                        "interactive=${stateReader.interactiveSnapshot(panel)}",
                )
            }
            return result
        }
    }

    private class FalseTouchHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safely { chain.thisObject }
            val result = proceedUnchanged(module, chain, label)
            val value = result as? Boolean
            tracker.recordFalseTouch(value)
            safelyLog(module, "falseTouch") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} falseTouch method=$label " +
                        "args=${safeArguments(chain.args)} originalResult=$value " +
                        "decisionInputs=${stateReader.decisionSnapshot(panel)}",
                )
            }
            return result
        }
    }

    private class FlingHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safely { chain.thisObject }
            val velocity = chain.args.getOrNull(0)
            val speedUpFactor = chain.args.getOrNull(1)
            val expand = chain.args.getOrNull(2)
            val expandBecauseOfFalsing = chain.args.getOrNull(3)
            tracker.recordFling(expand as? Boolean, velocity as? Number)
            safelyLog(module, "fling decision") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} flingDecision method=$label " +
                        "collapse-origin=${origins.current()} velocity=$velocity " +
                        "speedUpFactor=$speedUpFactor expand=$expand " +
                        "expandBecauseOfFalsing=$expandBecauseOfFalsing " +
                        "decisionInputs=${stateReader.decisionSnapshot(panel)} " +
                        "interactive=${stateReader.interactiveSnapshot(panel)}",
                )
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class FlingToHeightHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safely { chain.thisObject }
            val targetHeight = (chain.args.getOrNull(2) as? Number)?.toDouble()
            safelyLog(module, "fling target") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} flingToHeight method=$label " +
                        "collapse-origin=${origins.current()} " +
                        "velocity=${chain.args.getOrNull(0)} expand=${chain.args.getOrNull(1)} " +
                        "targetHeight=${targetHeight ?: MISSING} " +
                        "targetIsZero=${targetHeight?.let { abs(it) <= ZERO_EPSILON }} " +
                        "speedUpFactor=${chain.args.getOrNull(3)} " +
                        "dismissing=${chain.args.getOrNull(4)} " +
                        "state=${stateReader.snapshot(panel)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class CollapseHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safely { chain.thisObject }
            safelyLog(module, "collapse") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} collapse-entry method=$label " +
                        "collapse-origin=${origins.current()} args=${safeArguments(chain.args)} " +
                        "state=${stateReader.snapshot(panel)} " +
                        "interactive=${stateReader.interactiveSnapshot(panel)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class HeightHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val receiverIsPanel: Boolean,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val requested = (chain.args.singleOrNull() as? Number)?.toDouble()
            val result = proceedUnchanged(module, chain, label)
            val panel = if (receiverIsPanel) safely { chain.thisObject } else null
            val actual = stateReader.expandedHeight(panel) ?: requested
            val observation = actual?.let(tracker::observeHeight)
            if (observation?.shouldLog == true) {
                safelyLog(module, "height sample") {
                    module.safeLog(
                        "HyperOSP: ${observation.snapshot.label()} height-sample " +
                            "method=$label requested=${requested ?: MISSING} " +
                            "actual=${actual ?: MISSING} " +
                            "maxHeight=${observation.snapshot.maxHeight}",
                    )
                }
            }
            return result
        }
    }

    private class EmptySpaceHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safely { chain.thisObject }
            safelyLog(module, "empty-space scheduling") {
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} emptySpaceClick schedule-entry " +
                        "method=$label collapse-origin=${origins.current()} " +
                        "DEX-condition=barState==SHADE&&!showingFold; " +
                        "DEX-scheduler=NotificationPanelView.post(no-delay); " +
                        "scheduleCheck=${stateReader.emptySpaceScheduleCheck(panel)} " +
                        "state=${stateReader.snapshot(panel)} " +
                        "interactive=${stateReader.interactiveSnapshot(panel)} " +
                        "systemUiCaller=${realCallerSummary()}",
                )
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class MergedRunnableHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val classIdField: Field,
        private val outerInjectorField: Field,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
        private val origins: CollapseOriginContext,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val runnable = safely { chain.thisObject }
            val classId = (safeFieldGet(classIdField, runnable) as? Number)?.toInt()
            val injector = safeFieldGet(outerInjectorField, runnable)
            return when (classId) {
                CPU_BOOST_CLASS_ID -> {
                    safelyLog(module, "CPU boost runnable") {
                        module.safeLog(
                            "HyperOSP: ${tracker.snapshot().label()} mergedRunnable run " +
                                "method=$label classId=0 role=CPU_BOOST_FIELD " +
                                "DEX-action=BoostHelper.boostWithCpuFreq(2000ms,panelView); " +
                                "notACollapsePath=true " +
                                "injectorState=${stateReader.injectorSnapshot(injector)} " +
                                "systemUiCaller=${realCallerSummary()}",
                        )
                    }
                    proceedUnchanged(module, chain, label)
                }

                EMPTY_SPACE_COLLAPSE_CLASS_ID -> {
                    safelyLog(module, "empty-space collapse runnable") {
                        module.safeLog(
                            "HyperOSP: ${tracker.snapshot().label()} mergedRunnable run " +
                                "method=$label classId=1 role=EMPTY_SPACE_COLLAPSE " +
                                "collapse-origin=$ORIGIN_XIAOMI_RUNNABLE " +
                                "DEX-action=NPVC.collapse(1.0,false); " +
                                "injectorState=${stateReader.injectorSnapshot(injector)} " +
                                "interactive=${stateReader.interactiveFromInjector(injector)} " +
                                "systemUiCaller=${realCallerSummary()}",
                        )
                    }
                    origins.withOrigin(ORIGIN_XIAOMI_RUNNABLE) {
                        proceedUnchanged(module, chain, label)
                    }
                }

                else -> proceedUnchanged(module, chain, label)
            }
        }
    }

    private class AppearanceHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val outerInjectorField: Field,
        private val stateReader: PanelStateReader,
        private val tracker: GestureTracker,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val appeared = chain.args.getOrNull(0) as? Boolean
            val animate = chain.args.getOrNull(1) as? Boolean
            val injector = safeFieldGet(outerInjectorField, safely { chain.thisObject })
            if (appeared == false && animate == true) {
                safelyLog(module, "CPU boost schedule candidate") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} CPU-boost schedule-entry " +
                            "method=$label appeared=$appeared animate=$animate " +
                            "DEX-condition=appearanceChanged&&appeared==false&&animate&&" +
                            "!bgHandler.hasCallbacks(boostRunnable); " +
                            "DEX-scheduler=bgHandler.post(no-delay); " +
                            "scheduleCheck=${stateReader.cpuBoostScheduleCheck(injector, appeared, animate)} " +
                            "injectorState=${stateReader.injectorSnapshot(injector)}",
                    )
                }
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class PanelStateReader(
        private val module: HyperOSPModule,
        panelClass: Class<*>,
        injectorClass: Class<*>,
        private val interactiveReader: InteractiveStateReader,
    ) {
        private val panelFields = PANEL_STATE_FIELDS.mapNotNull { field(panelClass, it) }
        private val panelByName = panelFields.associateBy { it.name }
        private val injectorFields = INJECTOR_STATE_FIELDS.mapNotNull { field(injectorClass, it) }
        private val injectorField = field(panelClass, NPVC_INJECTOR_FIELD)
        private val flingUtilsField = field(panelClass, FLING_UTILS_FIELD)
        private val minVelocityField = flingUtilsField?.type?.let {
            field(it, MIN_VELOCITY_FIELD)
        }
        private val headsUpField = field(panelClass, HEADS_UP_HELPER_FIELD)
        private val collapseSnoozesField = headsUpField?.type?.let {
            field(it, COLLAPSE_SNOOZES_FIELD)
        }
        private val qsControllerField = field(panelClass, QS_CONTROLLER_FIELD)
        private val qsAnimatorField = qsControllerField?.type?.let {
            field(it, QS_EXPANSION_ANIMATOR_FIELD)
        }
        private val shadeRepositoryField = field(panelClass, SHADE_REPOSITORY_FIELD)
        private val legacyTrackingField = shadeRepositoryField?.type?.let {
            field(it, LEGACY_TRACKING_FIELD)
        }
        private val keyguardField = field(panelClass, KEYGUARD_STATE_CONTROLLER_FIELD)
        private val keyguardFields = keyguardField?.type?.let { type ->
            KEYGUARD_STATE_FIELDS.mapNotNull { field(type, it) }
        }.orEmpty()
        private val ambientField = field(injectorClass, AMBIENT_STATE_FIELD)
        private val panelAppearedField = ambientField?.type?.let {
            field(it, PANEL_APPEARED_FIELD)
        }
        private val foldManagerField = field(injectorClass, FOLD_MANAGER_FIELD)
        private val backgroundHandlerField = field(injectorClass, BACKGROUND_HANDLER_FIELD)
        private val boostRunnableField = field(injectorClass, BOOST_RUNNABLE_FIELD)

        fun logInventory() {
            module.safeLog(
                "HyperOSP: causality NPVC state fields: " +
                    panelFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
            )
            module.safeLog(
                "HyperOSP: causality injector state fields: " +
                    injectorFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
            )
            module.safeLog(
                "HyperOSP: causality decision linkage: " +
                    "injector=${injectorField?.let(::fieldSignature) ?: MISSING}; " +
                    "minVelocity=${minVelocityField?.let(::fieldSignature) ?: MISSING}; " +
                    "collapseSnoozes=${collapseSnoozesField?.let(::fieldSignature) ?: MISSING}; " +
                    "qsAnimator=${qsAnimatorField?.let(::fieldSignature) ?: MISSING}; " +
                    "tracking=${legacyTrackingField?.let(::fieldSignature) ?: MISSING}; " +
                    "boostHandler=${backgroundHandlerField?.let(::fieldSignature) ?: MISSING}; " +
                    "boostRunnable=${boostRunnableField?.let(::fieldSignature) ?: MISSING}; " +
                    "keyguard=" +
                    keyguardFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
            )
        }

        fun expandedHeight(panel: Any?): Double? =
            (safeFieldGet(panelByName[EXPANDED_HEIGHT_FIELD], panel) as? Number)?.toDouble()

        fun snapshot(panel: Any?): String {
            if (panel == null) return MISSING
            return panelFields.joinToString(prefix = "{", postfix = "}") { current ->
                "${current.name}=${safeValue(safeFieldGet(current, panel))}"
            }
        }

        fun decisionSnapshot(panel: Any?): String {
            if (panel == null) return MISSING
            val flingUtils = safeFieldGet(flingUtilsField, panel)
            val headsUp = safeFieldGet(headsUpField, panel)
            val qsController = safeFieldGet(qsControllerField, panel)
            val repository = safeFieldGet(shadeRepositoryField, panel)
            val trackingFlow = safeFieldGet(legacyTrackingField, repository)
            val keyguard = safeFieldGet(keyguardField, panel)
            val keyguardValues = keyguardFields.joinToString { current ->
                "${current.name}=${safeValue(safeFieldGet(current, keyguard))}"
            }
            return snapshot(panel).removeSuffix("}") +
                ", tracking=${safeValue(flowValue(trackingFlow))}" +
                ", minVelocity=${safeValue(safeFieldGet(minVelocityField, flingUtils))}" +
                ", collapseSnoozes=${safeValue(safeFieldGet(collapseSnoozesField, headsUp))}" +
                ", qsExpansionAnimator=${safeValue(safeFieldGet(qsAnimatorField, qsController))}" +
                ", keyguard={$keyguardValues}}"
        }

        fun interactiveSnapshot(panel: Any?): String =
            interactiveReader.fromPanel(panel)

        fun interactiveFromInjector(injector: Any?): String =
            interactiveReader.fromInjector(injector)

        fun emptySpaceScheduleCheck(panel: Any?): String {
            if (panel == null) return MISSING
            val barState = (safeFieldGet(panelByName[BAR_STATE_FIELD], panel) as? Number)?.toInt()
            val injector = safeFieldGet(injectorField, panel)
            val foldManager = safeFieldGet(foldManagerField, injector)
            val showingFold = foldManager?.let { current ->
                field(current.javaClass, SHOWING_FOLD_FIELD)?.let {
                    safeFieldGet(it, current) as? Boolean
                }
            }
            val willPost = if (barState != null && showingFold != null) {
                barState == SHADE_BAR_STATE && !showingFold
            } else {
                null
            }
            return "{barState=${barState ?: MISSING},showingFold=${showingFold ?: MISSING}," +
                "willPostClassId1=${willPost ?: MISSING}}"
        }

        fun cpuBoostScheduleCheck(
            injector: Any?,
            appeared: Boolean?,
            animate: Boolean?,
        ): String {
            if (injector == null) return MISSING
            val ambient = safeFieldGet(ambientField, injector)
            val previousAppeared = safeFieldGet(panelAppearedField, ambient) as? Boolean
            val handler = safeFieldGet(backgroundHandlerField, injector) as? Handler
            val runnable = safeFieldGet(boostRunnableField, injector) as? Runnable
            val hasCallbacks = safely {
                if (
                    handler == null || runnable == null ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                ) {
                    null
                } else {
                    handler.hasCallbacks(runnable)
                }
            }
            val appearanceChanged = if (appeared != null && previousAppeared != null) {
                appeared != previousAppeared
            } else {
                null
            }
            val willPost = if (
                appearanceChanged != null && appeared != null && animate != null && hasCallbacks != null
            ) {
                appearanceChanged && !appeared && animate && !hasCallbacks
            } else {
                null
            }
            return "{previousAppeared=${previousAppeared ?: MISSING}," +
                "appearanceChanged=${appearanceChanged ?: MISSING}," +
                "hasCallbacks=${hasCallbacks ?: MISSING},willPostClassId0=${willPost ?: MISSING}}"
        }

        fun injectorSnapshot(injector: Any?): String {
            if (injector == null) return MISSING
            val ambient = safeFieldGet(ambientField, injector)
            val foldManager = safeFieldGet(foldManagerField, injector)
            val foldValue = foldManager?.let { current ->
                field(current.javaClass, SHOWING_FOLD_FIELD)?.let {
                    safeFieldGet(it, current)
                }
            }
            val direct = injectorFields.joinToString { current ->
                "${current.name}=${safeValue(safeFieldGet(current, injector))}"
            }
            return "{$direct, panelAppeared=" +
                "${safeValue(safeFieldGet(panelAppearedField, ambient))}, " +
                "showingFold=${safeValue(foldValue)}}"
        }

        @Suppress("DEPRECATION")
        private fun field(owner: Class<*>, name: String): Field? = try {
            allFields(owner).singleOrNull { it.name == name }?.also {
                it.isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }

        private fun allFields(owner: Class<*>): List<Field> = buildList {
            var current: Class<*>? = owner
            while (current != null && current != Any::class.java) {
                addAll(current.declaredFields)
                current = current.superclass
            }
        }
    }

    private class InteractiveStateReader(
        private val module: HyperOSPModule,
        panelClass: Class<*>,
        injectorClass: Class<*>,
        managerClass: Class<*>?,
    ) {
        private val injectorField = field(panelClass, NPVC_INJECTOR_FIELD, injectorClass)
        private val managerField = managerClass?.let {
            field(injectorClass, PANEL_INTERACTIVE_MANAGER_FIELD, it)
        }
        private val flowFields = managerClass?.let { manager ->
            INTERACTIVE_FLOW_FIELDS.mapNotNull { field(manager, it, null) }
        }.orEmpty()

        fun logInventory() {
            module.safeLog(
                "HyperOSP: causality PanelInteractiveManager linkage: " +
                    "NPVC=${injectorField?.let(::fieldSignature) ?: MISSING} -> " +
                    "injector=${managerField?.let(::fieldSignature) ?: MISSING}; " +
                    "flows=${flowFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE }}; " +
                    "access=direct-fields-only; collect=false; mutate=false",
            )
        }

        fun fromPanel(panel: Any?): String =
            fromInjector(safeFieldGet(injectorField, panel))

        fun fromInjector(injector: Any?): String {
            val manager = safeFieldGet(managerField, injector) ?: return MISSING
            return flowFields.joinToString(prefix = "{", postfix = "}") { current ->
                "${current.name}=${safeValue(flowValue(safeFieldGet(current, manager)))}"
            }.ifEmpty { NONE }
        }

        @Suppress("DEPRECATION")
        private fun field(owner: Class<*>, name: String, type: Class<*>?): Field? = try {
            allFields(owner).singleOrNull { current ->
                current.name == name && (type == null || current.type == type)
            }?.also {
                it.isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }

        private fun allFields(owner: Class<*>): List<Field> = buildList {
            var current: Class<*>? = owner
            while (current != null && current != Any::class.java) {
                addAll(current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) })
                current = current.superclass
            }
        }
    }

    private class GestureTracker {
        private val nextId = AtomicInteger(0)
        private val lock = Any()
        private var current: GestureState? = null

        fun observeTouchBefore(event: MotionEvent): GestureSnapshot = synchronized(lock) {
            val state = ensureLocked(event)
            state.lastAction = event.actionMasked
            state.snapshot()
        }

        fun observeTouchAfter(event: MotionEvent, height: Double?): TouchObservation =
            synchronized(lock) {
                val state = ensureLocked(event)
                if (height != null) updateHeight(state, height)
                val now = SystemClock.uptimeMillis()
                val logMove = event.actionMasked == MotionEvent.ACTION_MOVE &&
                    height != null &&
                    (
                        state.lastMoveLogAt == 0L ||
                            now - state.lastMoveLogAt >= MOVE_SAMPLE_INTERVAL_MS &&
                            abs(height - state.lastMoveLogHeight) >= MOVE_HEIGHT_DELTA
                        )
                if (logMove) {
                    state.lastMoveLogAt = now
                    state.lastMoveLogHeight = height
                }
                if (event.actionMasked in TERMINAL_ACTIONS) {
                    state.active = false
                }
                TouchObservation(state.snapshot(), logMove)
            }

        fun ensureGesture(event: MotionEvent): GestureSnapshot = synchronized(lock) {
            ensureLocked(event).snapshot()
        }

        fun observeHeight(height: Double): HeightObservation = synchronized(lock) {
            val state = current ?: GestureState(
                nextId.incrementAndGet(),
                -1L,
                SystemClock.uptimeMillis(),
                false,
            ).also { current = it }
            val previous = state.lastHeight
            updateHeight(state, height)
            val zeroTransition = previous != null &&
                (abs(previous) <= ZERO_EPSILON) != (abs(height) <= ZERO_EPSILON)
            val shouldLog = state.lastHeightLog == null ||
                zeroTransition ||
                abs(height - (state.lastHeightLog ?: height)) >= HEIGHT_LOG_DELTA
            if (shouldLog) state.lastHeightLog = height
            HeightObservation(state.snapshot(), shouldLog)
        }

        fun recordFalseTouch(value: Boolean?) = synchronized(lock) {
            current?.falseTouch = value
        }

        fun recordFling(expand: Boolean?, velocity: Number?) = synchronized(lock) {
            current?.apply {
                flingExpand = expand
                flingVelocity = velocity?.toDouble()
            }
        }

        fun snapshot(): GestureSnapshot = synchronized(lock) {
            current?.snapshot() ?: GestureSnapshot.none()
        }

        private fun ensureLocked(event: MotionEvent): GestureState {
            val existing = current
            if (
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                (existing == null || existing.downTime != event.downTime)
            ) {
                return GestureState(
                    nextId.incrementAndGet(),
                    event.downTime,
                    SystemClock.uptimeMillis(),
                    true,
                ).also { current = it }
            }
            if (existing != null && existing.downTime == event.downTime) return existing
            return GestureState(
                nextId.incrementAndGet(),
                event.downTime,
                SystemClock.uptimeMillis(),
                event.actionMasked !in TERMINAL_ACTIONS,
            ).also { current = it }
        }

        private fun updateHeight(state: GestureState, height: Double) {
            state.lastHeight = height
            state.maxHeight = maxOf(state.maxHeight, height)
        }
    }

    private class GestureState(
        val id: Int,
        val downTime: Long,
        val startedAt: Long,
        var active: Boolean,
    ) {
        var lastAction: Int? = null
        var lastHeight: Double? = null
        var maxHeight: Double = 0.0
        var lastMoveLogAt: Long = 0L
        var lastMoveLogHeight: Double = 0.0
        var lastHeightLog: Double? = null
        var falseTouch: Boolean? = null
        var flingExpand: Boolean? = null
        var flingVelocity: Double? = null

        fun snapshot(): GestureSnapshot = GestureSnapshot(
            id,
            downTime,
            active,
            (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L),
            lastAction,
            lastHeight,
            maxHeight,
            falseTouch,
            flingExpand,
            flingVelocity,
        )
    }

    private data class GestureSnapshot(
        val id: Int?,
        val downTime: Long?,
        val active: Boolean,
        val ageMs: Long?,
        val lastAction: Int?,
        val lastHeight: Double?,
        val maxHeight: Double,
        val falseTouch: Boolean?,
        val flingExpand: Boolean?,
        val flingVelocity: Double?,
    ) {
        fun label(): String = if (id == null) {
            "gesture#none"
        } else {
            "gesture#$id(downTime=$downTime,active=$active,ageMs=$ageMs," +
                "lastAction=${lastAction?.let(::actionName) ?: MISSING}," +
                "height=${lastHeight ?: MISSING},maxHeight=$maxHeight," +
                "falseTouch=${falseTouch ?: MISSING}," +
                "flingExpand=${flingExpand ?: MISSING}," +
                "flingVelocity=${flingVelocity ?: MISSING})"
        }

        companion object {
            fun none() = GestureSnapshot(
                null,
                null,
                false,
                null,
                null,
                null,
                0.0,
                null,
                null,
                null,
            )
        }
    }

    private data class TouchObservation(
        val snapshot: GestureSnapshot,
        val logMove: Boolean,
    )

    private data class HeightObservation(
        val snapshot: GestureSnapshot,
        val shouldLog: Boolean,
    )

    private class CollapseOriginContext {
        private val value = ThreadLocal<String?>()

        fun current(): String = value.get() ?: ORIGIN_OTHER

        fun <T> withOrigin(origin: String, block: () -> T): T {
            val previous = value.get()
            value.set(origin)
            return try {
                block()
            } finally {
                if (previous == null) value.remove() else value.set(previous)
            }
        }
    }

    companion object {
        private fun signature(method: Method): String =
            "${method.declaringClass.name}#${method.name}(" +
                method.parameterTypes.joinToString { it.name } +
                "):${method.returnType.name}"

        private fun fieldSignature(field: Field): String =
            "${field.declaringClass.name}#${field.name}:${field.type.name}"

        private const val NOTIFICATION_PANEL_CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController"
        private const val TOUCH_HANDLER =
            "com.android.systemui.shade.NotificationPanelViewController\$TouchHandler"
        private const val NOTIFICATION_PANEL_INJECTOR =
            "com.android.systemui.shade.NotificationPanelViewControllerInjector"
        private const val PANEL_INTERACTIVE_MANAGER =
            "com.miui.systemui.shade.PanelInteractiveManager"
        private const val MERGED_RUNNABLE =
            "com.android.systemui.shade.NotificationPanelViewControllerInjector\$boostRunnable\$1"
        private const val APPEARANCE_CALLBACK =
            "com.android.systemui.shade.NotificationPanelViewControllerInjector\$2"

        private const val ON_TOUCH_EVENT = "onTouchEvent"
        private const val END_MOTION_EVENT = "endMotionEvent"
        private const val IS_FALSE_TOUCH = "isFalseTouch"
        private const val FLING_PREFIX = "fling"
        private const val FLING_TO_HEIGHT = "flingToHeight"
        private const val COLLAPSE = "collapse"
        private const val SET_EXPANDED_HEIGHT = "setExpandedHeight"
        private const val SET_EXPANDED_HEIGHT_INTERNAL = "setExpandedHeightInternal"
        private const val ON_EMPTY_SPACE_CLICK = "onEmptySpaceClick"
        private const val ON_APPEARANCE_CHANGED = "onAppearanceChanged"
        private const val RUN = "run"

        private const val OUTER_REFERENCE_FIELD = "this\$0"
        private const val R8_CLASS_ID_FIELD = "\$r8\$classId"
        private const val NPVC_INJECTOR_FIELD = "mNotifInjector"
        private const val PANEL_INTERACTIVE_MANAGER_FIELD = "panelInteractiveManager"
        private const val EXPANDED_HEIGHT_FIELD = "mExpandedHeight"
        private const val FLING_UTILS_FIELD = "mFlingAnimationUtils"
        private const val MIN_VELOCITY_FIELD = "mMinVelocityPxPerSecond"
        private const val HEADS_UP_HELPER_FIELD = "mHeadsUpTouchHelper"
        private const val COLLAPSE_SNOOZES_FIELD = "mCollapseSnoozes"
        private const val QS_CONTROLLER_FIELD = "mQsController"
        private const val QS_EXPANSION_ANIMATOR_FIELD = "mExpansionAnimator"
        private const val SHADE_REPOSITORY_FIELD = "mShadeRepository"
        private const val LEGACY_TRACKING_FIELD = "legacyShadeTracking"
        private const val KEYGUARD_STATE_CONTROLLER_FIELD = "mKeyguardStateController"
        private const val AMBIENT_STATE_FIELD = "ambientState"
        private const val PANEL_APPEARED_FIELD = "panelAppeared"
        private const val FOLD_MANAGER_FIELD = "foldManager"
        private const val SHOWING_FOLD_FIELD = "isShowingFold"
        private const val BACKGROUND_HANDLER_FIELD = "bgHandler"
        private const val BOOST_RUNNABLE_FIELD = "boostRunnable"
        private const val BAR_STATE_FIELD = "mBarState"

        private const val ORIGIN_GESTURE = "GESTURE_DECISION"
        private const val ORIGIN_XIAOMI_RUNNABLE = "XIAOMI_BOOST_RUNNABLE"
        private const val ORIGIN_OTHER = "OTHER"

        private const val CPU_BOOST_CLASS_ID = 0
        private const val EMPTY_SPACE_COLLAPSE_CLASS_ID = 1
        private const val SHADE_BAR_STATE = 0
        private const val ZERO_EPSILON = 0.001
        private const val MOVE_SAMPLE_INTERVAL_MS = 120L
        private const val MOVE_HEIGHT_DELTA = 160.0
        private const val HEIGHT_LOG_DELTA = 320.0
        private const val MAX_SYSTEM_UI_CALLERS = 10
        private const val MAX_FALLBACK_CALLERS = 16
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"

        private val TERMINAL_ACTIONS = setOf(
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
        )
        private val TOUCH_METHOD_NAMES = setOf(
            "onTouch",
            ON_TOUCH_EVENT,
            "onInterceptTouchEvent",
            "handleTouch\$1",
        )
        private val PANEL_STATE_FIELDS = listOf(
            "mExpandedHeight",
            "mExpandedFraction",
            "mTouchSlopExceeded",
            "mTouchSlop",
            "mTouchAboveFalsingThreshold",
            "mAllowExpandForSmallExpansion",
            "mDownTime",
            "mInitialExpandX",
            "mInitialExpandY",
            "mPanelClosedOnDown",
            "mCollapsedAndHeadsUpOnDown",
            "mDozingOnDown",
            "mUseExternalTouch",
            "mAnimatingOnDown",
            "mMotionAborted",
            "mOverExpansion",
            "mBarState",
            "mExpanding",
            "mIsFlinging",
        )
        private val INJECTOR_STATE_FIELDS = listOf(
            "controlCenterExpanding",
            "currentTouchExternal",
            "handlingExternalTouch",
            "expandingFromHeadsUp",
            "hidePanelPending",
            "hidePanelPendingWhenIntercept",
            "isDownOnKeyguard",
            "isFullyCollapsedOnDown",
            "isFullyExpandedOnDown",
            "isKeyguardAwayWhenDown",
            "isKeyguardLocked",
            "nssCoveredQs",
            "panelCollapsing",
            "panelOpening",
            "trackingMiniWindowHeadsUp",
        )
        private val KEYGUARD_STATE_FIELDS = listOf(
            "mShowing",
            "mCanDismissLockScreen",
            "mKeyguardFadingAway",
            "mKeyguardGoingAway",
        )
        private val INTERACTIVE_FLOW_FIELDS = listOf(
            "controlCenterInteractive",
            "notificationInteractive",
            "entirePanelTouchable",
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

        private fun safeFieldGet(field: Field?, receiver: Any?): Any? = try {
            if (field == null || receiver == null) null else field.get(receiver)
        } catch (_: Throwable) {
            null
        }

        private fun flowValue(flow: Any?): Any? = safely {
            if (flow == null) return@safely null
            flow.javaClass.methods.firstOrNull { method ->
                method.name == "getValue" && method.parameterCount == 0
            }?.invoke(flow)
        }

        private fun safeArguments(arguments: List<Any?>): String =
            arguments.mapIndexed { index, value ->
                "arg$index(${value?.javaClass?.name ?: "null"})=${safeValue(value)}"
            }.joinToString(prefix = "[", postfix = "]")

        private fun safeValue(value: Any?): String = when (value) {
            null -> "<null>"
            is Boolean, is Number, is Char, is String -> value.toString()
            is MotionEvent -> motionSummary(value)
            else -> "${value.javaClass.name}@${System.identityHashCode(value).toString(16)}"
        }

        private fun motionSummary(event: MotionEvent): String = safely {
            "MotionEvent(action=${actionName(event.actionMasked)},x=${event.x},y=${event.y}," +
                "downTime=${event.downTime},eventTime=${event.eventTime})"
        } ?: "<MotionEvent-unreadable>"

        private fun actionName(action: Int): String = when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> action.toString()
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
            selected.joinToString(" <- ") { frame ->
                "${frame.className}#${frame.methodName}:${frame.lineNumber}"
            }.ifEmpty { NONE }
        } catch (throwable: Throwable) {
            "<stack-unavailable:${throwable.javaClass.simpleName}>"
        }
    }
}
