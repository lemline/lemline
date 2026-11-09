// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.messaging.CompensationException
import com.lemline.runner.common.messaging.FailureReasons
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.listeners.ListenerEventService
import com.lemline.runner.messaging.ArrivalNamespaceResolver
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [CloudEventHandler].
 *
 * Verifies that:
 * - deserialization pairs the CloudEvent with the arrival namespace resolved from broker metadata
 * - processing is delegated to [ListenerEventService] scoped to the arrival namespace
 * - events arriving for a namespace this runner does not follow are rejected via the
 *   foreign-namespace guard (compensation without persistence, so the message is ACKed)
 */
@DisplayName("CloudEventHandler")
class CloudEventHandlerTest {

    private val metrics = mockk<CloudEventSubscriberMetrics>(relaxed = true)
    private val resolver = mockk<ArrivalNamespaceResolver>()
    private val listenerService = mockk<ListenerEventService>()

    private fun scopeOf(vararg namespaces: String): NamespaceScope = object : NamespaceScope {
        override val configured: Set<String> = namespaces.toSet()
    }

    private fun handler(vararg followed: String): CloudEventHandler =
        CloudEventHandler(metrics, scopeOf(*followed), resolver).apply {
            listenerEventService = listenerService
        }

    private fun buildCloudEvent(): CloudEvent = CloudEventBuilder.v1()
        .withId("test-event-${IDV7.random()}")
        .withType("com.example.Event")
        .withSource(URI.create("https://test.example.com"))
        .build()

    @Test
    fun `deserialize should pair the event with the resolved arrival namespace`() = runTest {
        val event = buildCloudEvent()
        val message = Message.of(CloudEventService.serialize(event))
        every { resolver.resolve(message, DestinationType.CLOUDEVENTS) } returns WorkflowNamespace("team-a")

        val received = with(handler("team-a")) { message.deserialize() }

        received.event.id shouldBe event.id
        received.arrivalNamespace shouldBe WorkflowNamespace("team-a")
    }

    @Test
    fun `handle should delegate to the listener service scoped to the arrival namespace`() = runTest {
        val event = buildCloudEvent()
        coEvery { listenerService.handleCloudEvent(event, WorkflowNamespace("team-a")) } returns 1

        val next = handler("team-a", "team-b")
            .handle(ReceivedCloudEvent(event, WorkflowNamespace("team-a")))

        next.shouldBeNull()
        coVerify(exactly = 1) { listenerService.handleCloudEvent(event, WorkflowNamespace("team-a")) }
    }

    @Test
    fun `handle should reject an event arriving for a namespace this runner does not follow`() = runTest {
        val event = buildCloudEvent()

        val e = shouldThrow<CompensationException> {
            handler("team-a").handle(ReceivedCloudEvent(event, WorkflowNamespace("team-b")))
        }

        e.reason shouldBe FailureReasons.FOREIGN_NAMESPACE
        // Compensation is a no-op: CloudEvents are external, re-emittable by their producer
        e.run()
        verify(exactly = 1) { metrics.foreignNamespace(null) }
        coVerify(exactly = 0) { listenerService.handleCloudEvent(any(), any()) }
    }
}
