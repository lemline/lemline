// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ForkState(
    override val startedAt: Instant = Clock.System.now(),
    override val visitCount: Int = 0,
) : TaskState()
