// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
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
import com.lemline.core.states.DoState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.ListenerRepository
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import jakarta.inject.Inject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach

/**
 * Abstract base class for testing ListenerRepository implementations.
 *
 * Extends OutboxRepositoryTest to inherit all standard outbox tests,
 * and adds listener-specific tests.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ListenerRepositoryTest : OutboxRepositoryTest<ListenerModel>() {

    @Inject
    override lateinit var repository: ListenerRepository

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    // Fixed test values for workflow identification
    private val testNamespace = WorkflowNamespace("test-namespace")
    private val testName = WorkflowName("test-workflow")
    private val testVersion = WorkflowVersion("1.0.0")
    private val testNodePosition = NodePosition("/do/listenTask")

    @BeforeEach
    fun setupParentRecords() = runTest {
        // Clear existing data
        repository.deleteAll()

        // Create parent definition record
        val definition = DefinitionModel(
            namespace = testNamespace,
            name = testName,
            version = testVersion,
            definition = """
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
                              type: com.example.Event
            """.trimIndent()
        )
        // Try to insert, ignore if already exists (from parallel tests)
        try {
            definitionRepository.insert(definition)
        } catch (_: Exception) {
            // Definition already exists, which is fine
        }
    }

    override fun createRandomEntity(): ListenerModel {
        val now = Clock.System.now()
        val workflowId = WorkflowId(IDV7.random())

        val workflowInfo = WorkflowInfo(
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion
        )

        // Create a proper NodeStack with the listen task position
        // The nodeStack.lastPosition must equal testNodePosition for correct serialization
        val nodeStack = NodeStack(
            listOf(
                NodePosition.root to RootState(
                    startedAt = now,
                    workflowId = workflowId,
                    workflowInput = JsonNull
                ),
                NodePosition("/do") to DoState(startedAt = now),
                testNodePosition to TaskState(startedAt = now)
            )
        )

        val listenStarted = WorkflowEvent.ListenStarted(
            nodeStack = nodeStack,
            rawOutput = JsonNull,
            config = ListenConfig(
                strategy = ListenStrategy.ONE,
                filters = listOf(EventFilter(type = "com.example.Event")),
                readAs = ListenAndReadAs.DATA,
                timeoutAt = null
            )
        )

        return ListenerModel(
            id = IDV7.random(),
            workflowNamespace = testNamespace,
            workflowName = testName,
            workflowVersion = testVersion,
            instanceMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = listenStarted
            ),
            workflowId = workflowId,
            workflowPosition = testNodePosition,
            timeoutAt = null,
            outboxScheduledFor = now
        ).also {
            it.outboxAttemptCount = Random.nextInt(0, 5)
            it.outboxDelayedUntil = now
        }
    }

    override fun changeDelayedUntil(model: ListenerModel) =
        model.copy().apply { outboxDelayedUntil = Instant.random() }
}
