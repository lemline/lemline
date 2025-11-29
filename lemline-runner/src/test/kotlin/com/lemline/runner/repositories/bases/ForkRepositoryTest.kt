// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.ForkState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.repositories.ForkRepository
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for ForkRepository implementations.
 *
 * Verifies fork persistence, branch tracking, retrieval, and thread-safe
 * concurrent branch completion across different database backends.
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
        val fork = createTestFork()
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
        val fork = createTestFork()
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
        val fork = createTestFork()
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
        val fork = createTestFork()
        repository.insertForkWithBranches(fork, emptyList())

        // When
        val result = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)

        // Then
        result shouldNotBe null
        val (retrievedFork, retrievedBranches) = result!!
        retrievedFork.id shouldBe fork.id
        retrievedBranches shouldHaveSize 0
    }

    @Test
    fun `should update fork metadata`() = runTest {
        // Given
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        // When - mark fork as completed
        val updatedFork = fork.copy(
            output = LemlineJson.encodeToString<JsonElement>(JsonPrimitive("final-result")),
            outboxCompletedAt = Clock.System.now()
        )
        repository.update(updatedFork)

        // Then
        val retrieved = repository.findByWorkflowIdAndPosition(testWorkflowId, testPosition)!!
        retrieved.output shouldBe "\"final-result\""
        retrieved.outboxCompletedAt.shouldNotBeNull()
    }

    @Test
    open fun `should handle concurrent branch updates without race conditions`() = runTest {
        // Given - fork with 3 branches in non-compete mode
        val fork = createTestFork(compete = false)
        val branches = createTestBranches(fork, branchCount = 3)
        repository.insertForkWithBranches(fork, branches)

        // When - simulate 3 workers updating different branches concurrently
        val updates = (0..2).map { index ->
            async {
                repository.withTransaction { conn ->
                    val (currentFork, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                        testWorkflowId,
                        testPosition,
                        conn
                    )!!

                    currentBranches[index].output = "\"result-$index\""
                    currentBranches[index].completedAt = Clock.System.now()

                    repository.updateBranch(currentBranches[index], conn)

                    // Count completed branches (this is what the handler does)
                    if (currentBranches.filter { it.completedAt != null }.size == 3) {
                        currentFork.outboxCompletedAt = Clock.System.now()
                        repository.update(currentFork, conn)
                    }
                }
            }
        }

        updates.awaitAll()

        // Then - FOR UPDATE ensures no data loss
        // Verify all branches are completed
        val (finalFork, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(
            testWorkflowId,
            testPosition
        )!!
        finalBranches.count { it.completedAt != null } shouldBe 3
        finalBranches.map { it.output } shouldContainAll listOf("\"result-0\"", "\"result-1\"", "\"result-2\"")
        // this is the critical test
        finalFork.outboxCompletedAt.shouldNotBeNull()
    }

    @Test
    fun `should handle concurrent branch completion in compete mode`() = runTest {
        // Given - fork with 3 branches in compete mode (first to complete wins)
        val fork = createTestFork(compete = true)
        val branches = createTestBranches(fork, branchCount = 3)
        repository.insertForkWithBranches(fork, branches)

        // When - simulate 3 workers completing different branches concurrently
        val completionResults = (0..2).map { index ->
            async {
                repository.withTransaction { conn ->
                    val (_, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                        testWorkflowId,
                        testPosition,
                        conn
                    )!!

                    val branchToUpdate = currentBranches[index].copy(
                        output = "\"winner-$index\"",
                        completedAt = Clock.System.now()
                    )

                    repository.updateBranch(branchToUpdate, conn)

                    // Check if this is the first completion (compete mode)
                    val completedCount = currentBranches.count { it.completedAt != null }
                    val isFirstCompletion = completedCount == 0

                    Pair(index, isFirstCompletion)
                }
            }
        }

        val results = completionResults.awaitAll()

        // Then - at least ONE worker should detect being first
        // (exact count depends on timing, but FOR UPDATE prevents data loss)
        val firstCompletions = results.filter { it.second }
        firstCompletions.size shouldNotBe 0

        // Verify all branches were updated - this is the critical test
        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches.count { it.completedAt != null } shouldBe 3
    }

    @Test
    fun `should serialize access to same fork from multiple transactions`() = runTest {
        // Given
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        // When - two transactions try to read the same fork with FOR UPDATE
        val transaction1 = async {
            repository.withTransaction { conn ->
                val (_, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                    testWorkflowId,
                    testPosition,
                    conn
                )!!

                val updated = currentBranches[0].copy(
                    output = "\"T1-output\"",
                    completedAt = Clock.System.now()
                )
                repository.updateBranch(updated, conn)
            }
        }

        val transaction2 = async {
            repository.withTransaction { conn ->
                val (_, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                    testWorkflowId,
                    testPosition,
                    conn
                )!!

                val updated = currentBranches[1].copy(
                    output = "\"T2-output\"",
                    completedAt = Clock.System.now()
                )
                repository.updateBranch(updated, conn)
            }
        }

        transaction1.await()
        transaction2.await()

        // Then - FOR UPDATE ensures both transactions complete without data loss
        // The exact execution order depends on the scheduler, but the final result must be correct
        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches.count { it.completedAt != null } shouldBe 2
        finalBranches.map { it.output } shouldContainAll listOf("\"T1-output\"", "\"T2-output\"")
    }

    @Test
    fun `should prevent duplicate branch completion detection`() = runTest {
        // Given - fork with 2 branches
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        // When - same branch is processed twice (e.g., message redelivery)
        val firstUpdate = repository.withTransaction { conn ->
            val (_, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                testWorkflowId,
                testPosition,
                conn
            )!!

            val branch = currentBranches[0]
            val wasAlreadyCompleted = branch.completedAt != null

            if (!wasAlreadyCompleted) {
                val updated = branch.copy(
                    output = "\"result-0\"",
                    completedAt = Clock.System.now()
                )
                repository.updateBranch(updated, conn)
            }

            wasAlreadyCompleted
        }

        val secondUpdate = repository.withTransaction { conn ->
            val (_, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                testWorkflowId,
                testPosition,
                conn
            )!!

            val branch = currentBranches[0]
            val wasAlreadyCompleted = branch.completedAt != null

            if (!wasAlreadyCompleted) {
                val updated = branch.copy(
                    output = "\"duplicate-result\"",
                    completedAt = Clock.System.now()
                )
                repository.updateBranch(updated, conn)
            }

            wasAlreadyCompleted
        }

        // Then
        firstUpdate shouldBe false  // First time, not completed
        secondUpdate shouldBe true  // Second time, already completed

        // Verify original output is preserved
        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches[0].output shouldBe "\"result-0\""
    }

    // Helper functions
    private fun createTestFork(
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
                nodeStack = NodeStack(
                    listOf(
                        NodePosition.root to RootState(
                            startedAt = Clock.System.now(),
                            workflowId = testWorkflowId,
                            workflowInput = JsonPrimitive("test-input")
                        )
                    )
                ),
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
                failedAt = null
            )
        }
}
