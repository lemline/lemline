// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.orchestrator.context.Scope
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class BaseState {
    /**
     * When the node started execution.
     * Common to all state types.
     */
    @ExperimentalTime
    abstract val startedAt: Instant

    @Transient
    open val scope: Scope = JsonObject(mapOf())
}
