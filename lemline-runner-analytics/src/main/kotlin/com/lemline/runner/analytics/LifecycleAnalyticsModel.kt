// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import java.time.OffsetDateTime
import java.util.*

data class LifecycleAnalyticsModel(
    val eventId: String,
    val source: String,
    val type: String,
    val specVersion: String,
    val subject: String?,
    val eventTime: OffsetDateTime?,
    val dataContentType: String?,
    val dataSchema: String?,
    val workflowId: UUID?,
    val workflowNamespace: String?,
    val workflowName: String?,
    val workflowVersion: String?,
    val payload: String,
)
