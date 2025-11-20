// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.ForkState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.repositories.ForkRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for ForkRepository implementations.
 *
 * Verifies fork persistence, branch tracking, and retrieval
 * across different database backends.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ForkWaitingRepositoryTest {

    @Inject
    protected lateinit var repository: ForkRepository

    private val testWorkflowId = WorkflowId.random()
    private val testPosition = NodePosition.root.addName("fork1")
    private var testForkId: IDV7? = null

    @BeforeEach
    fun clean() = runTest {
        // Clean up any existing test data
        testForkId?.let { forkId ->
            try {
                repository.deleteById(forkId)
            } catch (e: Exception) {
                // Ignore if doesn't exist
            }
        }
    }

    @Test
    fun `should insert fork with branches atomically`() = runTest {
        // Given
        val fork = createTestFork(branchCount = 3)
        val branches = createTestBranches(fork, branchCount = 3)

        // When
        repository.insertForkWithBranches(fork, branches)

        // Then
        val retrievedResult = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)
        retrievedResult shouldNotBe null

        val (retrievedFork, retrievedBranches) = retrievedResult!!
        retrievedFork.id shouldBe fork.id
        retrievedFork.compete shouldBe false
        retrievedBranches shouldHaveSize 3
        retrievedBranches.all { it.completedAt == null } shouldBe true
    }

    @Test
    fun `should update branch`() = runTest {
        // Given
        val fork = createTestFork(branchCount = 2)
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        // When - update first branch
        val branch = branches.first().copy(
            output = "\"result-0\"",
            completedAt = Clock.System.now()
        )
        val updateCount = repository.updateBranch(branch)

        // Then
        updateCount shouldBe 1

        val (_, retrievedBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        retrievedBranches.find { it.name == branch.name }?.output shouldBe "\"result-0\""
        retrievedBranches.find { it.name == branch.name }?.completedAt shouldNotBe null
    }

    @Test
    fun `should delete fork and cascade delete branches`() = runTest {
        // Given
        val fork = createTestFork(branchCount = 2)
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        // Verify inserted
        repository.findByWorkflowIdAndPosition(testWorkflowId, testPosition) shouldNotBe null

        // When
        repository.deleteById(fork.id)

        // Then
        repository.findByWorkflowIdAndPosition(testWorkflowId, testPosition).shouldBeNull()
        repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition).shouldBeNull()
    }

    @Test
    fun `should return null when fork not found`() = runTest {
        // When
        val fork = repository.findByWorkflowIdAndPosition(
            WorkflowId.random(),
            NodePosition.root.addName("nonexistent")
        )

        // Then
        fork.shouldBeNull()
    }

    @Test
    fun `should return null when fork with branches not found`() = runTest {
        // When
        val result = repository.findByWorkflowIdAndPositionWithBranches(
            WorkflowId.random(),
            NodePosition.root.addName("nonexistent")
        )

        // Then
        result.shouldBeNull()
    }

    @Test
    fun `should return fork without branches when no branches exist`() = runTest {
        // Given - insert fork without branches
        val fork = createTestFork(branchCount = 0)
        repository.insertForkWithBranches(fork, emptyList())

        // When
        val result = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)

        // Then
        result shouldNotBe null
        val (retrievedFork, retrievedBranches) = result!!
        retrievedFork.id shouldBe fork.id
        retrievedBranches shouldHaveSize 0
    }

    // Helper functions
    private fun createTestFork(
        branchCount: Int,
        compete: Boolean = false
    ): ForkModel {
        val forkId = IDV7.random()
        testForkId = forkId  // Store for cleanup

        val instanceMessage = InstanceMessage(
            workflowInfo = WorkflowInfo(
                workflowNamespace = WorkflowNamespace("test"),
                workflowName = WorkflowName("test-workflow"),
                workflowVersion = WorkflowVersion("1.0")
            ),
            workflowState = WorkflowEvent.ForkStarted(
                taskStates = mapOf(
                    NodePosition.root to com.lemline.core.states.RootState(
                        startedAt = Clock.System.now(),
                        workflowId = testWorkflowId,
                        workflowInput = JsonPrimitive("test-input")
                    )
                ),
                nodePosition = testPosition,
                forkState = ForkState(),
                rawInput = JsonPrimitive("test-input")
            ),
        )

        return ForkModel(
            id = forkId,
            instanceMessage = instanceMessage,
            position = testPosition.toString(),
            compete = compete
        )
    }

    private fun createTestBranches(fork: ForkModel, branchCount: Int) =
        (0 until branchCount).map { index ->
            ForkBranchModel(
                forkId = fork.id,
                name = "branch-$index",
                output = null,
                completedAt = null,
                failedAt = null,
                failureId = null
            )
        }
}
