// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.common.logger.Logger
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.common.messaging.MessageHandler
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
internal class LifecycleAnalyticsHandler(
    override val metrics: LifecycleAnalyticsSubscriberMetrics,
    private val lifecycleAnalyticsService: LifecycleAnalyticsService,
) : MessageHandler<CloudEvent> {

    override val logger: Logger = logger()

    // Test hooks
    override var onCompleteTest: (Message<String>, CloudEvent?) -> Unit = { _, _ -> }
    override var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }

    override suspend fun Message<String>.deserialize(): CloudEvent {
        return CloudEventService.deserialize(payload)
    }

    override suspend fun handle(current: CloudEvent): CloudEvent? {
        when (lifecycleAnalyticsService.ingest(current)) {
            IngestResult.INSERTED -> logger.debug {
                "Lifecycle analytics inserted: id=${current.id}, source=${current.source}"
            }

            IngestResult.DUPLICATE -> logger.debug {
                "Lifecycle analytics duplicate ignored: id=${current.id}, source=${current.source}"
            }
        }

        // Sink-only handler: no downstream emission.
        return null
    }

    // Never called since handle() returns null.
    override suspend fun serialize(current: CloudEvent, next: CloudEvent): String {
        throw UnsupportedOperationException("Lifecycle analytics handler does not emit messages")
    }

    // Never called since handle() returns null.
    override suspend fun emit(payload: String, idempotentKey: IDV7) {
        throw UnsupportedOperationException("Lifecycle analytics handler does not emit messages")
    }

    // Never called since handle() returns null.
    override fun deriveIdempotentKey(next: CloudEvent): IDV7 {
        throw UnsupportedOperationException("Lifecycle analytics handler does not emit messages")
    }
}
