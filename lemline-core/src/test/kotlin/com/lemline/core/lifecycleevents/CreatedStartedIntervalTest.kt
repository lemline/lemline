// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.getWorkflowToTest
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.core.workflows.WorkflowCache
import com.lemline.messages.internal.v1.WorkflowStateProto
import io.cloudevents.CloudEvent
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The `created → started` interval of workflows and tasks measures waiting for execution:
 * `created` is frozen when the command becomes eligible and carried by the command, `started`
 * is read when a worker enters the task. Timing is controlled by building commands with explicit
 * creation times, so the assertions do not depend on how fast the test machine is.
 */
class CreatedStartedIntervalTest {

    private class Capture : LifecycleEventEmitter {
        val events = CopyOnWriteArrayList<CloudEvent>()
        override suspend fun emit(cloudEvent: CloudEvent) {
            events.add(cloudEvent)
        }

        fun ofType(type: LifecycleEventType, task: String? = null) = events.filter {
            it.type == type.type && (task == null || taskOf(it) == task)
        }

        fun single(type: LifecycleEventType, task: String? = null) = ofType(type, task).single()
    }

    @AfterEach
    fun clear() = WorkflowCache.clear()

    private val twoSteps = """
        do:
          - first: { set: { a: 1 } }
          - second: { set: { b: 2 } }
    """

    @Test
    fun `initial creation events carry the command creation time and match worker-derived identities`() = runBlocking {
        val workflow = getWorkflowToTest(twoSteps)
        val createdAt = Instant.parse("2026-01-02T03:04:05.123456Z")
        val command = StepByStepOrchestrator.initCmd(
            workflowId = WorkflowId.random(),
            startedAt = createdAt - 1.seconds,
            createdAt = createdAt,
        )
        val accepted = Capture()
        StepByStepOrchestrator.emitCreationEvents(workflow.info, command, CloudEventLifecycleHook(accepted))

        val workflowCreated = accepted.single(LifecycleEventType.WORKFLOW_CREATED)
        val doCreated = accepted.single(LifecycleEventType.TASK_CREATED, "/do")
        assertEquals(createdAt, workflowCreated.instant())
        assertEquals(createdAt, doCreated.instant())
        assertEquals(createdAt.toString(), dataOf(workflowCreated).getValue("createdAt").jsonPrimitive.content)
        // RootState.startedAt ($workflow.startedAt) keeps its own value.
        assertEquals(createdAt - 1.seconds, command.nodeStack.rootState.startedAt)

        // A legacy command (no createdAt) gets its /do task.created from the worker: same event ID.
        val legacy = Capture()
        StepByStepOrchestrator.runByTask(workflow, command.copy(createdAt = null), workflow.info, CloudEventLifecycleHook(legacy))
        assertEquals(doCreated.id, legacy.single(LifecycleEventType.TASK_CREATED, "/do").id)
    }

    @Test
    fun `worker entry does not emit the initial task created and measures a delayed pickup`() = runBlocking {
        val workflow = getWorkflowToTest(twoSteps)
        // The command was accepted 5 seconds before a worker picked it up.
        val createdAt = Clock.System.now() - 5.seconds
        val command = StepByStepOrchestrator.initCmd(startedAt = createdAt, createdAt = createdAt)
        val capture = Capture()
        StepByStepOrchestrator.runByTask(workflow, command, workflow.info, CloudEventLifecycleHook(capture))

        assertTrue(capture.ofType(LifecycleEventType.TASK_CREATED, "/do").isEmpty(), "workers must not emit /do task.created")
        assertTrue(capture.ofType(LifecycleEventType.WORKFLOW_CREATED).isEmpty())
        val workflowStarted = capture.single(LifecycleEventType.WORKFLOW_STARTED).instant()
        val doStarted = capture.single(LifecycleEventType.TASK_STARTED, "/do").instant()
        assertTrue(workflowStarted - createdAt >= 5.seconds, "workflow wait ${workflowStarted - createdAt}")
        assertTrue(doStarted - createdAt >= 5.seconds, "/do wait ${doStarted - createdAt}")
    }

    @Test
    fun `legacy initial command without creation time never reports a zero wait`() = runBlocking {
        val workflow = getWorkflowToTest(twoSteps)
        val preparedAt = Clock.System.now() - 3.seconds
        val legacy = StepByStepOrchestrator.initCmd(startedAt = preparedAt).copy(createdAt = null)
        val capture = Capture()
        StepByStepOrchestrator.runByTask(workflow, legacy, workflow.info, CloudEventLifecycleHook(capture))

        val doCreated = capture.single(LifecycleEventType.TASK_CREATED, "/do").instant()
        val doStarted = capture.single(LifecycleEventType.TASK_STARTED, "/do").instant()
        // Stamped with the value older runtimes used for workflow.created, not with the worker clock.
        assertEquals(preparedAt, doCreated)
        assertTrue(doStarted - doCreated >= 3.seconds)
    }

