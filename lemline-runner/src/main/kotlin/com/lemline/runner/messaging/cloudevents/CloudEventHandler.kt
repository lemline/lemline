// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.Logger
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.runner.common.messaging.CompensationException
import com.lemline.runner.common.messaging.FailureReasons
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.listeners.ListenerEventService
import com.lemline.runner.eventschedules.EventScheduleService
import com.lemline.runner.messaging.ArrivalNamespaceResolver
import com.lemline.runner.messaging.WithHealthCheckMessageHandler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Handles CloudEvent messages from the messaging layer.
 *
 * This handler is a thin adapter that:
 * 1. Deserializes incoming messages to [ReceivedCloudEvent] objects — the CloudEvent payload
 *    plus the arrival namespace resolved from the incoming broker metadata
 * 2. Guards against events arriving for namespaces this runner does not follow
 * 3. Delegates processing to [ListenerEventService.handleCloudEvent], scoped to the arrival namespace
 *
 * All business logic for CloudEvent processing (matching, inserting events,
 * evaluating until conditions) is in [ListenerEventService].
 */
@ApplicationScoped
internal class CloudEventHandler(
    override val metrics: CloudEventSubscriberMetrics,
    override val namespaceScope: NamespaceScope,
    private val arrivalNamespaceResolver: ArrivalNamespaceResolver,
) : WithHealthCheckMessageHandler<ReceivedCloudEvent> {

    override val logger: Logger = logger()

    @Inject
    internal lateinit var listenerEventService: ListenerEventService

    @Inject
    internal lateinit var eventScheduleService: EventScheduleService

    // Test hooks
    override var onCompleteTest: (Message<String>, ReceivedCloudEvent?) -> Unit = { _, _ -> }
    override var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }

    // ========================================
    // MessageHandler implementation
    // ========================================

    override suspend fun Message<String>.deserialize(): ReceivedCloudEvent = ReceivedCloudEvent(
        event = CloudEventService.deserialize(payload),
        arrivalNamespace = arrivalNamespaceResolver.resolve(this, DestinationType.CLOUDEVENTS),
    )

    override suspend fun handle(current: ReceivedCloudEvent): ReceivedCloudEvent? {
        // Foreign-namespace guard: ReceivedCloudEvent carries no WorkflowInfo, so the generic
        // guard in MessageHandler cannot fire — enforce it here on the arrival namespace.
        // Counted, logged, ACKed (via compensation) but never processed. No persistence:
        // CloudEvents are external events, re-emittable by their producer.
        if (!namespaceScope.owns(current.arrivalNamespace)) {
            logger.warn {
                "Foreign namespace '${current.arrivalNamespace}' received on the cloudevents channel — " +
                    "this runner follows ${namespaceScope.followed}. Check broker bindings; " +
                    "the event is dropped (re-emittable by its producer)."
            }
            metrics.foreignNamespace(null)
            throw CompensationException(FailureReasons.FOREIGN_NAMESPACE) {
                // No compensation persistence — see above.
            }
        }
        listenerEventService.handleCloudEvent(current.event, current.arrivalNamespace)
        eventScheduleService.handleCloudEvent(current.event, current.arrivalNamespace)
        // Return null to skip emit - CloudEvents don't produce outbound messages
        return null
    }

    // Never called since handle() returns null
    override suspend fun serialize(current: ReceivedCloudEvent, next: ReceivedCloudEvent): String {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override suspend fun emit(payload: String, idempotentKey: IDV7, workflowInfo: WorkflowInfo?) {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override fun deriveIdempotentKey(next: ReceivedCloudEvent): IDV7 {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }
}
