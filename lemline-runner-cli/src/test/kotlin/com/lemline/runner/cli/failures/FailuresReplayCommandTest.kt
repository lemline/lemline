// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.failures

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.setup
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.EventEmitter
import com.lemline.runner.common.messaging.FailureReasons
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.lemline.runner.failures.FailureModel
import com.lemline.runner.failures.FailureRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

class FailuresReplayCommandTest {

    private lateinit var command: FailuresReplayCommand
    private lateinit var failureRepository: FailureRepository
    private lateinit var commandEmitter: CommandEmitter
    private lateinit var eventEmitter: EventEmitter

    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    private val workflowInfo = WorkflowInfo(
        namespace = WorkflowNamespace("foreign-ns"),
        name = WorkflowName("wf"),
        version = WorkflowVersion("1.0.0"),
    )

    private val now = Instant.fromEpochSeconds(1735732800)

    @BeforeEach
    fun setup() {
        failureRepository = mockk()
        commandEmitter = mockk()
        eventEmitter = mockk()

        coEvery { commandEmitter.send(any()) } returns Unit
        coEvery { eventEmitter.send(any()) } returns Unit

        command = FailuresReplayCommand().apply {
            this.failureRepository = this@FailuresReplayCommandTest.failureRepository
            this.commandEmitter = this@FailuresReplayCommandTest.commandEmitter
            this.eventEmitter = this@FailuresReplayCommandTest.eventEmitter
            this.mixin = GlobalMixin()
        }

        // Save original streams
        originalOut = System.out
        originalErr = System.err

        // Set up capture streams
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))

        cmd = CommandLine(command).setup()
    }

    @AfterEach
    fun cleanup() {
        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)

        clearAllMocks()
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private fun nodeStack(): NodeStack {
        val root = StackFrame(
            position = NodePosition.root,
            state = RootState(
                startedAt = now,
                workflowId = WorkflowId.random(),
                workflowInput = JsonPrimitive("input"),
            ),
            counter = 1,
        )
        val task = StackFrame(
            position = NodePosition("/do/0/task"),
            state = TaskState(startedAt = now),
            counter = 2,
        )
        return NodeStack.fromFrames(listOf(root, task))
    }

    private fun commandMessage(): InstanceMessage<WorkflowCommand> = InstanceMessage(
        workflowInfo = workflowInfo,
        workflowState = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack(),
            rawOutput = JsonPrimitive("ok"),
        ),
    )

    private fun eventMessage(): InstanceMessage<WorkflowEvent> = InstanceMessage(
        workflowInfo = workflowInfo,
        workflowState = WorkflowEvent.WaitStarted(
            nodeStack = nodeStack(),
            rawOutput = JsonPrimitive("wait"),
            config = WaitConfig(waitUntil = now),
        ),
    )

    private fun failureRow(payload: String?): FailureModel = FailureModel(
        id = IDV7.random(),
        instanceMessage = null,
        payload = payload,
        errorReason = FailureReasons.FOREIGN_NAMESPACE,
        errorClass = "java.lang.IllegalStateException",
        errorMessage = "Namespace 'foreign-ns' is not followed by this runner",
        errorStackTrace = "stacktrace",
    )

    // MockK's any() cannot synthesize the IDV7 value class (init requires a UUID v7),
    // so deleteById is always stubbed/verified with concrete ids.
    private fun stubDelete(id: IDV7) {
        coEvery { failureRepository.deleteById(id) } returns 1
    }

    private fun executeAndVerifySuccess(vararg args: String): String {
        val exitCode = cmd.execute(*args)
        withClue("Error: $errStream\nStdout: $outStream") {
            exitCode shouldBe 0
        }
        return outStream.toString()
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    fun `should replay a command payload on the commands channel and delete the row`() {
        // Given a parked foreign-namespace failure carrying a command payload
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)
        stubDelete(row.id)

        // When
        val output = executeAndVerifySuccess("--yes")

        // Then
        val messageSlot: CapturingSlot<InstanceMessage<out WorkflowCommand>> = slot()
        coVerify(exactly = 1) { commandEmitter.send(capture(messageSlot)) }
        coVerify(exactly = 0) { eventEmitter.send(any()) }
        coVerify(exactly = 1) { failureRepository.deleteById(row.id) }

        messageSlot.captured.workflowInfo shouldBe workflowInfo

        output shouldContain "Replayed failure ${row.id}"
        output shouldContain "namespace: foreign-ns"
        output shouldContain "kind: command"
        output shouldContain "Replay complete: 1 replayed, 0 failed."
    }

    @Test
    fun `should replay an event payload on the events channel and delete the row`() {
        // Given a parked foreign-namespace failure carrying an event payload
        val message = eventMessage()
        val row = failureRow(InstanceMessageCodec.toTransportPayload(message))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)
        stubDelete(row.id)

        // When
        val output = executeAndVerifySuccess("--yes")

        // Then
        val messageSlot: CapturingSlot<InstanceMessage<WorkflowEvent>> = slot()
        coVerify(exactly = 1) { eventEmitter.send(capture(messageSlot)) }
        coVerify(exactly = 0) { commandEmitter.send(any()) }
        coVerify(exactly = 1) { failureRepository.deleteById(row.id) }

        messageSlot.captured.workflowState shouldBe message.workflowState

        output shouldContain "kind: event"
        output shouldContain "Replay complete: 1 replayed, 0 failed."
    }

    @Test
    fun `dry-run should list failures without emitting or deleting`() {
        // Given
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)

        // When
        val output = executeAndVerifySuccess("--dry-run")

        // Then
        coVerify(exactly = 0) { commandEmitter.send(any()) }
        coVerify(exactly = 0) { eventEmitter.send(any()) }
        coVerify(exactly = 0) { failureRepository.deleteById(row.id) }

        output shouldContain "Dry run: 1 failure(s) would be replayed"
        output shouldContain row.id.toString()
        output shouldContain "kind: command"
    }

    @Test
    fun `should skip an undecodable payload without deleting it`() {
        // Given one undecodable row and one valid row
        val badRow = failureRow("not-a-valid-payload")
        val goodRow = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery {
            failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE)
        } returns listOf(badRow, goodRow)
        stubDelete(goodRow.id)

        // When
        val output = executeAndVerifySuccess("--yes")

        // Then: only the valid row is emitted and deleted
        coVerify(exactly = 1) { commandEmitter.send(any()) }
        coVerify(exactly = 1) { failureRepository.deleteById(goodRow.id) }
        coVerify(exactly = 0) { failureRepository.deleteById(badRow.id) }

        errStream.toString() shouldContain "Skipping failure ${badRow.id}"
        output shouldContain "Replay complete: 1 replayed, 1 failed."
    }

    @Test
    fun `should not delete the row when emission fails`() {
        // Given emission fails
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)
        coEvery { commandEmitter.send(any()) } throws RuntimeException("broker unavailable")

        // When
        val output = executeAndVerifySuccess("--yes")

        // Then
        coVerify(exactly = 0) { failureRepository.deleteById(row.id) }
        errStream.toString() shouldContain "Failed to replay failure ${row.id}"
        output shouldContain "Replay complete: 0 replayed, 1 failed."
    }

    @Test
    fun `should replay a single failure selected by id`() {
        // Given
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findById(row.id) } returns row
        stubDelete(row.id)

        // When
        val output = executeAndVerifySuccess("--id", row.id.toString(), "--yes")

        // Then
        coVerify(exactly = 1) { failureRepository.findById(row.id) }
        coVerify(exactly = 0) { failureRepository.findByReason(any()) }
        coVerify(exactly = 1) { commandEmitter.send(any()) }
        coVerify(exactly = 1) { failureRepository.deleteById(row.id) }

        output shouldContain "Replay complete: 1 replayed, 0 failed."
    }

    @Test
    fun `should report when no failure matches the given id`() {
        // Given
        val missingId = IDV7.random()
        coEvery { failureRepository.findById(missingId) } returns null

        // When
        val output = executeAndVerifySuccess("--id", missingId.toString(), "--yes")

        // Then
        coVerify(exactly = 0) { commandEmitter.send(any()) }
        coVerify(exactly = 0) { failureRepository.deleteById(missingId) }
        output shouldContain "No failure found with id '$missingId'."
    }

    @Test
    fun `should fail on an invalid id`() {
        // When
        val exitCode = cmd.execute("--id", "not-a-uuid", "--yes")

        // Then
        exitCode shouldNotBe 0
        coVerify(exactly = 0) { commandEmitter.send(any()) }
        coVerify(exactly = 0) { eventEmitter.send(any()) }
    }

    @Test
    fun `should report when no failures match the reason`() {
        // Given
        coEvery { failureRepository.findByReason("some_reason") } returns emptyList()

        // When
        val output = executeAndVerifySuccess("--reason", "some_reason", "--yes")

        // Then
        output shouldContain "No failures found with reason 'some_reason'."
    }

    @Test
    fun `should cancel replay when confirmation is declined`() {
        // Given a command that declines the confirmation prompt
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)

        val decliningCommand = object : FailuresReplayCommand() {
            override fun readUserInput(): String? = "n"
        }.apply {
            this.failureRepository = this@FailuresReplayCommandTest.failureRepository
            this.commandEmitter = this@FailuresReplayCommandTest.commandEmitter
            this.eventEmitter = this@FailuresReplayCommandTest.eventEmitter
            this.mixin = GlobalMixin()
        }

        // When
        val exitCode = CommandLine(decliningCommand).setup().execute()

        // Then
        exitCode shouldBe 0
        coVerify(exactly = 0) { commandEmitter.send(any()) }
        coVerify(exactly = 0) { failureRepository.deleteById(row.id) }
        outStream.toString() shouldContain "Replay cancelled."
    }

    @Test
    fun `should replay when confirmation is accepted`() {
        // Given a command that accepts the confirmation prompt
        val row = failureRow(InstanceMessageCodec.toTransportPayload(commandMessage()))
        coEvery { failureRepository.findByReason(FailureReasons.FOREIGN_NAMESPACE) } returns listOf(row)
        stubDelete(row.id)

        val acceptingCommand = object : FailuresReplayCommand() {
            override fun readUserInput(): String? = "y"
        }.apply {
            this.failureRepository = this@FailuresReplayCommandTest.failureRepository
            this.commandEmitter = this@FailuresReplayCommandTest.commandEmitter
            this.eventEmitter = this@FailuresReplayCommandTest.eventEmitter
            this.mixin = GlobalMixin()
        }

        // When
        val exitCode = CommandLine(acceptingCommand).setup().execute()

        // Then
        exitCode shouldBe 0
        coVerify(exactly = 1) { commandEmitter.send(any()) }
        coVerify(exactly = 1) { failureRepository.deleteById(row.id) }
    }
}
