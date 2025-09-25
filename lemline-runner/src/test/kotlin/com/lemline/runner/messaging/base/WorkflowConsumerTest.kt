// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.base

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.database.DatabaseMessageHandler
import com.lemline.runner.messaging.database.IngestionMessage
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageHandler
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.DefinitionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
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
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var instanceMessageHandler: InstanceMessageHandler

    @Inject
    lateinit var databaseMessageHandler: DatabaseMessageHandler

    val processingMessages = ConcurrentHashMap<String, CompletableFuture<InstanceMessage?>>()

    val databaseMessages = ConcurrentHashMap<String, CompletableFuture<DatabaseMessage?>>()

    @BeforeEach
    fun setup() = runTest {
        // Clear the database
        definitionRepository.deleteAll()

        processingMessages.clear()

        instanceMessageHandler.onFailureTest = { msg: Message<String>, e: Throwable? ->
            processingMessages.remove(msg.payload)?.completeExceptionally(e)
        }

        instanceMessageHandler.onCompleteTest = { msg: Message<String>, o: InstanceMessage? ->
            processingMessages.remove(msg.payload)?.complete(o)
        }

        databaseMessageHandler.onFailureTest = { msg: Message<String>, e: Throwable? ->
            databaseMessages.remove(msg.payload)?.completeExceptionally(e)
        }

        databaseMessageHandler.onCompleteTest = { msg: Message<String>, o: DatabaseMessage? ->
            databaseMessages.remove(msg.payload)?.complete(o)
        }

        // Create test workflow definition
        val definitionModel = DefinitionModel(
            namespace = WorkflowNamespace("test"),
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

    protected abstract fun sendInstanceMessage(message: String)

    protected abstract fun receiveInstanceMessage(timeout: Long = 1, unit: TimeUnit = SECONDS): String?

    protected abstract fun receiveDatabaseMessage(timeout: Long = 1, unit: TimeUnit = SECONDS): String?

    private fun sendMessageFuture(messageJson: String): CompletableFuture<InstanceMessage?> {
        // Send the message to the input topic
        sendInstanceMessage(messageJson)

        // returns the corresponding future
        return processingMessages.computeIfAbsent(messageJson) { CompletableFuture() }
    }

    /**
     * **Scenario: **Tests the successful processing of a valid workflow message that results in a task execution.
     *
     * **Given: **
     * - Creates a `InstanceMessage` instructing the test workflow to execute the 'task' path.
     * - Encodes the message to JSON.
     *
     * **When: **
     * - Sends the message to the consumer using `sendMessageFuture` and waits for processing completion.
     *
     * **Then: **
     * - Asserts that a message is received on the output topic within the timeout.
     * - Verifies that *no* messages were stored in the retry or wait repositories, as the workflow executed directly.
     */
    @Test
    fun `should process valid workflow message and send to output topic`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            workflowId = WorkflowId.random(),
            workflowNamespace = WorkflowNamespace("test"),
            workflowName = WorkflowName("test-workflow"),
            workflowVersion = WorkflowVersion("1.0.0"),
            workflowInput = JsonPrimitive("task"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        // Wait for the message to be processed
        println("output = ${future.get(1, SECONDS)}")

        receiveInstanceMessage().shouldNotBeNull {
            println("i=$this")
        }

        // Verify that no message was sent to the database topic
        receiveDatabaseMessage() shouldBe null
    }

    /**
     * **Scenario: **Tests how the consumer handles a fundamentally invalid message (e.g., non-JSON string).
     *
     * **Given: **
     * - Defines an invalid message string.
     *
     * **When: **
     * - Sends the invalid message using `sendMessageFuture`.
     *
     * **Then: **
     * - Asserts that waiting for the processing future throws an exception (as processing fails).
     * - Verifies that a message containing a failure model was sent to the database topic.
     */
    @Test
    fun `invalid message should trigger sending a FailureModel to the database topic`() = runTest {
        // Given
        val invalidMessage = "invalid json message"

        // When
        val future = sendMessageFuture(invalidMessage)

        // Then
        val cause = shouldThrow<ExecutionException> { future.get(1, SECONDS) }.cause
        (cause is CompensationException && cause.reason == DESERIALIZATION_FAILURE) shouldBe true

        // Check that a message was sent to the database topic
        receiveDatabaseMessage().shouldNotBeNull {
            val msg = DatabaseMessage.fromJsonString(this) as IngestionMessage
            println("msg=$msg")
            msg.instanceMessages.isEmpty() shouldBe true
            msg.instanceModels.size shouldBe 1
            val failure = msg.instanceModels[0] as FailureModel
            failure.instanceMessage shouldBe null
            failure.payload shouldBe invalidMessage
            failure.errorReason shouldBe DESERIALIZATION_FAILURE
        }
    }

    /**
     * **Scenario: **Tests the case where workflow execution leads to a state requiring a retry.
     *
     * **Given: **
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'retry' path.
     * - Encodes the message to JSON.
     *
     * **When: **
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Then: **
     * - Verifies that a message corresponding to the workflow instance is stored in the *retry* repository.
     * - Asserts the status of the stored message is `PENDING` (awaiting retry attempt).
     * - Asserts the attempt count is 0 initially.
     */
    @Test
    fun `retry should trigger sending a RetryOutboxModel to the database topic`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            workflowId = WorkflowId.random(),
            workflowNamespace = WorkflowNamespace("test"),
            workflowName = WorkflowName("test-workflow"),
            workflowVersion = WorkflowVersion("1.0.0"),
            workflowInput = JsonPrimitive("retry"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        future.get(1, SECONDS) shouldBe null

        // Check that a message was sent to the database topic
        receiveDatabaseMessage().shouldNotBeNull {
            val msg = DatabaseMessage.fromJsonString(this) as IngestionMessage
            msg.instanceMessages.isEmpty() shouldBe true
            msg.instanceModels.size shouldBe 1
            val retry = msg.instanceModels[0] as RetryOutboxModel
            retry.instanceMessage.workflowState.currentPosition.toString() shouldBe "/do/3/retryCase"
            retry.errorReason shouldBe "https://serverlessworkflow.io/errors/not-implemented"
            retry.outBoxStatus shouldBe OutBoxStatus.PENDING
            retry.outboxScheduledFor shouldNotBe null
        }
    }

    /**
     * **Scenario: **Tests the case where workflow execution leads to a scheduled wait state.
     *
     * **Given: **
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'wait' path.
     * - Encodes the message to JSON.
     *
     * **When: **
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Then: **
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
            workflowNamespace = WorkflowNamespace("test"),
            WorkflowName("test-workflow"),
            WorkflowVersion("1.0.0"),
            JsonPrimitive("wait"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        println(future.get(2, SECONDS))

        // Check that a message was sent to the database topic
        receiveDatabaseMessage().shouldNotBeNull {
            val msg = DatabaseMessage.fromJsonString(this) as IngestionMessage
            msg.instanceMessages.isEmpty() shouldBe true
            msg.instanceModels.size shouldBe 1
            val retry = msg.instanceModels[0] as WaitOutboxModel
            retry.instanceMessage.workflowState.currentPosition.toString() shouldBe "/do/2/waitCase"
            retry.outBoxStatus shouldBe OutBoxStatus.PENDING
            retry.outboxScheduledFor shouldNotBe null
        }
    }

    /**
     * **Scenario: **Tests the case where the workflow completes its execution upon receiving the message, without needing further steps or output.
     *
     * **Given: **
     * - Creates a `WorkflowMessage` instructing the test workflow to execute the 'completed' path (which leads directly to exit).
     * - Encodes the message to JSON.
     *
     * **When: **
     * - Sends the message using `sendMessageFuture` and waits for processing.
     *
     * **Then: **
     * - Asserts that *no* message is received on the output topic (returns null).
     * - Verifies that both the retry and wait repositories remain empty.
     */
    @Test
    fun `should handle completed workflow without sending message`() = runTest {
        // Given
        val instanceMessage = InstanceMessage.new(
            workflowId = WorkflowId.random(),
            workflowNamespace = WorkflowNamespace("test"),
            workflowName = WorkflowName("test-workflow"),
            workflowVersion = WorkflowVersion("1.0.0"),
            workflowInput = JsonPrimitive("completed"),
        )

        // When
        val future = sendMessageFuture(instanceMessage.toJsonString())

        // Then
        future.get(1, SECONDS) shouldBe null

        receiveDatabaseMessage() shouldBe null
    }
}
