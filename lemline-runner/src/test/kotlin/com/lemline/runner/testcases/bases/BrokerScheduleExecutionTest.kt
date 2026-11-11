// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.coroutines.ApplicationClassLoaderContext
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.core.workflows.WorkflowCache
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.gateway.analytics.WorkflowAnalyticsEventSource
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.resources.instances.InstanceStartService
import com.lemline.runner.gateway.resources.instances.StartInstanceOutcome
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.schedules.ScheduleModel
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.testcases.AnalyticsWorkflowResultAwaiter
import com.lemline.runner.waits.WaitRepository
import jakarta.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * Runs the production start service, scheduled relays, broker consumers and lifecycle analytics.
 * No mocked emitters, manual outbox passes, injected completion events or shifted deadlines.
 * The gateway transport itself is outside this test's scope.
 */
internal abstract class BrokerScheduleExecutionTest {
    @Inject
    lateinit var definitions: DefinitionRepository

    @Inject
    lateinit var schedules: ScheduleRepository

    @Inject
    lateinit var waits: WaitRepository

    @Inject
    lateinit var database: DatabaseManager

    @Inject
    lateinit var starts: InstanceStartService

    @Inject
    lateinit var results: AnalyticsWorkflowResultAwaiter

    @Inject
    lateinit var analytics: WorkflowAnalyticsEventSource

    @Test
    fun `every completes the immediate run and at least two distinct scheduled runs`() =
        runBlocking(ApplicationClassLoaderContext()) {
            withSchedule("every", "every: PT2S") { name, initialId ->
                val completed = awaitCompleted(name, initialId, count = 3, timeout = 45.seconds)
                assertTrue(initialId in completed)
                val rows = rows(name).filter { it.outboxCompletedAt != null }.sortedBy { it.outboxScheduledFor }
                assertTrue(rows.size >= 2)
                rows.zipWithNext().forEach { (current, next) ->
                    assertEquals(current.outboxScheduledFor!! + 2.seconds, next.outboxScheduledFor)
                    assertEquals(current.nextOccurrenceId, next.id)
                }
                assertDistinctOccurrences(rows)
            }
        }

    @Test
    fun `cron completes two occurrences on real minute boundaries`() = runBlocking(ApplicationClassLoaderContext()) {
        withSchedule("cron", "cron: '* * * * *'") { name, initialId ->
            // UNIX cron has minute resolution. Allow two real boundaries plus broker startup slack.
            val completed = awaitCompleted(name, initialId = null, count = 2, timeout = 150.seconds)
            assertFalse(initialId in completed, "Cron must not execute an immediate start command")
            assertEquals(null, results.findWorkflowResult(initialId))
            val rows = rows(name).filter { it.workflowId in completed }.sortedBy { it.outboxScheduledFor }
            assertEquals(2, rows.size)
            assertDistinctOccurrences(rows)
            assertEquals(rows[0].outboxScheduledFor!! + 60.seconds, rows[1].outboxScheduledFor)
            for (row in rows) {
                val due = assertNotNull(row.outboxScheduledFor)
                assertEquals(0L, due.epochSeconds % 60)
                assertEquals(0, due.nanosecondsOfSecond)
                assertTrue(eventTime(row.workflowId, LifecycleEventType.WORKFLOW_STARTED) >= due)
            }
        }
    }

    @Test
    fun `after waits for workflow completion before scheduling each subsequent run`() =
        runBlocking(ApplicationClassLoaderContext()) {
            withSchedule("after", "after: PT2S", waitSeconds = 5) { name, initialId ->
                // Observe a real wait task before completion; no next occurrence may exist yet.
                withTimeout(30.seconds) {
                    while (waits.listAll().none { it.workflowId == initialId && it.outboxCompletedAt == null }) {
                        delay(50)
                    }
                }
                assertEquals(null, results.findWorkflowResult(initialId))
                assertEquals(1, rows(name).size, "The next run must not be created when the current one starts")
                assertEquals(null, rows(name).single().outboxDelayedUntil)

                val completed = awaitCompleted(name, initialId, count = 3, timeout = 60.seconds)
                val ordered = rows(name).filter { it.workflowId in completed }.sortedBy { it.outboxScheduledFor }
                assertEquals(3, ordered.size)
                assertEquals(initialId, ordered.first().workflowId)
                assertDistinctOccurrences(ordered)
                for ((previous, next) in ordered.zipWithNext()) {
                    val finishedAt = eventTime(previous.workflowId, LifecycleEventType.WORKFLOW_COMPLETED)
                    val startedAt = eventTime(next.workflowId, LifecycleEventType.WORKFLOW_STARTED)
                    val due = assertNotNull(next.outboxScheduledFor)
                    // SQL deadlines have microsecond precision; the completion event can have nanoseconds.
                    assertTrue(due + 1.microseconds >= finishedAt + 2.seconds)
                    assertTrue(startedAt >= due, "The next run started before its completion-based deadline")
                    assertEquals(previous.nextOccurrenceId, next.id)
                }
            }
        }