    @Test
    fun `queued task in ALL mode - task created equals the command creation time`() = runBlocking {
        val workflow = getWorkflowToTest(twoSteps)
        val capture = Capture()
        val hook = CloudEventLifecycleHook(capture)
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd()
        // /do schedules /do/0/first, which is published as its own command in ALL mode.
        var scheduled: WorkflowEvent.TaskScheduled
        do {
            scheduled = StepByStepOrchestrator.runByTask(workflow, command, workflow.info, hook) as WorkflowEvent.TaskScheduled
            command = scheduled.resume()
        } while (scheduled.nodePosition != NodePosition("/do/0/first"))

        val createdAt = requireNotNull(scheduled.createdAt)
        val resume = scheduled.resume()
        assertEquals(createdAt, resume.createdAt)
        assertEquals(createdAt, capture.single(LifecycleEventType.TASK_CREATED, "/do/0/first").instant())

        // The command survives transport and is picked up later: created stays, started moves.
        val delivered = roundTrip(resume) as WorkflowCommand.ResumeFromTask
        assertEquals(createdAt, delivered.createdAt)
        Thread.sleep(60)
        StepByStepOrchestrator.runByTask(workflow, delivered, workflow.info, hook)
        val started = capture.single(LifecycleEventType.TASK_STARTED, "/do/0/first").instant()
        assertTrue(started - createdAt >= 60.milliseconds, "queued wait ${started - createdAt}")
        // Redelivery of the predecessor re-derives the same task.created identity.
        assertEquals(1, capture.ofType(LifecycleEventType.TASK_CREATED, "/do/0/first").map { it.id }.distinct().size)
    }

    @Test
    fun `inline tasks in ACTION mode have a near zero interval`() = runBlocking {
        val workflow = getWorkflowToTest(twoSteps)
        val capture = Capture()
        val outcome = StepByStepOrchestrator.runByActivity(workflow, StepByStepOrchestrator.initCmd(), workflow.info, CloudEventLifecycleHook(capture))
        assertTrue(outcome is WorkflowEvent.WorkflowCompleted)
        for (task in listOf("/do/0/first", "/do/1/second")) {
            val created = capture.single(LifecycleEventType.TASK_CREATED, task).instant()
            val started = capture.single(LifecycleEventType.TASK_STARTED, task).instant()
            assertTrue(started >= created, "$task started before it was created")
            assertTrue(started - created < 1.seconds, "$task inline wait ${started - created}")
        }
    }

    @Test
    fun `retry attempt boundary is retryAt and attempts get distinct identities`() = runBlocking {
        val workflow = getWorkflowToTest("""
            do:
              - guard:
                  try:
                    - boom:
                        raise:
                          error:
                            type: https://example.com/errors/boom
                            status: 503
                  catch:
                    retry: { delay: { milliseconds: 1 }, limit: { attempt: { count: 2 } } }
        """)
        val capture = Capture()
        val hook = CloudEventLifecycleHook(capture)
        var command: WorkflowCommand = StepByStepOrchestrator.initCmd()
        val retryCommands = mutableListOf<Pair<Instant, WorkflowCommand.ResumeFromTask>>()
        while (true) {
            when (val event = StepByStepOrchestrator.runByTask(workflow, command, workflow.info, hook)) {
                is WorkflowEvent.TaskScheduled -> command = event.resume()
                is WorkflowEvent.TaskRetryScheduled -> {
                    // Like the runner, only resume once the planned retry time is reached.
                    while (Clock.System.now() < event.retryAt) Thread.sleep(1)
                    command = event.resume()
                    retryCommands.add(event.retryAt to command)
                }
                else -> break
            }
        }

        assertEquals(2, retryCommands.size)
        // A retry attempt becomes eligible at retryAt: its command carries that boundary.
        for ((retryAt, retry) in retryCommands) assertEquals(retryAt, retry.createdAt)
        val retried = capture.ofType(LifecycleEventType.TASK_RETRIED)
        assertEquals(retryCommands.map { it.first }, retried.map { it.instant() })
        // The retried block is not a new invocation: no task.created after the first attempt,
        // task.retried (stamped retryAt) is its attempt boundary, and each attempt starts with
        // its own identity.
        val block = "/do/0/guard/try"
        assertEquals(1, capture.ofType(LifecycleEventType.TASK_CREATED, block).size)
        val blockStarts = capture.ofType(LifecycleEventType.TASK_STARTED, block)
        assertEquals(3, blockStarts.map { it.id }.distinct().size)
        for ((index, retryAt) in retryCommands.map { it.first }.withIndex()) {
            assertTrue(blockStarts[index + 1].instant() >= retryAt, "attempt ${index + 2} started before its retryAt")
        }
        // Tasks inside the block are scheduled again by each attempt: new invocations, new identities.
        val boom = "$block/0/boom"
        assertEquals(3, capture.ofType(LifecycleEventType.TASK_CREATED, boom).map { it.id }.distinct().size)
        assertEquals(3, capture.ofType(LifecycleEventType.TASK_STARTED, boom).map { it.id }.distinct().size)
    }

