// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.bases

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageHandler
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.WaitRepository
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import jakarta.inject.Inject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base class for testing the [InstanceMessageHandler].
 *
 * This class sets up a common test environment including repositories (Retry, Wait, Workflow)
 * and provides helper methods for sending messages and waiting for processing.
 * Concrete subclasses must implement:
 *  - `setupMessaging()`: To configure the specific messaging infrastructure (e.g., Kafka, in-memory) for the test.
 *  - `cleanupMessaging()`: To tear down the messaging infrastructure after the test.
 *  - `sendMessage(String)`: To send a raw message string to the consumer's input channel.
 *  - `receiveMessage(Long, TimeUnit)`: To receive a raw message string from the consumer's output channel.
 *
 * It defines tests covering various workflow execution scenarios:
 * - Successful task execution.
 * - Handling of invalid input messages.
 * - Storing instances requiring retry logic.
 * - Storing instances requiring a wait state.
 * - Handling workflows that complete without further output.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class WorkflowConsumerTest {
    @Inject
    lateinit var retryRepository: RetryRepository

    @Inject
    lateinit var waitRepository: WaitRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var instanceMessageHandler: InstanceMessageHandler

    val processingMessages = ConcurrentHashMap<String, CompletableFuture<InstanceMessage?>>()

    @BeforeEach
    fun setup() = runTest {
        // Clear the database
        definitionRepository.deleteAll()
        retryRepository.deleteAll()
        waitRepository.deleteAll()

        processingMessages.clear()

        instanceMessageHandler.onFailureTest = { msg: Message<String>, e: Throwable? ->
            processingMessages.remove(msg.payload)?.completeExceptionally(e)
        }

        instanceMessageHandler.onCompleteTest = { msg: Message<String>, o: InstanceMessage? ->
            processingMessages.remove(msg.payload)?.complete(o)
        }

        // Create test workflow definition
        val definitionModel = DefinitionModel(
            name = WorkflowName("test-workflow"),
            version = WorkflowVersion("1.0.0"),
            definition = """
            document:
                dsl: '1.0.0'
                namespace: test
                name: test-workflow
                version: '1.0.0'
            do:
                - test:
                    switch:
                      - task:
                          when: @{ . == "task" }
                          then: taskCase
                      - wait:
                          when: @{ . == "wait" }
                          then: waitCase
                      - completed:
                          when: @{ . == "completed" }
                          then: exit
                      - error:
                          when: @{ . == "retry" }
                          then: retryCase
                - taskCase:
                    call: http
                    with:
                      method: get
                      endpoint: https://jsonplaceholder.typicode.com/posts/1
                    then: exit
                - waitCase:
                    wait:
                      seconds: 30
                    then: exit
                - retryCase:
                    try:
                      - raiseError:
                          raise:
                            error:
                              type: https://serverlessworkflow.io/errors/not-implemented
                              status: 500
                    catch:
                      retry:
                        limit:
                           attempt:
                            count: 2
                        delay: PT1S
                      do:
                        - setCaught:
                            set:
                              caught: true
            """.trimIndent().replace("@", "$")
        )
        definitionRepository.insert(definitionModel)

        setupMessaging()
    }

    protected abstract fun setupMessaging()

    @AfterEach
    fun cleanup() {
        cleanupMessaging()
    }

    protected abstract fun cleanupMessaging()

    protected abstract fun sendMessage(message: String)

    protected abstract fun receiveMessage(timeout: Long, unit: TimeUnit): String?


    private fun sendMessageFuture(messageJson: String): CompletableFuture<InstanceMessage?> {
        // Send the message to the input topic
        sendMessage(messageJson)

        // returns the corresponding future
        return processingMessages.computeIfAbsent(messageJson) { CompletableFuture() }
    }

    /**
     * **Scenario: **Tests the successful processing of a valid workflow message that results in a task execution.
     *
     * **Arrange:**
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'task' path.
     * - Encodes the message to JSON.
     *
     * **Act:**
     * - Sends the message to the consumer using `sendMessageFuture` and waits for processing completion.
     *
     * **Assert:**
     * - Asserts that a message is received on the output topic within the timeout.
     * - Verifies that *no* messages were stored in the retry or wait repositories, as the workflow executed directly.
     */
    @Test
    fun `should process valid workflow message and send to output topic`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("test-workflow"),
            workflowVersion = WorkflowVersion("1.0.0"),
            workflowInput = JsonPrimitive("task"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        // Wait for the message to be processed
        println("output = ${future.get(1, SECONDS)}")
        val outputMessage = receiveMessage(1, SECONDS)
        assertNotNull(outputMessage, "No messages received from output topic")

        // Verify that no message was stored in repositories
        val retryMessages = retryRepository.listAll()
        val waitMessages = waitRepository.listAll()
        assertTrue(retryMessages.isEmpty(), "Messages found in retry repository")
        assertTrue(
            waitMessages.isEmpty(),
            "Messages found in wait repository:\n${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}",
        )
    }

    /**
     * **Scenario: **Tests how the consumer handles a fundamentally invalid message (e.g., non-JSON string).
     *
     * **Arrange:**
     * - Defines an invalid message string.
     *
     * **Act:**
     * - Sends the invalid message using `sendMessageFuture`.
     *
     * **Assert:**
     * - Asserts that waiting for the processing future throws an exception (as processing fails).
     * - Verifies that the invalid message is stored in the *retry* repository with a status of `FAILED`.
     * - Verifies that the wait repository remains empty.
     */
    @Test
    fun `invalid message should be stored in retry table as Failed`() = runTest {
        // Given
        val invalidMessage = "invalid json message"

        // When
        val future = sendMessageFuture(invalidMessage)

        // This message cannot be processed
        shouldThrowAny { future.get(1, SECONDS) }

        val retryMessages = retryRepository.listAll()
        //retryMessages[0].message shouldBe invalidMessage
        retryMessages[0].outBoxStatus shouldBe OutBoxStatus.FAILED

        // Verify no message was stored in the wait repository
        val waitMessages = waitRepository.listAll()
        assertTrue(
            waitMessages.isEmpty(),
            "Messages found in wait repository:\n${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}",
        )
    }

    /**
     * **Scenario: **Tests the case where workflow execution leads to a state requiring a retry.
     *
     * **Arrange:**
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'retry' path.
     * - Encodes the message to JSON.
     *
     * **Act:**
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Assert:**
     * - Verifies that a message corresponding to the workflow instance is stored in the *retry* repository.
     * - Asserts the status of the stored message is `PENDING` (awaiting retry attempt).
     * - Asserts the attempt count is 0 initially.
     */
    @Test
    fun `should store instance with retry in retry repository`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            workflowId = WorkflowId.random(),
            workflowName = WorkflowName("test-workflow"),
            workflowVersion = WorkflowVersion("1.0.0"),
            workflowInput = JsonPrimitive("retry"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Wait for the message to be processed
        future.get(2, SECONDS)

        // Verify a message was stored in the retry repository
        val retryMessages = retryRepository.listAll()

        assertTrue(retryMessages.isNotEmpty(), "No messages found in retry repository")
        assertEquals(OutBoxStatus.PENDING, retryMessages[0].outBoxStatus, "Retry message status is not PENDING")
        assertEquals(0, retryMessages[0].outboxAttemptCount, "Retry message attempt count is not 0")
    }

    /**
     * **Scenario: **Tests the case where workflow execution leads to a scheduled wait state.
     *
     * **Arrange:**
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'wait' path.
     * - Encodes the message to JSON.
     *
     * **Act:**
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Assert:**
     * - Verifies that a message corresponding to the workflow instance is stored in the *wait* repository.
     * - Asserts the status of the stored message is `PENDING`.
     * - Asserts the attempt count is 0.
     * - Asserts that the `delayedUntil` timestamp is set correctly (approximately 30 seconds in the future, based on the test workflow definition).
     */
    @Test
    fun `should store waiting instance in wait repository`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            WorkflowId.random(),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0"),
            JsonPrimitive("wait"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        // Wait for the message to be processed
        future.get(1, SECONDS)

        // Verify that no message was stored in the wait repository
        val waitMessages = waitRepository.listAll()

        assertTrue(waitMessages.isNotEmpty(), "No messages found in wait repository")
        assertEquals(OutBoxStatus.PENDING, waitMessages[0].outBoxStatus, "Wait message status is not PENDING")
        assertEquals(0, waitMessages[0].outboxAttemptCount, "Wait message attempt count is not 0")

        // Verify delay was set correctly (within 1 second of expected)
        val expectedDelay = Clock.System.now() + 30.seconds
        val actualDelay = waitMessages[0].outboxDelayedUntil!!
        assertTrue(
            actualDelay < (expectedDelay + 1.seconds) && actualDelay > (expectedDelay - 1.seconds),
            "Wait message delay is not set correctly",
        )
    }

    /**
     * **Scenario: **Tests the case where the workflow completes its execution upon receiving the message, without needing further steps or output.
     *
     * **Arrange:**
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'completed' path (which leads directly to exit).
     * - Encodes the message to JSON.
     *
     * **Act:**
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Assert:**
     * - Asserts that *no* message is received on the output topic (returns null).
     * - Verifies that both the retry and wait repositories remain empty.
     */
    @Test
    fun `should handle completed workflow without sending message`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            WorkflowId.random(),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0"),
            JsonPrimitive("completed"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        // Wait for the message to be processed
        future.get(1, SECONDS)

        val outputMessage = receiveMessage(1, SECONDS)
        assertTrue(outputMessage == null, "Messages were sent to output topic: $outputMessage")

        // Verify no message was stored in repositories
        val retryMessages = retryRepository.listAll()
        assertEquals(
            0,
            retryMessages.size,
            "Messages were stored in retry repository: ${retryMessages.map { LemlineJson.encodeToPrettyString(it) }}",
        )

        val waitMessages = waitRepository.listAll()
        assertEquals(
            0,
            waitMessages.size,
            "Messages were stored in wait repository:  ${waitMessages.map { LemlineJson.encodeToPrettyString(it) }}",
        )
    }
}
