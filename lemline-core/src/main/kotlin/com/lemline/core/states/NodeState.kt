// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.core.processors.scope.Scope
import kotlin.time.Instant

sealed class NodeState {

    /**
     * When the node started execution.
     * Common to all state types.
     */
    abstract val startedAt: Instant

    open val scope: Scope = ObjectNode(mapOf())
}
