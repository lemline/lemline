// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.runner.common.messaging.MessageSubscriberMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Provides micrometer metrics for monitoring CloudEvent message processing.
 */
@Singleton
internal class CloudEventSubscriberMetrics @Inject constructor(
    registry: MeterRegistry
) : MessageSubscriberMetrics(registry) {
    override val METRIC_PREFIX = "lemline.messaging.cloudevents"
}
