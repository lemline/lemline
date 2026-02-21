// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.config.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_CONSUMER_CONCURRENCY_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.ANALYTICS_CONSUMER_ENABLED_DEFAULT
import com.lemline.runner.messaging.MessageSubscriber
import io.cloudevents.CloudEvent
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

@Startup
@ApplicationScoped
@IfBuildProperty(name = LIFECYCLE_EVENTS_CONSUMER_ENABLED, stringValue = "true")
internal class LifecycleAnalyticsSubscriber(
    @param:ConfigProperty(
        name = LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY,
        defaultValue = ANALYTICS_CONSUMER_CONCURRENCY_DEFAULT
    )
    override val maxConcurrency: Long,
    @param:ConfigProperty(name = LIFECYCLE_EVENTS_CONSUMER_ENABLED, defaultValue = ANALYTICS_CONSUMER_ENABLED_DEFAULT)
    override val enabled: Boolean,
    @param:Channel(LIFECYCLEEVENTS_IN_CHANNEL)
    override val publisher: Publisher<Message<String>>,
    override val handler: LifecycleAnalyticsHandler,
    override val metrics: LifecycleAnalyticsSubscriberMetrics,
) : MessageSubscriber<CloudEvent>()
