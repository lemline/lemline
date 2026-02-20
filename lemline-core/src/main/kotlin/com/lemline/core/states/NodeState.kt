// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.processors.scope.Scope
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject

sealed class NodeState {

    /**
     * When the node started execution.
     * Common to all state types.
     */
    @ExperimentalTime
    abstract val startedAt: Instant

    open val scope: Scope = JsonObject(mapOf())
}