    private suspend fun withSchedule(
        kind: String,
        schedule: String,
        waitSeconds: Int = 0,
        verify: suspend (String, WorkflowId) -> Unit,
    ) {
        val name = "schedule-e2e-$kind-${IDV7.random()}"
        val waitTask = if (waitSeconds == 0) "" else "  - pause:\n      wait:\n        seconds: $waitSeconds\n"
        val yaml = """
            |document:
            |  dsl: '1.0.0'
            |  namespace: test
            |  name: $name
            |  version: '1.0.0'
            |schedule:
            |  $schedule
            |do:
            |${waitTask}  - finish:
            |      set:
            |        result: '$name'
        """.trimMargin()
        WorkflowCache.parseYamlAndPut(yaml)
        definitions.insert(
            DefinitionModel(
                namespace = WorkflowNamespace("test"),
                name = WorkflowName(name),
                version = WorkflowVersion("1.0.0"),
                definition = yaml,
            )
        )
        try {
            val accepted = starts.start(
                StartInstanceRequest.newBuilder()
                    .setNamespace("test")
                    .setName(name)
                    .setVersion("1.0.0")
                    .setZoneId("UTC")
                    .build(),
                GatewayPrincipal.anonymous,
            )
            assertEquals(StartInstanceOutcome.ACCEPTED_NEW, accepted.result)
            verify(name, accepted.instanceId)
        } finally {
            stopSchedule(name)
        }
    }

    private suspend fun rows(name: String): List<ScheduleModel> = schedules.listAll().filter {
        it.workflowNamespace == WorkflowNamespace("test") && it.workflowName == WorkflowName(name)
    }

    private suspend fun awaitCompleted(
        name: String,
        initialId: WorkflowId?,
        count: Int,
        timeout: Duration,
    ): Set<WorkflowId> {
        val completed = linkedSetOf<WorkflowId>()
        return withTimeout(timeout) {
            while (completed.size < count) {
                val occurrences = rows(name)
                assertTrue(occurrences.none { it.outboxFailedAt != null }, "Schedule publication failed: $occurrences")
                val ids = (listOfNotNull(initialId) + occurrences.filter { it.outboxCompletedAt != null }
                    .map { it.workflowId }).distinct()
                for (id in ids) {
                    if (id in completed) continue
                    val result = results.findWorkflowResult(id) ?: continue
                    val success = assertIs<WorkflowTestResult.Success>(result, "Workflow $id failed: $result")
                    assertEquals(buildJsonObject { put("result", JsonPrimitive(name)) }, success.output)
                    completed += id
                    if (completed.size == count) break
                }
                if (completed.size < count) delay(100)
            }
            completed
        }
    }

    private fun assertDistinctOccurrences(rows: List<ScheduleModel>) {
        assertEquals(1, rows.map { it.scheduleId }.distinct().size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        assertEquals(rows.size, rows.map { it.workflowId }.distinct().size)
        assertEquals(rows.size, rows.map { it.messageId }.distinct().size)
    }

    private suspend fun eventTime(id: WorkflowId, type: LifecycleEventType): Instant = withTimeout(10.seconds) {
        // Analytics may ingest terminal events before earlier lifecycle events from another batch.
        var timestamp: Instant? = null
        while (timestamp == null) {
            timestamp = analytics.listByWorkflowIdAfter(id, 0L, 256)
                .firstOrNull { it.eventType == type.type }
                ?.let { CloudEventService.deserialize(it.cloudEventJson).time?.toInstant()?.toKotlinInstant() }
            if (timestamp == null) delay(50)
        }
        timestamp
    }

    private suspend fun stopSchedule(name: String) = withTimeout(10.seconds) {
        // Lock each retained occurrence so a concurrent relay/completion cannot overwrite the stop.
        // Repeat to include a successor committed while the preceding row was being locked.
        while (true) {
            val active = rows(name).filter { it.outboxFailedAt == null || it.cleanupAfter == null }
            if (active.isEmpty()) break
            database.withTransaction { conn ->
                for (row in active) {
                    val locked = schedules.findByWorkflowIdForUpdate(row.workflowId, conn) ?: continue
                    locked.outboxFailedAt = Clock.System.now()
                    locked.cleanupAfter = Clock.System.now()
                    schedules.update(locked, conn)
                }
            }
        }
    }
}
