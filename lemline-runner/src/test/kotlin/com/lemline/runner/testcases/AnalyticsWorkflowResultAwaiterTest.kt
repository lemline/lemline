// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.values.WorkflowId
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventRow
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import io.cloudevents.core.builder.CloudEventBuilder
import io.cloudevents.jackson.JsonFormat
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AnalyticsWorkflowResultAwaiterTest {
    private fun row(index: Int) = WorkflowAnalyticsEventRow(
        cursor = index.toLong(),
        eventType = "org.open-workflow-specification.task.completed.v1",
        cloudEventJson = String(JsonFormat().serialize(CloudEventBuilder.v1()
            .withId(index.toString()).withType("org.open-workflow-specification.task.completed.v1")
            .withSource(URI("https://tests.example.com"))
            .withData("application/json", "{}".toByteArray()).build()))
    )

    private fun source(read: (Long, Int) -> List<WorkflowAnalyticsEventRow>) = object : WorkflowAnalyticsEventSource {
        override suspend fun listByWorkflowIdAfter(workflowId: WorkflowId, afterSequenceExclusive: Long, limit: Int) =
            read(afterSequenceExclusive, limit)
        override suspend fun validate() = Unit
    }

    @Test
    fun `lifecycle assertions wait for earlier task batches to reach analytics`() = runBlocking {
        var polls = 0
        val awaiter = AnalyticsWorkflowResultAwaiter(source { _, _ ->
            polls++
            if (polls == 1) listOf(row(2)) else listOf(row(1), row(2))
        })
        awaiter.validateLifecycleEvents(WorkflowId.random(), { events ->
            if (events.map { it.id }.toSet() == setOf("1", "2")) null else "Missing task history"
        })
        assertEquals(2, polls)
    }

    @Test
    fun `lifecycle assertions include history beyond the first page`() = runBlocking {
        val rows = (1..300).map(::row)
        val cursors = mutableListOf<Long>()
        val awaiter = AnalyticsWorkflowResultAwaiter(source { cursor, limit ->
            cursors.add(cursor)
            rows.filter { it.cursor > cursor }.take(limit)
        })
        awaiter.validateLifecycleEvents(WorkflowId.random(), { events ->
            if (events.size == 300 && events.last().id == "300") null else "Truncated history"
        })
        assertEquals(listOf(0L, 256L), cursors)
    }

    @Test
    fun `missing lifecycle events fail the test instead of accepting workflow completion`() = runBlocking {
        val awaiter = AnalyticsWorkflowResultAwaiter(source { _, _ -> emptyList() })
        val error = assertFailsWith<AssertionError> {
            awaiter.validateLifecycleEvents(WorkflowId.random(), { "Owner never completed" }, Duration.ZERO)
        }
        assertTrue(error.message!!.contains("Owner never completed"))
    }
}
