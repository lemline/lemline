// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.runner.messaging.MessageSubscriberMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlin.time.ExperimentalTime

/**
 * Provides micrometer metrics for monitoring instance message processing.
 */
@Singleton
@ExperimentalTime
internal class InstanceMessageSubscriberMetrics @Inject constructor(
    registry: MeterRegistry
) : MessageSubscriberMetrics(registry) {
    override val METRIC_PREFIX = "lemline.messaging.instances"
}
