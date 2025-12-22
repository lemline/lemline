// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.models.ListenerStrategy
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for ListenerTimeoutOutbox - processing timed out listeners.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerTimeoutOutboxTest {

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")

    @BeforeEach
    fun setup() = runTest {
        listenerRepository.deleteAll()
        WorkflowCache.clear()

        // Create definition (for cache lookup during listener processing)
        val definition = """
            document:
              dsl: '1.0.0'
              namespace: $testNamespace
              name: $testName
              version: '$testVersion'
            do:
              - listenTask:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.TestEvent
        """.trimIndent()

        definitionRepository.insert(
            DefinitionModel(
                namespace = testNamespace,
                name = testName,
                version = testVersion,
                definition = definition
            )
        )
        WorkflowCache.parseYamlAndPut(definition)
    }

    @Test
    fun `findTimedOut returns listeners past timeout`() = runTest {
        // Given: A listener with timeout in the past
        val timedOutListener = createListener(
            timeoutAt = Clock.System.now() - 1.hours
        )
        listenerRepository.insert(timedOutListener)

        // When
        val timedOut = listenerRepository.findTimedOut(10)

        // Then
        timedOut.size shouldBe 1
        timedOut[0].id shouldBe timedOutListener.id
    }

    @Test
    fun `findTimedOut excludes listeners with future timeout`() = runTest {
        // Given: A listener with timeout in the future
        val futureListener = createListener(
            timeoutAt = Clock.System.now() + 1.hours
        )
        listenerRepository.insert(futureListener)

        // When
        val timedOut = listenerRepository.findTimedOut(10)

        // Then
        timedOut.size shouldBe 0
    }

    @Test
    fun `findTimedOut excludes listeners without timeout`() = runTest {
        // Given: A listener without timeout set
        val noTimeoutListener = createListener(
            timeoutAt = null
        )
        listenerRepository.insert(noTimeoutListener)

        // When
        val timedOut = listenerRepository.findTimedOut(10)

        // Then
        timedOut.size shouldBe 0
    }

    @Test
    fun `findTimedOut excludes already completed listeners`() = runTest {
        // Given: A timed out but already completed listener
        val completedListener = createListener(
            timeoutAt = Clock.System.now() - 1.hours
        )
        listenerRepository.insert(completedListener)
        listenerRepository.markCompleted(completedListener.id)

        // When
        val timedOut = listenerRepository.findTimedOut(10)

        // Then
        timedOut.size shouldBe 0
    }

    @Test
    fun `findTimedOut excludes already failed listeners`() = runTest {
        // Given: A timed out but already failed listener
        val failedListener = createListener(
            timeoutAt = Clock.System.now() - 1.hours
        )
        listenerRepository.insert(failedListener)
        listenerRepository.markFailed(
            id = failedListener.id,
            errorClass = "TestException",
            errorMessage = "Test error",
            errorStackTrace = null
        )

        // When
        val timedOut = listenerRepository.findTimedOut(10)

        // Then
        timedOut.size shouldBe 0
    }

    @Test
    fun `markFailed sets failure fields correctly`() = runTest {
        // Given: A listener
        val listener = createListener(timeoutAt = Clock.System.now() - 1.hours)
        listenerRepository.insert(listener)

        // When
        listenerRepository.markFailed(
            id = listener.id,
            errorClass = "ListenerTimeoutException",
            errorMessage = "Listen task timed out",
            errorStackTrace = "stack trace here"
        )

        // Then
        val updated = listenerRepository.findById(listener.id)
        updated shouldNotBe null
        updated!!.outboxFailedAt shouldNotBe null
        updated.outboxErrorClass shouldBe "ListenerTimeoutException"
        updated.outboxErrorMessage shouldBe "Listen task timed out"
        updated.outboxErrorStackTrace shouldBe "stack trace here"
    }

    @Test
    fun `findTimedOut respects limit parameter`() = runTest {
        // Given: Multiple timed out listeners
        repeat(5) {
            val listener = createListener(
                timeoutAt = Clock.System.now() - 1.hours,
                idSuffix = "-$it"
            )
            listenerRepository.insert(listener)
        }

        // When: Query with limit of 2
        val timedOut = listenerRepository.findTimedOut(2)

        // Then
        timedOut.size shouldBe 2
    }

    // Helper functions

    private fun createListener(
        timeoutAt: kotlin.time.Instant?,
        strategy: ListenStrategy = ListenStrategy.ONE,
        idSuffix: String = ""
    ): ListenerModel {
        val workflowId = WorkflowId(IDV7.random())

        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = Clock.System.now(),
                    workflowId = workflowId,
                    workflowInput = JsonNull
                )
            )
        )

        val config = ListenConfig(
            strategy = strategy,
            filters = listOf(EventFilter(type = "com.example.TestEvent")),
            readAs = ListenAndReadAs.DATA
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = config
        )

        val listenerId = nodeStack.deriveIdempotentId("-listen$idSuffix")

        return ListenerModel(
            id = listenerId,
            instanceMessage = InstanceMessage(
                workflowInfo = WorkflowInfo(
                    namespace = testNamespace,
                    name = testName,
                    version = testVersion
                ),
                workflowState = listenStarted
            ),
            strategy = ListenerStrategy.from(config),
            timeoutAt = timeoutAt,
            outboxScheduledFor = Clock.System.now()
        )
    }
}
