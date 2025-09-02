// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.config.INGESTION_CONSUMER_CONCURRENCY
import com.lemline.runner.config.INGESTION_CONSUMER_ENABLED
import com.lemline.runner.messaging.MessageSubscriber
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher

internal const val INGESTION_IN_CHANNEL = "ingestion-in"

@ExperimentalTime
@Startup
@ApplicationScoped
internal class IngestionMessageSubscriber @OptIn(ExperimentalSerializationApi::class) constructor(
    @param:ConfigProperty(name = INGESTION_CONSUMER_CONCURRENCY) override val maxConcurrency: Long,
    @param:ConfigProperty(name = INGESTION_CONSUMER_ENABLED) override val enabled: Boolean,
    @param:Channel(INGESTION_IN_CHANNEL) override val publisher: Publisher<Message<String>>,
    override val handler: IngestionMessageHandler,
    override val metrics: IngestionMessageSubscriberMetrics,
) : MessageSubscriber()
