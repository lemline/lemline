// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instanceevents

import java.time.Instant
import java.util.UUID

data class InstanceEventFilter(
    val instanceId: UUID,
    val afterSequenceExclusive: Long? = null,
    val pageSize: Int = 50,
)

data class InstanceEventRow(
    val cursor: Long,
    val instanceId: UUID,
    val eventType: String,
    val eventTime: Instant?,
    val cloudEventJson: String,
    val namespace: String?,
)

data class InstanceEventListResult(
    val events: List<InstanceEventRow>,
    val nextCursor: Long?,
    val latestCursor: Long?,
)
