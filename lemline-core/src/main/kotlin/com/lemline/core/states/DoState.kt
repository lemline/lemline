// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import kotlin.time.Clock
import kotlin.time.Instant

data class DoState(
    override val startedAt: Instant = Clock.System.now(),
    val index: Int = -1
) : NodeState()
