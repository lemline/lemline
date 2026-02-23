// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.common.messaging.MessageSubscriberMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
internal class LifecycleAnalyticsSubscriberMetrics @Inject constructor(
    registry: MeterRegistry
) : MessageSubscriberMetrics(registry) {
    override val METRIC_PREFIX = com.lemline.runner.common.config.LEMLINE_ANALYTICS
}
