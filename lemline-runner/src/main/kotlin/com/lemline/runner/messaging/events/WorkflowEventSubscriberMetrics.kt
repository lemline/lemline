// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.runner.messaging.MessageSubscriberMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlin.time.ExperimentalTime

/**
 * Provides micrometer metrics for monitoring ingestion message processing.
 */
@Singleton
@ExperimentalTime
internal class WorkflowEventSubscriberMetrics @Inject constructor(
    registry: MeterRegistry
) : MessageSubscriberMetrics(registry) {
    override val METRIC_PREFIX = "lemline.messaging.database"
}
