// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Programmatically delivers CloudEvents to trigger listen tasks.
 *
 * Use this when testing workflows with `listen` tasks that wait for
 * external events.
 */
interface CloudEventDelivery {

    /**
     * Deliver a CloudEvent to trigger a waiting listen task.
     *
     * @param event The CloudEvent to deliver
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliver(event: CloudEvent, targetWorkflowId: String? = null)

    /**
     * Deliver a CloudEvent built from parameters.
     *
     * @param type CloudEvent type (e.g., "com.myapp.order.created")
     * @param source CloudEvent source URI (e.g., "/orders/123")
     * @param data Event payload as JSON
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliver(
        type: String,
        source: String,
        data: JsonElement,
        targetWorkflowId: String? = null
    )

    /**
     * Deliver multiple events at once.
     *
     * @param events Events to deliver
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliverAll(events: List<CloudEvent>, targetWorkflowId: String? = null)

    /**
     * Build a CloudEvent using a DSL.
     */
    fun buildEvent(block: CloudEventBuilderDsl.() -> Unit): CloudEvent
}

/**
 * DSL for building CloudEvents in tests.
 */
class CloudEventBuilderDsl {
    var id: String = UUID.randomUUID().toString()
    var type: String = ""
    var source: String = ""
    var data: JsonElement? = null
    var subject: String? = null
    var dataContentType: String = "application/json"
    var time: OffsetDateTime = OffsetDateTime.now()

    fun build(): CloudEvent {
        require(type.isNotBlank()) { "CloudEvent type is required" }
        require(source.isNotBlank()) { "CloudEvent source is required" }

        val builder = CloudEventBuilder.v1()
            .withId(id)
            .withType(type)
            .withSource(URI.create(source))
            .withTime(time)
            .withDataContentType(dataContentType)

        subject?.let { builder.withSubject(it) }
        data?.let { builder.withData(it.toString().toByteArray()) }

        return builder.build()
    }
}
