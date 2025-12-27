// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * State for listen tasks, tracking the current event being processed in foreach.do.
 *
 * The eventId is set when resuming foreach.do execution for a specific event,
 * and is used to identify which event was processed when the foreach completes.
 */
@Serializable
data class ListenState(
    override val startedAt: Instant = Clock.System.now(),
    /**
     * The CloudEvent ID currently being processed in foreach.do.
     * Null when not in foreach or when listen task first starts.
     */
    val eventId: String? = null,
) : NodeState()
