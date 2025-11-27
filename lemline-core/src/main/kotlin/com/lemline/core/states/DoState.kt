// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@ExperimentalTime
@Serializable
data class DoState(
    override val startedAt: Instant = Clock.System.now(),
    val index: Int = -1
) : BaseState()
