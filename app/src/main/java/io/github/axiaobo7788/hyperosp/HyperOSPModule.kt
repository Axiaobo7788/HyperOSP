/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 HyperOSP contributors
 */

package io.github.axiaobo7788.hyperosp

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam

class HyperOSPModule(base: XposedInterface, param: ModuleLoadedParam) :
    XposedModule(base, param) {

    init {
        log("HyperOSP: module loaded; process=${param.processName}")
    }
}
