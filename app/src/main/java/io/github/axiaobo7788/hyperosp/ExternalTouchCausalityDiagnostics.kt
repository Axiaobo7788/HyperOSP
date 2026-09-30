/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import android.os.SystemClock
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observation-only diagnostics for the external notification-shade touch path.
 *
 * The target signatures and the actual mExpandedHeight/mExpandedFraction writer
 * were verified from the target MiuiSystemUI.apk before these hooks were added.
 * Every hook invokes the original exactly once and never modifies an argument,
 * result, exception, field, flow, MotionEvent, or panel state.
 */
internal class ExternalTouchCausalityDiagnostics(
    private val module: HyperOSPModule,
) {

    fun install(classLoader: ClassLoader) {
        val panelClass = loadClass(classLoader, NPVC)
        val touchClass = loadClass(classLoader, NPVC_TOUCH_HANDLER)
        val injectorClass = loadClass(classLoader, NPVC_INJECTOR)
        val outerClass = loadClass(classLoader, MIUI_SHADE_TOUCH_HANDLER)
        val writerClass = loadClass(classLoader, EXPANDED_HEIGHT_WRITER)
        val interactiveClass = loadClass(classLoader, PANEL_INTERACTIVE_MANAGER)

        if (
            panelClass == null || touchClass == null || injectorClass == null ||
            outerClass == null || writerClass == null
        ) {
            module.safeLog(
                "HyperOSP: v0.0.7 external-touch diagnostics unavailable; " +
                    "one or more required classes are missing",
            )
            return
        }

        val tracker = GestureTracker()
        val eventContext = EventContext()
        val heightRequestContext = HeightRequestContext()
        val reader = StateReader(
            module = module,
            panelClass = panelClass,
            injectorClass = injectorClass,
            outerClass = outerClass,
            interactiveClass = interactiveClass,
        )

        module.safeLog(
            "HyperOSP: v0.0.7 external-touch zero-reset diagnostics; " +
                "observationOnly=true; apkSha256=$TARGET_APK_SHA256",
        )
        module.safeLog(
            "HyperOSP: static DEX result: outer handleExternalTouch and injector " +
                "handleExternalTouch contain no expansion write; " +
                "$EXPANDED_HEIGHT_WRITER#run directly writes mExpandedHeight and " +
                "mExpandedFraction; batchApplyWindowLayoutParams invokes it synchronously",
        )
        reader.logInventory()

        stage("MiuiShadeTouchHandler external boundary") {
            installOuterBoundary(outerClass, tracker, eventContext, reader)
        }
        stage("NotificationPanel injector external boundary") {
            installInjectorBoundary(injectorClass, tracker, eventContext, reader)
        }
        stage("NotificationPanel TouchHandler boundary") {
            installTouchBoundary(touchClass, panelClass, tracker, eventContext, reader)
        }
        stage("expanded-height OEM clamp") {
            installHeightSetter(
                injectorClass,
                tracker,
                eventContext,
                heightRequestContext,
                reader,
            )
        }
        stage("expanded-height actual writer") {
            installActualWriter(
                writerClass,
                panelClass,
                tracker,
                eventContext,
                heightRequestContext,
                reader,
            )
        }
        stage("downstream endMotionEvent") {
            installEndMotion(panelClass, tracker, reader)
        }
        stage("downstream false-touch result") {
            installFalseTouch(panelClass, tracker, reader)
        }
        stage("downstream fling decision") {
            installFling(panelClass, tracker, reader)
        }
        stage("downstream fling target") {
            installFlingToHeight(panelClass, tracker, reader)
        }
    }

    private fun installOuterBoundary(
        owner: Class<*>,
        tracker: GestureTracker,
        context: EventContext,
        reader: StateReader,
    ) {
        val candidates = methods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == HANDLE_EXTERNAL_TOUCH &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == MotionEvent::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2].name == KOTLIN_FUNCTION_1
        }
        val method = requireUnique(
            "$MIUI_SHADE_TOUCH_HANDLER#$HANDLE_EXTERNAL_TOUCH" +
                "(MotionEvent,String,Function1):boolean",
            candidates,
        ) ?: return
        install(
            method,
            BoundaryHooker(
                module = module,
                label = signature(method),
                stage = "outer",
                tracker = tracker,
                eventContext = context,
                reader = reader,
                receiverKind = ReceiverKind.OUTER,
                eventIndex = 0,
            ),
        )
    }

    private fun installInjectorBoundary(
        owner: Class<*>,
        tracker: GestureTracker,
        context: EventContext,
        reader: StateReader,
    ) {
        val candidates = methods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == HANDLE_EXTERNAL_TOUCH &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
        }
        val method = requireUnique(
            "$NPVC_INJECTOR#$HANDLE_EXTERNAL_TOUCH(MotionEvent):boolean",
            candidates,
        ) ?: return
        install(
            method,
            BoundaryHooker(
                module = module,
                label = signature(method),
                stage = "injector",
                tracker = tracker,
                eventContext = context,
                reader = reader,
                receiverKind = ReceiverKind.INJECTOR,
                eventIndex = 0,
            ),
        )
    }

    private fun installTouchBoundary(
        owner: Class<*>,
        panelClass: Class<*>,
        tracker: GestureTracker,
        context: EventContext,
        reader: StateReader,
    ) {
        val panelField = exactField(owner, OUTER_REFERENCE_FIELD, panelClass)
        val candidates = methods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ON_TOUCH_EVENT &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
        }
        val method = requireUnique(
            "$NPVC_TOUCH_HANDLER#$ON_TOUCH_EVENT(MotionEvent):boolean",
            candidates,
        ) ?: return
        if (panelField == null) {
            module.safeLog(
                "HyperOSP: diagnostic target missing: $NPVC_TOUCH_HANDLER#" +
                    "$OUTER_REFERENCE_FIELD:$NPVC",
            )
            return
        }
        install(
            method,
            BoundaryHooker(
                module = module,
                label = signature(method),
                stage = "touchHandler",
                tracker = tracker,
                eventContext = context,
                reader = reader,
                receiverKind = ReceiverKind.TOUCH,
                eventIndex = 0,
                panelField = panelField,
            ),
        )
    }

    private fun installHeightSetter(
        owner: Class<*>,
        tracker: GestureTracker,
        context: EventContext,
        requestContext: HeightRequestContext,
        reader: StateReader,
    ) {
        val candidates = methods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == SET_EXPANDED_HEIGHT_INTERNAL &&
                oneFloatVoid(method)
        }
        val method = requireUnique(
            "$NPVC_INJECTOR#$SET_EXPANDED_HEIGHT_INTERNAL(float):void",
            candidates,
        ) ?: return
        install(
            method,
            HeightSetterHooker(
                module,
                signature(method),
                tracker,
                context,
                requestContext,
                reader,
            ),
        )
    }

    private fun installActualWriter(
        owner: Class<*>,
        panelClass: Class<*>,
        tracker: GestureTracker,
        context: EventContext,
        requestContext: HeightRequestContext,
        reader: StateReader,
    ) {
        val panelField = exactField(owner, WRITER_PANEL_FIELD, panelClass)
        val valueField = exactField(owner, WRITER_VALUE_FIELD, java.lang.Float.TYPE)
        val candidates = methods(owner).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == RUN &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 0
        }
        val method = requireUnique("$EXPANDED_HEIGHT_WRITER#$RUN():void", candidates) ?: return
        if (panelField == null || valueField == null) {
            module.safeLog(
                "HyperOSP: diagnostic target missing: actual writer fields; " +
                    "panel=${panelField?.let(::fieldSignature) ?: MISSING}; " +
                    "value=${valueField?.let(::fieldSignature) ?: MISSING}",
            )
            return
        }
        install(
            method,
            ActualWriterHooker(
                module,
                signature(method),
                panelField,
                valueField,
                tracker,
                context,
                requestContext,
                reader,
            ),
        )
    }

    private fun installEndMotion(
        panelClass: Class<*>,
        tracker: GestureTracker,
        reader: StateReader,
    ) {
        val candidates = methods(panelClass).mapNotNull { method ->
            resolveEndMotion(method, panelClass)
        }
        if (candidates.size != 1) {
            resolutionFailure(
                "$NPVC#endMotionEvent(MotionEvent,float,float,boolean):void",
                candidates.map(EndMotionTarget::method),
            )
            return
        }
        val target = candidates.single()
        install(
            target.method,
            EndMotionHooker(module, signature(target.method), target, tracker, reader),
        )
    }

    private fun installFalseTouch(
        panelClass: Class<*>,
        tracker: GestureTracker,
        reader: StateReader,
    ) {
        val candidates = methods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == IS_FALSE_TOUCH &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE),
                )
        }
        val method = requireUnique(
            "$NPVC#$IS_FALSE_TOUCH(float,float,int):boolean",
            candidates,
        ) ?: return
        install(method, FalseTouchHooker(module, signature(method), tracker, reader))
    }

    private fun installFling(
        panelClass: Class<*>,
        tracker: GestureTracker,
        reader: StateReader,
    ) {
        val candidates = methods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name.startsWith(FLING_PREFIX) &&
                method.name != FLING_TO_HEIGHT &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        java.lang.Float.TYPE,
                        java.lang.Float.TYPE,
                        java.lang.Boolean.TYPE,
                        java.lang.Boolean.TYPE,
                    ),
                )
        }
        val method = requireUnique(
            "$NPVC#fling(float,float,boolean,boolean):void",
            candidates,
        ) ?: return
        install(method, FlingHooker(module, signature(method), tracker, reader))
    }

    private fun installFlingToHeight(
        panelClass: Class<*>,
        tracker: GestureTracker,
        reader: StateReader,
    ) {
        val candidates = methods(panelClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == FLING_TO_HEIGHT &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        java.lang.Float.TYPE,
                        java.lang.Boolean.TYPE,
                        java.lang.Float.TYPE,
                        java.lang.Float.TYPE,
                        java.lang.Boolean.TYPE,
                    ),
                )
        }
        val method = requireUnique(
            "$NPVC#$FLING_TO_HEIGHT(float,boolean,float,float,boolean):void",
            candidates,
        ) ?: return
        install(method, FlingToHeightHooker(module, signature(method), tracker, reader))
    }

    private fun resolveEndMotion(method: Method, panelClass: Class<*>): EndMotionTarget? {
        if (!method.name.contains(END_MOTION_EVENT) || method.returnType != Void.TYPE) return null
        val types = method.parameterTypes
        val eventIndexes = types.withIndex().filter { it.value == MotionEvent::class.java }
        if (
            eventIndexes.size != 1 ||
            types.count { it == java.lang.Float.TYPE } != 2 ||
            types.count { it == java.lang.Boolean.TYPE } != 1
        ) {
            return null
        }
        return if (Modifier.isStatic(method.modifiers)) {
            val panelIndexes = types.withIndex().filter { it.value == panelClass }
            if (panelIndexes.size == 1) {
                EndMotionTarget(method, panelIndexes.single().index, eventIndexes.single().index)
            } else {
                null
            }
        } else if (method.declaringClass == panelClass) {
            EndMotionTarget(method, null, eventIndexes.single().index)
        } else {
            null
        }
    }

    private fun stage(label: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            module.safeLog(
                "HyperOSP: caught exception during v0.0.7 diagnostic stage: $label",
                throwable,
            )
        }
    }

    private fun install(method: Method, hooker: XposedInterface.Hooker) {
        module.installDiagnosticHook(method, hooker, signature(method))
    }

    private fun requireUnique(label: String, candidates: List<Method>): Method? {
        if (candidates.size == 1) return candidates.single()
        resolutionFailure(label, candidates)
        return null
    }

    private fun resolutionFailure(label: String, candidates: List<Method>) {
        module.safeLog(
            "HyperOSP: diagnostic target missing/ambiguous: $label; " +
                "compatible=${candidates.size}; inventory=" +
                candidates.joinToString(transform = ::signature).ifEmpty { NONE },
        )
    }

    private fun loadClass(classLoader: ClassLoader, className: String): Class<*>? = try {
        Class.forName(className, false, classLoader).also {
            module.safeLog("HyperOSP: diagnostic class found: $className")
        }
    } catch (throwable: Throwable) {
        module.safeLog("HyperOSP: diagnostic class missing: $className")
        module.safeLog("HyperOSP: caught exception while resolving $className", throwable)
        null
    }

    private fun methods(owner: Class<*>): List<Method> = try {
        owner.declaredMethods.filterNot { method ->
            Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)
        }
    } catch (throwable: Throwable) {
        module.safeLog("HyperOSP: caught exception while inspecting ${owner.name}", throwable)
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun exactField(owner: Class<*>, name: String, type: Class<*>): Field? = try {
        owner.declaredFields.singleOrNull { field ->
            field.name == name && field.type == type && !Modifier.isStatic(field.modifiers)
        }?.also { it.isAccessible = true }
    } catch (throwable: Throwable) {
        module.safeLog(
            "HyperOSP: caught exception while resolving field ${owner.name}#$name",
            throwable,
        )
        null
    }

    private data class EndMotionTarget(
        val method: Method,
        val panelArgumentIndex: Int?,
        val eventArgumentIndex: Int,
    )

    private enum class ReceiverKind {
        OUTER,
        INJECTOR,
        TOUCH,
    }

    private class BoundaryHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val stage: String,
        private val tracker: GestureTracker,
        private val eventContext: EventContext,
        private val reader: StateReader,
        private val receiverKind: ReceiverKind,
        private val eventIndex: Int,
        private val panelField: Field? = null,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val event = safe { chain.args.getOrNull(eventIndex) as? MotionEvent }
            val receiver = safe { chain.thisObject }
            val panel = when (receiverKind) {
                ReceiverKind.TOUCH -> safeFieldGet(panelField, receiver)
                else -> tracker.panel()
            }
            if (receiverKind == ReceiverKind.INJECTOR) tracker.bindInjector(receiver)
            if (panel != null) tracker.bindPanel(panel, reader.injectorFromPanel(panel))
            val gesture = event?.let(tracker::observeEvent) ?: tracker.snapshot()
            val action = event?.let { safe { it.actionMasked } }
            val terminal = action in TERMINAL_ACTIONS
            if (terminal) {
                safeLog(module, "$stage before") {
                    module.safeLog(
                        "HyperOSP: ${gesture.label()} external-boundary=$stage phase=before " +
                            "action=${action?.let(::actionName) ?: MISSING} method=$label " +
                            "event=${event?.let(::motionSummary) ?: MISSING} " +
                            "state=${reader.boundarySnapshot(receiverKind, receiver, panel)} " +
                            "interactive=${reader.interactiveSnapshot(panel, receiver)}",
                    )
                }
            }

            val token = event?.let { eventContext.enter(it, stage, gesture.id) }
            val result = try {
                proceedUnchanged(module, chain, label)
            } finally {
                eventContext.exit(token)
            }

            val finalPanel = if (receiverKind == ReceiverKind.TOUCH) {
                safeFieldGet(panelField, receiver)
            } else {
                tracker.panel()
            }
            val height = reader.expandedHeight(finalPanel)
            tracker.observeHeight(height)
            if (terminal) {
                safeLog(module, "$stage after") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} external-boundary=$stage " +
                            "phase=after action=${action?.let(::actionName) ?: MISSING} " +
                            "originalResult=${safeValue(result)} state=" +
                            "${reader.boundarySnapshot(receiverKind, receiver, finalPanel)} " +
                            "interactive=${reader.interactiveSnapshot(finalPanel, receiver)}",
                    )
                }
            }
            if (receiverKind == ReceiverKind.OUTER && terminal) tracker.finish(event)
            return result
        }
    }

    private class HeightSetterHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val tracker: GestureTracker,
        private val context: EventContext,
        private val requestContext: HeightRequestContext,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val injector = safe { chain.thisObject }
            tracker.bindInjector(injector)
            val panel = tracker.panel()
            val before = reader.expandedHeight(panel)
            val requested = (chain.args.singleOrNull() as? Number)?.toDouble()
            val event = context.current()
            val shouldLog = event?.action in TERMINAL_ACTIONS ||
                (before != null && before > ZERO_FROM_HEIGHT && requested != null && requested <= ZERO_TO_HEIGHT)
            if (shouldLog) {
                safeLog(module, "height clamp before") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} height-clamp phase=before " +
                            "method=$label requested=$requested actualBefore=${before ?: MISSING} " +
                            "eventContext=${event ?: MISSING} clampState=${reader.clampSnapshot(injector)} " +
                            "interactive=${reader.interactiveFromInjector(injector)}",
                    )
                }
            }
            val requestToken = requestContext.enter(requested, before, context.current())
            val result = try {
                proceedUnchanged(module, chain, label)
            } finally {
                requestContext.exit(requestToken)
            }
            val after = reader.expandedHeight(tracker.panel())
            tracker.observeHeight(after)
            if (shouldLog) {
                safeLog(module, "height clamp after") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} height-clamp phase=after " +
                            "method=$label requested=$requested actualAfter=${after ?: MISSING} " +
                            "clampState=${reader.clampSnapshot(injector)}",
                    )
                }
            }
            return result
        }
    }

    private class ActualWriterHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val panelField: Field,
        private val valueField: Field,
        private val tracker: GestureTracker,
        private val context: EventContext,
        private val requestContext: HeightRequestContext,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val writer = safe { chain.thisObject }
            val panel = safeFieldGet(panelField, writer)
            val injector = reader.injectorFromPanel(panel)
            tracker.bindPanel(panel, injector)
            val requested = (safeFieldGet(valueField, writer) as? Number)?.toDouble()
            val previous = reader.expandedHeight(panel)
            tracker.observeHeight(previous)
            val result = proceedUnchanged(module, chain, label)
            val current = reader.expandedHeight(panel)
            tracker.observeHeight(current)

            if (
                previous != null && current != null &&
                previous > ZERO_FROM_HEIGHT && current <= ZERO_TO_HEIGHT &&
                tracker.markZeroCrossing()
            ) {
                val event = context.current()
                val request = requestContext.current()
                safeLog(module, "actual zero crossing") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} ZERO-CROSSING " +
                            "actualWriter=$label previous=$previous new=$current " +
                            "originalRequested=${request?.requested ?: MISSING} " +
                            "setterActualBefore=${request?.actualBefore ?: MISSING} " +
                            "effectiveRequested=$requested action=" +
                            "${event?.action?.let(::actionName) ?: MISSING} " +
                            "eventContext=${event ?: MISSING} " +
                            "state=${reader.panelSnapshot(panel)} " +
                            "clampState=${reader.clampSnapshot(injector)} " +
                            "interactive=${reader.interactiveFromInjector(injector)} " +
                            "systemUiCaller=${realCallerSummary()}",
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
        private val tracker: GestureTracker,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = target.panelArgumentIndex?.let(chain.args::getOrNull)
                ?: safe { chain.thisObject }
            tracker.bindPanel(panel, reader.injectorFromPanel(panel))
            val event = chain.args.getOrNull(target.eventArgumentIndex) as? MotionEvent
            if (event != null) tracker.observeEvent(event)
            val log = tracker.markDownstream(END_MOTION_EVENT)
            if (log) logDownstream(module, tracker, reader, panel, label, END_MOTION_EVENT, chain.args)
            val result = proceedUnchanged(module, chain, label)
            tracker.observeHeight(reader.expandedHeight(panel))
            return result
        }
    }

    private class FalseTouchHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val tracker: GestureTracker,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safe { chain.thisObject }
            tracker.bindPanel(panel, reader.injectorFromPanel(panel))
            val result = proceedUnchanged(module, chain, label)
            if (tracker.markDownstream(IS_FALSE_TOUCH)) {
                safeLog(module, "downstream false-touch") {
                    module.safeLog(
                        "HyperOSP: ${tracker.snapshot().label()} downstream-cleanup=" +
                            "${tracker.downstreamCleanup(reader.expandedHeight(panel))} " +
                            "method=$label args=${safeArguments(chain.args)} " +
                            "originalResult=${safeValue(result)} state=${reader.panelSnapshot(panel)}",
                    )
                }
            }
            return result
        }
    }

    private class FlingHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val tracker: GestureTracker,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safe { chain.thisObject }
            tracker.bindPanel(panel, reader.injectorFromPanel(panel))
            if (tracker.markDownstream(FLING_PREFIX)) {
                logDownstream(module, tracker, reader, panel, label, "flingDecision", chain.args)
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class FlingToHeightHooker(
        private val module: HyperOSPModule,
        private val label: String,
        private val tracker: GestureTracker,
        private val reader: StateReader,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val panel = safe { chain.thisObject }
            tracker.bindPanel(panel, reader.injectorFromPanel(panel))
            if (tracker.markDownstream(FLING_TO_HEIGHT)) {
                logDownstream(module, tracker, reader, panel, label, FLING_TO_HEIGHT, chain.args)
            }
            return proceedUnchanged(module, chain, label)
        }
    }

    private class StateReader(
        private val module: HyperOSPModule,
        panelClass: Class<*>,
        injectorClass: Class<*>,
        outerClass: Class<*>,
        interactiveClass: Class<*>?,
    ) {
        private val panelFields = fields(panelClass, PANEL_STATE_FIELDS)
        private val panelByName = panelFields.associateBy(Field::getName)
        private val injectorFields = fields(injectorClass, INJECTOR_STATE_FIELDS)
        private val outerFields = fields(outerClass, OUTER_STATE_FIELDS)
        private val panelInjectorField = field(panelClass, NPVC_INJECTOR_FIELD)
        private val expandHelperField = field(injectorClass, EXPAND_HELPER_FIELD)
        private val expandVisibleField = expandHelperField?.type?.let { field(it, VISIBLE_FIELD) }
        private val expandTrackingField = expandHelperField?.type?.let { field(it, TRACKING_FIELD) }
        private val expandHeightField = expandHelperField?.type?.let { field(it, EXPAND_HEIGHT_FIELD) }
        private val managerField = interactiveClass?.let { type ->
            field(injectorClass, PANEL_INTERACTIVE_MANAGER_FIELD, type)
        }
        private val interactiveFields = interactiveClass?.let { type ->
            fields(type, INTERACTIVE_FLOW_FIELDS)
        }.orEmpty()

        fun logInventory() {
            module.safeLog(
                "HyperOSP: v0.0.7 panel fields: " +
                    panelFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
            )
            module.safeLog(
                "HyperOSP: v0.0.7 injector fields: " +
                    injectorFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE } +
                    "; expandVisible=${expandVisibleField?.let(::fieldSignature) ?: MISSING}; " +
                    "expandTracking=${expandTrackingField?.let(::fieldSignature) ?: MISSING}; " +
                    "expandHeight=${expandHeightField?.let(::fieldSignature) ?: MISSING}",
            )
            module.safeLog(
                "HyperOSP: v0.0.7 outer fields: " +
                    outerFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE },
            )
            module.safeLog(
                "HyperOSP: v0.0.7 interactive fields: " +
                    interactiveFields.joinToString(transform = ::fieldSignature).ifEmpty { NONE } +
                    "; read=getValue-only; collect=false; mutate=false",
            )
        }

        fun injectorFromPanel(panel: Any?): Any? = safeFieldGet(panelInjectorField, panel)

        fun expandedHeight(panel: Any?): Double? =
            (safeFieldGet(panelByName[EXPANDED_HEIGHT_FIELD], panel) as? Number)?.toDouble()

        fun panelSnapshot(panel: Any?): String = snapshot(panelFields, panel)

        fun injectorSnapshot(injector: Any?): String = snapshot(injectorFields, injector)

        fun outerSnapshot(outer: Any?): String = snapshot(outerFields, outer)

        fun clampSnapshot(injector: Any?): String {
            val helper = safeFieldGet(expandHelperField, injector)
            return injectorSnapshot(injector).removeSuffix("}") +
                ", expandHelper.visible=${safeValue(safeFieldGet(expandVisibleField, helper))}" +
                ", expandHelper.tracking=" +
                "${safeValue(flowValue(safeFieldGet(expandTrackingField, helper)))}" +
                ", expandHelper.expandHeight=" +
                "${safeValue(safeFieldGet(expandHeightField, helper))}}"
        }

        fun boundarySnapshot(
            kind: ReceiverKind,
            receiver: Any?,
            panel: Any?,
        ): String = when (kind) {
            ReceiverKind.OUTER -> "{outer=${outerSnapshot(receiver)},panel=${panelSnapshot(panel)}}"
            ReceiverKind.INJECTOR ->
                "{injector=${clampSnapshot(receiver)},panel=${panelSnapshot(panel)}}"
            ReceiverKind.TOUCH -> "{panel=${panelSnapshot(panel)}}"
        }

        fun interactiveSnapshot(panel: Any?, receiver: Any?): String {
            val injector = if (receiver?.javaClass?.name == NPVC_INJECTOR) {
                receiver
            } else {
                injectorFromPanel(panel)
            }
            return interactiveFromInjector(injector)
        }

        fun interactiveFromInjector(injector: Any?): String {
            val manager = safeFieldGet(managerField, injector) ?: return MISSING
            return interactiveFields.joinToString(prefix = "{", postfix = "}") { current ->
                "${current.name}=${safeValue(flowValue(safeFieldGet(current, manager)))}"
            }.ifEmpty { NONE }
        }

        private fun snapshot(targetFields: List<Field>, receiver: Any?): String {
            if (receiver == null) return MISSING
            return targetFields.joinToString(prefix = "{", postfix = "}") { current ->
                "${current.name}=${safeValue(safeFieldGet(current, receiver))}"
            }
        }

        @Suppress("DEPRECATION")
        private fun fields(owner: Class<*>, names: List<String>): List<Field> = names.mapNotNull {
            field(owner, it)
        }

        @Suppress("DEPRECATION")
        private fun field(owner: Class<*>, name: String, type: Class<*>? = null): Field? = try {
            allFields(owner).singleOrNull { current ->
                current.name == name && (type == null || current.type == type)
            }?.also { it.isAccessible = true }
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

        fun observeEvent(event: MotionEvent): GestureSnapshot = synchronized(lock) {
            val state = ensureLocked(event)
            state.lastAction = safe { event.actionMasked }
            state.snapshot()
        }

        fun finish(event: MotionEvent?) = synchronized(lock) {
            val state = current ?: return@synchronized
            if (event == null || safe { event.downTime } == state.downTime) state.active = false
        }

        fun bindPanel(panel: Any?, injector: Any?) = synchronized(lock) {
            if (panel == null) return@synchronized
            val state = current ?: detachedLocked()
            state.panel = panel
            if (injector != null) state.injector = injector
        }

        fun bindInjector(injector: Any?) = synchronized(lock) {
            if (injector == null) return@synchronized
            val state = current ?: detachedLocked()
            state.injector = injector
        }

        fun panel(): Any? = synchronized(lock) { current?.panel }

        fun observeHeight(height: Double?) = synchronized(lock) {
            if (height == null) return@synchronized
            val state = current ?: detachedLocked()
            state.lastHeight = height
            state.maxHeight = maxOf(state.maxHeight, height)
        }

        fun markZeroCrossing(): Boolean = synchronized(lock) {
            val state = current ?: detachedLocked()
            if (state.zeroCrossingLogged) return@synchronized false
            state.zeroCrossingLogged = true
            true
        }

        fun markDownstream(name: String): Boolean = synchronized(lock) {
            val state = current ?: detachedLocked()
            state.downstreamLogged.add(name)
        }

        fun downstreamCleanup(currentHeight: Double?): Boolean = synchronized(lock) {
            val state = current ?: return@synchronized false
            state.zeroCrossingLogged ||
                (state.maxHeight > ZERO_FROM_HEIGHT && currentHeight != null && currentHeight <= ZERO_TO_HEIGHT)
        }

        fun snapshot(): GestureSnapshot = synchronized(lock) {
            current?.snapshot() ?: GestureSnapshot.none()
        }

        private fun ensureLocked(event: MotionEvent): GestureState {
            val downTime = safe { event.downTime } ?: -1L
            val action = safe { event.actionMasked }
            val existing = current
            if (existing != null && existing.downTime == downTime) return existing
            if (action == MotionEvent.ACTION_DOWN || existing == null || !existing.active) {
                return GestureState(
                    id = nextId.incrementAndGet(),
                    downTime = downTime,
                    startedAt = SystemClock.uptimeMillis(),
                    active = action !in TERMINAL_ACTIONS,
                ).also { current = it }
            }
            return GestureState(
                id = nextId.incrementAndGet(),
                downTime = downTime,
                startedAt = SystemClock.uptimeMillis(),
                active = action !in TERMINAL_ACTIONS,
            ).also { current = it }
        }

        private fun detachedLocked(): GestureState = GestureState(
            id = nextId.incrementAndGet(),
            downTime = -1L,
            startedAt = SystemClock.uptimeMillis(),
            active = false,
        ).also { current = it }
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
        var panel: Any? = null
        var injector: Any? = null
        var zeroCrossingLogged: Boolean = false
        val downstreamLogged = mutableSetOf<String>()

        fun snapshot() = GestureSnapshot(
            id = id,
            downTime = downTime,
            active = active,
            ageMs = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L),
            lastAction = lastAction,
            lastHeight = lastHeight,
            maxHeight = maxHeight,
            zeroCrossingLogged = zeroCrossingLogged,
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
        val zeroCrossingLogged: Boolean,
    ) {
        fun label(): String = if (id == null) {
            "gesture#none"
        } else {
            "gesture#$id(downTime=$downTime,active=$active,ageMs=$ageMs," +
                "lastAction=${lastAction?.let(::actionName) ?: MISSING}," +
                "height=${lastHeight ?: MISSING},maxHeight=$maxHeight," +
                "zeroCross=$zeroCrossingLogged)"
        }

        companion object {
            fun none() = GestureSnapshot(null, null, false, null, null, null, 0.0, false)
        }
    }

    private class EventContext {
        private val local = ThreadLocal<Frame?>()

        fun enter(event: MotionEvent, stage: String, gestureId: Int?): Token {
            val previous = local.get()
            local.set(
                Frame(
                    action = safe { event.actionMasked },
                    downTime = safe { event.downTime },
                    eventTime = safe { event.eventTime },
                    stage = stage,
                    gestureId = gestureId,
                ),
            )
            return Token(previous)
        }

        fun exit(token: Token?) {
            if (token == null) return
            if (token.previous == null) local.remove() else local.set(token.previous)
        }

        fun current(): Frame? = local.get()

        data class Frame(
            val action: Int?,
            val downTime: Long?,
            val eventTime: Long?,
            val stage: String,
            val gestureId: Int?,
        ) {
            override fun toString(): String =
                "{action=${action?.let(::actionName) ?: MISSING},downTime=$downTime," +
                    "eventTime=$eventTime,stage=$stage,gesture=$gestureId}"
        }

        data class Token(val previous: Frame?)
    }

    private class HeightRequestContext {
        private val local = ThreadLocal<Frame?>()

        fun enter(
            requested: Double?,
            actualBefore: Double?,
            event: EventContext.Frame?,
        ): Token {
            val previous = local.get()
            local.set(Frame(requested, actualBefore, event))
            return Token(previous)
        }

        fun exit(token: Token) {
            if (token.previous == null) local.remove() else local.set(token.previous)
        }

        fun current(): Frame? = local.get()

        data class Frame(
            val requested: Double?,
            val actualBefore: Double?,
            val event: EventContext.Frame?,
        )

        data class Token(val previous: Frame?)
    }

    companion object {
        private const val NPVC =
            "com.android.systemui.shade.NotificationPanelViewController"
        private const val NPVC_TOUCH_HANDLER =
            "com.android.systemui.shade.NotificationPanelViewController\$TouchHandler"
        private const val NPVC_INJECTOR =
            "com.android.systemui.shade.NotificationPanelViewControllerInjector"
        private const val MIUI_SHADE_TOUCH_HANDLER =
            "com.miui.systemui.shade.MiuiShadeTouchHandlerImpl"
        private const val PANEL_INTERACTIVE_MANAGER =
            "com.miui.systemui.shade.PanelInteractiveManager"
        private const val EXPANDED_HEIGHT_WRITER =
            "com.android.systemui.shade.NotificationPanelViewController\$\$ExternalSyntheticLambda24"
        private const val KOTLIN_FUNCTION_1 = "kotlin.jvm.functions.Function1"

        private const val HANDLE_EXTERNAL_TOUCH = "handleExternalTouch"
        private const val ON_TOUCH_EVENT = "onTouchEvent"
        private const val SET_EXPANDED_HEIGHT_INTERNAL = "setExpandedHeightInternal\$1"
        private const val END_MOTION_EVENT = "endMotionEvent"
        private const val IS_FALSE_TOUCH = "isFalseTouch"
        private const val FLING_PREFIX = "fling"
        private const val FLING_TO_HEIGHT = "flingToHeight"
        private const val RUN = "run"

        private const val OUTER_REFERENCE_FIELD = "this\$0"
        private const val WRITER_PANEL_FIELD = "f\$0"
        private const val WRITER_VALUE_FIELD = "f\$1"
        private const val NPVC_INJECTOR_FIELD = "mNotifInjector"
        private const val PANEL_INTERACTIVE_MANAGER_FIELD = "panelInteractiveManager"
        private const val EXPAND_HELPER_FIELD = "expandHelper"
        private const val VISIBLE_FIELD = "visible"
        private const val TRACKING_FIELD = "tracking"
        private const val EXPAND_HEIGHT_FIELD = "expandHeight"
        private const val EXPANDED_HEIGHT_FIELD = "mExpandedHeight"

        private const val ZERO_FROM_HEIGHT = 100.0
        private const val ZERO_TO_HEIGHT = 1.0
        private const val MAX_SYSTEM_UI_CALLERS = 12
        private const val MAX_FALLBACK_CALLERS = 16
        private const val MISSING = "<missing>"
        private const val NONE = "<none>"
        private const val TARGET_APK_SHA256 =
            "e1ef38a00753d5dbcd864ddf4c2d6a2fbb0e9aee2a0c438c0cc32e3153b2d3c7"

        private val TERMINAL_ACTIONS = setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)
        private val PANEL_STATE_FIELDS = listOf(
            "mExpandedHeight",
            "mExpandedFraction",
            "mUseExternalTouch",
            "mTouchSlopExceeded",
            "mTouchAboveFalsingThreshold",
            "mAllowExpandForSmallExpansion",
            "mPanelClosedOnDown",
            "mCollapsedAndHeadsUpOnDown",
            "mMotionAborted",
            "mExpanding",
            "mIsFlinging",
            "mBarState",
        )
        private val INJECTOR_STATE_FIELDS = listOf(
            "currentTouchExternal",
            "handlingExternalTouch",
            "controlCenterExpanding",
            "expandingFromHeadsUp",
            "panelCollapsing",
            "panelOpening",
            "isDownOnKeyguard",
            "isFullyCollapsedOnDown",
            "isFullyExpandedOnDown",
            "isKeyguardAwayWhenDown",
            "isKeyguardLocked",
            "hidePanelPending",
            "hidePanelPendingWhenIntercept",
        )
        private val OUTER_STATE_FIELDS = listOf(
            "statusBarHandling",
            "statusBarBlocking",
            "dispatchedToCtrl",
            "shadeHandling",
            "opsBlocking",
            "shouldBlockPullDownEvent",
            "forceInterruptControlCenterEvent",
            "externalSource",
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

        private fun signature(method: Method): String =
            "${method.declaringClass.name}#${method.name}(" +
                method.parameterTypes.joinToString { it.name } +
                "):${method.returnType.name}"

        private fun fieldSignature(field: Field): String =
            "${field.declaringClass.name}#${field.name}:${field.type.name}"

        private fun oneFloatVoid(method: Method): Boolean =
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(java.lang.Float.TYPE))

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

        private fun logDownstream(
            module: HyperOSPModule,
            tracker: GestureTracker,
            reader: StateReader,
            panel: Any?,
            label: String,
            kind: String,
            args: List<Any?>,
        ) {
            safeLog(module, "downstream $kind") {
                val height = reader.expandedHeight(panel)
                module.safeLog(
                    "HyperOSP: ${tracker.snapshot().label()} downstream-cleanup=" +
                        "${tracker.downstreamCleanup(height)} kind=$kind method=$label " +
                        "args=${safeArguments(args)} state=${reader.panelSnapshot(panel)} " +
                        "interactive=${reader.interactiveSnapshot(panel, null)}",
                )
            }
        }

        private fun safeLog(module: HyperOSPModule, label: String, block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                module.safeLog("HyperOSP: caught exception while logging $label", throwable)
            }
        }

        private fun <T> safe(block: () -> T): T? = try {
            block()
        } catch (_: Throwable) {
            null
        }

        private fun safeFieldGet(field: Field?, receiver: Any?): Any? = try {
            if (field == null || receiver == null) null else field.get(receiver)
        } catch (_: Throwable) {
            null
        }

        private fun flowValue(flow: Any?): Any? = safe {
            if (flow == null) return@safe null
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

        private fun motionSummary(event: MotionEvent): String = safe {
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
