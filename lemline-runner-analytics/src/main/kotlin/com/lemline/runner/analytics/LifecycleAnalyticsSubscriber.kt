// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.analytics.config.AnalyticsConfigConstants.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.common.messaging.MessageSubscriber
import io.cloudevents.CloudEvent
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

@Startup
@ApplicationScoped
internal class LifecycleAnalyticsSubscriber(
    @param:ConfigProperty(
        name = LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY,
        defaultValue = LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY_DEFAULT
    )
    override val maxConcurrency: Long,
    @param:ConfigProperty(name = LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED, defaultValue = LIFECYCLE_EVENTS_CONSUMER_ENABLED_DEFAULT)
    override val enabled: Boolean,
    @param:Channel(LIFECYCLEEVENTS_IN_CHANNEL)
    override val publisher: Publisher<Message<String>>,
    override val handler: LifecycleAnalyticsHandler,
    override val metrics: LifecycleAnalyticsSubscriberMetrics,
) : MessageSubscriber<CloudEvent>()
