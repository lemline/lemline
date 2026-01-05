// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.forks

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for ForkRepository implementations.
 *
 * Verifies fork persistence, branch tracking, retrieval, and thread-safe
 * concurrent branch completion across different database backends.
 */
abstract class ForkRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): ForkRepository

    private val testWorkflowId = WorkflowId.random()
    private val testPosition = NodePosition.root.addName("fork1")
    private var testForkId: IDV7? = null

    private fun createEntity() = ForkModel.random()
    private fun modifyEntity(entity: ForkModel) =
        entity.copy().apply { completedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()) }

    @BeforeEach
    fun clean() = runTest {
        testForkId?.let { forkId ->
            try {
                getRepository().deleteById(forkId)
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun `should insert fork with branches atomically`() = runTest {
        val repository = getRepository()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 3)

        repository.insertForkWithBranches(fork, branches)

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
        val repository = getRepository()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        val branch = branches.first().copy(
            output = "\"result-0\"",
            completedAt = Clock.System.now()
        )
        val updateCount = repository.updateBranch(branch)

        updateCount shouldBe 1

        val (_, retrievedBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        retrievedBranches.find { it.name == branch.name }?.output shouldBe "\"result-0\""
        retrievedBranches.find { it.name == branch.name }?.completedAt shouldNotBe null
    }

    @Test
    fun `should delete fork and cascade delete branches`() = runTest {
        val repository = getRepository()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition) shouldNotBe null

        repository.deleteById(fork.id)

        repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition).shouldBeNull()
    }

    @Test
    fun `should return null when fork not found`() = runTest {
        val result = getRepository().findByWorkflowIdAndPositionWithBranches(
            WorkflowId.random(),
            NodePosition.root.addName("nonexistent")
        )
        result.shouldBeNull()
    }

    @Test
    fun `should return fork without branches when no branches exist`() = runTest {
        val repository = getRepository()
        val fork = createTestFork()
        repository.insertForkWithBranches(fork, emptyList())

        val result = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)

        result shouldNotBe null
        val (retrievedFork, retrievedBranches) = result!!
        retrievedFork.id shouldBe fork.id
        retrievedBranches shouldHaveSize 0
    }

    @Test
    fun `should update fork metadata`() = runTest {
        val repository = getRepository()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        val updatedFork = fork.copy(
            output = LemlineJson.encodeToString<JsonElement>(JsonPrimitive("final-result")),
        ).apply {
            completedAt = Clock.System.now()
        }
        repository.update(updatedFork)

        val (retrieved, _) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        retrieved.output shouldBe "\"final-result\""
        retrieved.completedAt.shouldNotBeNull()
    }

    @Test
    open fun `should handle concurrent branch updates without race conditions`() = runTest {
        val repository = getRepository()
        val databaseConfig = getDatabaseConfig()
        val fork = createTestFork(compete = false)
        val branches = createTestBranches(fork, branchCount = 3)
        repository.insertForkWithBranches(fork, branches)

        val updates = (0..2).map { index ->
            async {
                databaseConfig.withTransaction { conn ->
                    val (currentFork, currentBranches) = repository.findByWorkflowIdAndPositionWithBranches(
                        testWorkflowId,
                        testPosition,
                        conn
                    )!!

                    currentBranches[index].output = "\"result-$index\""
                    currentBranches[index].completedAt = Clock.System.now()

                    repository.updateBranch(currentBranches[index], conn)

                    if (currentBranches.filter { it.completedAt != null }.size == 3) {
                        currentFork.completedAt = Clock.System.now()
                        repository.update(currentFork, conn)
                    }
                }
            }
        }

        updates.awaitAll()

        val (finalFork, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(
            testWorkflowId,
            testPosition
        )!!
        finalBranches.count { it.completedAt != null } shouldBe 3
        finalBranches.map { it.output } shouldContainAll listOf("\"result-0\"", "\"result-1\"", "\"result-2\"")
        finalFork.completedAt.shouldNotBeNull()
    }

    @Test
    fun `should handle concurrent branch completion in compete mode`() = runTest {
        val repository = getRepository()
        val databaseConfig = getDatabaseConfig()
        val fork = createTestFork(compete = true)
        val branches = createTestBranches(fork, branchCount = 3)
        repository.insertForkWithBranches(fork, branches)

        val completionResults = (0..2).map { index ->
            async {
                databaseConfig.withTransaction { conn ->
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

                    val completedCount = currentBranches.count { it.completedAt != null }
                    val isFirstCompletion = completedCount == 0

                    Pair(index, isFirstCompletion)
                }
            }
        }

        val results = completionResults.awaitAll()

        val firstCompletions = results.filter { it.second }
        firstCompletions.size shouldNotBe 0

        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches.count { it.completedAt != null } shouldBe 3
    }

    @Test
    fun `should serialize access to same fork from multiple transactions`() = runTest {
        val repository = getRepository()
        val databaseConfig = getDatabaseConfig()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        val transaction1 = async {
            databaseConfig.withTransaction { conn ->
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
            databaseConfig.withTransaction { conn ->
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

        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches.count { it.completedAt != null } shouldBe 2
        finalBranches.map { it.output } shouldContainAll listOf("\"T1-output\"", "\"T2-output\"")
    }

    @Test
    fun `should prevent duplicate branch completion detection`() = runTest {
        val repository = getRepository()
        val databaseConfig = getDatabaseConfig()
        val fork = createTestFork()
        val branches = createTestBranches(fork, branchCount = 2)
        repository.insertForkWithBranches(fork, branches)

        val firstUpdate = databaseConfig.withTransaction { conn ->
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

        val secondUpdate = databaseConfig.withTransaction { conn ->
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

        firstUpdate shouldBe false
        secondUpdate shouldBe true

        val (_, finalBranches) = repository.findByWorkflowIdAndPositionWithBranches(testWorkflowId, testPosition)!!
        finalBranches[0].output shouldBe "\"result-0\""
    }

    // ========== Nested Standard Repository Tests ==========

    @Nested
    inner class CrudTests : CrudRepositoryTest<ForkModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<ForkModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<ForkModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    // ========== Helper functions ==========
    private fun createTestFork(compete: Boolean = false): ForkModel {
        val forkId = IDV7.random()
        testForkId = forkId

        val instanceMessage = InstanceMessage(
            workflowInfo = WorkflowInfo(
                namespace = WorkflowNamespace("test"),
                name = WorkflowName("test-workflow"),
                version = WorkflowVersion("1.0")
            ),
            workflowState = WorkflowEvent.ForkStarted(
                nodeStack = NodeStack.fromFrames(
                    listOf(
                        StackFrame(NodePosition.root, RootState(
                            startedAt = Clock.System.now(),
                            workflowId = testWorkflowId,
                            workflowInput = JsonPrimitive("test-input")
                        ))
                    )
                ),
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
