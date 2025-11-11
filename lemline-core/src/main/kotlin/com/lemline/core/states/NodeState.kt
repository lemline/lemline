// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.core.execution.context.Scope
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class NodeState {
    /**
     * When the node started execution.
     * Common to all state types.
     */
    abstract val startedAt: Instant

    @Transient
    open val scope: Scope = JsonObject(mapOf())

}