    @Test
    fun `loop iterations are new invocations with distinct identities`() = runBlocking {
        val workflow = getWorkflowToTest("""
            do:
              - loop:
                  for: { in: '${'$'}{ [1,2,3] }', each: item }
                  do:
                    - body: { set: { v: '${'$'}{ ${'$'}item }' } }
        """)
        val capture = Capture()
        StepByStepOrchestrator.runByActivity(workflow, StepByStepOrchestrator.initCmd(), workflow.info, CloudEventLifecycleHook(capture))
        val body = capture.ofType(LifecycleEventType.TASK_CREATED).mapNotNull { taskOf(it) }.first { it.endsWith("/body") }
        val created = capture.ofType(LifecycleEventType.TASK_CREATED, body)
        assertEquals(3, created.size)
        assertEquals(3, created.map { it.id }.distinct().size)
        assertEquals(3, capture.ofType(LifecycleEventType.TASK_STARTED, body).map { it.id }.distinct().size)
    }

    @Test
    fun `binary round trip preserves present and absent creation times`() {
        val createdAt = Instant.parse("2026-01-02T03:04:05.123456789Z")
        val command = StepByStepOrchestrator.initCmd(createdAt = createdAt)
        assertEquals(command, roundTrip(command))
        assertNull((roundTrip(command.copy(createdAt = null)) as WorkflowCommand.ResumeFromTask).createdAt)

        val scheduled = WorkflowEvent.TaskScheduled(command.nodeStack, NodePosition("/do/0/a"), buildJsonObject {}, null, createdAt = createdAt)
        assertEquals(scheduled, roundTrip(scheduled))
        assertNull((roundTrip(scheduled.copy(createdAt = null)) as WorkflowEvent.TaskScheduled).createdAt)
    }

    @Test
    fun `ProtoJSON messages without created_at decode as unknown, never epoch or now`() {
        // Produced with `buf convert --type lemline.internal.v1.WorkflowStateProto` from ProtoJSON
        // documents that omit or set "createdAt": "2026-01-02T03:04:05.123456Z" (see test source).
        val expected = Instant.parse("2026-01-02T03:04:05.123456Z")
        val resumeAbsent = decode("CmcKZQpKCkgSRgpECgYIoOvcygYSJDAxOTBiM2ExLTdjMmUtN2EzYi04YzRkLTEyMzQ1Njc4OTBhYhoSKhAKDgoBYRIJEQAAAAAAAPA/IgASAy9kbxoSKhAKDgoBYRIJEQAAAAAAAPA/")
        val resumePresent = decode("CnQKcgpKCkgSRgpECgYIoOvcygYSJDAxOTBiM2ExLTdjMmUtN2EzYi04YzRkLTEyMzQ1Njc4OTBhYhoSKhAKDgoBYRIJEQAAAAAAAPA/IgASAy9kbxoSKhAKDgoBYRIJEQAAAAAAAPA/MgsIpevcygYQgJTvOg==")
        val scheduledAbsent = decode("ElcyVQo6CjgSNgo0CgYIoOvcygYSJDAxOTBiM2ExLTdjMmUtN2EzYi04YzRkLTEyMzQ1Njc4OTBhYhoCKgAiABIDL2RvGhIqEAoOCgFhEgkRAAAAAAAA8D8=")
        val scheduledPresent = decode("EmQyYgo6CjgSNgo0CgYIoOvcygYSJDAxOTBiM2ExLTdjMmUtN2EzYi04YzRkLTEyMzQ1Njc4OTBhYhoCKgAiABIDL2RvGhIqEAoOCgFhEgkRAAAAAAAA8D8yCwil69zKBhCAlO86")

        assertNull((resumeAbsent as WorkflowCommand.ResumeFromTask).createdAt)
        assertEquals(expected, (resumePresent as WorkflowCommand.ResumeFromTask).createdAt)
        assertNull((scheduledAbsent as WorkflowEvent.TaskScheduled).createdAt)
        assertEquals(expected, (scheduledPresent as WorkflowEvent.TaskScheduled).createdAt)
        // Re-encoding keeps absence as absence.
        assertNull(WorkflowStateProtobufMapper.toProto(resumeAbsent).command!!.resume_from_task!!.created_at)
        assertNotEquals(resumeAbsent, resumePresent)
    }

    private fun decode(base64: String) =
        WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(Base64.getDecoder().decode(base64)))

    private fun roundTrip(state: com.lemline.core.states.WorkflowState) =
        WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(WorkflowStateProtobufMapper.toProto(state).encode()))

    private companion object {
        fun CloudEvent.instant(): Instant = requireNotNull(time).toInstant().toKotlinInstant()
        fun dataOf(event: CloudEvent) = parseJson(String(event.data!!.toBytes())).jsonObject
        fun taskOf(event: CloudEvent): String? = dataOf(event)["task"]?.jsonPrimitive?.content
    }
}
