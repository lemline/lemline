// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.Token
import com.lemline.core.states.BranchStatus
import com.lemline.core.states.WorkflowState
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.database.DatabaseMessageHandler
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration test for fork execution through the message handlers.
 *
 * Tests the complete flow:
 * 1. Fork started → persisted to database
 * 2. Branches scheduled as instance messages
 * 3. Branch completion → recorded in database
 * 4. Fork completion detection → parent resumed
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkExecutionIntegrationTest {

    @Inject
    lateinit var databaseMessageHandler: DatabaseMessageHandler

    @Inject
    lateinit var forkRepository: ForkRepository

    private val testWorkflowInfo = WorkflowInfo(
        workflowId = WorkflowId.random(),
        workflowNamespace = WorkflowNamespace("test"),
        workflowName = WorkflowName("fork-test"),
        workflowVersion = WorkflowVersion("1.0.0")
    )

    private var lastForkId: com.lemline.common.values.IDV7? = null

    @BeforeEach
    fun setup() = runTest {
        // Clean up any existing forks from the previous test
        lastForkId?.let { forkId ->
            try {
                forkRepository.delete(forkId)
            } catch (e: Exception) {
                // Ignore
            }
        }
        lastForkId = null
    }

    @Test
    fun `should persist fork and schedule branches`() = runTest {
        // Register workflow with 2 branches
        registerCustomForkWorkflow(branchCount = 2, compete = false)

        // Given - a RunningFork state at the fork position in the test workflow
        // The fork is at /DO/0/testFork (root -> DO token -> index 0 -> task testFork)
        val forkPosition = NodePosition.root.addToken(Token.DO).addIndex(0).addName("testFork")

        val forkState = WorkflowState.RunningFork(
            taskStates = emptyMap(),
            nodePosition = forkPosition,
            rawInput = JsonPrimitive("input"),
            completedBranches = emptyMap()
        )

        val instanceMessage = InstanceMessage(
            workflowInfo = testWorkflowInfo,
            workflowState = forkState,
            parentId = null
        )

        val forkStartedMessage = DatabaseMessage.WorkflowPersistence(instanceMessage)

        // When
        databaseMessageHandler.handle(forkStartedMessage)

        // Then - fork should be persisted
        val fork = forkRepository.findByWorkflowIdAndPosition(
            testWorkflowInfo.workflowId,
            forkPosition
        )
        fork shouldNotBe null
        fork!!.branchCount shouldBe 2
        fork.compete shouldBe false
        lastForkId = fork.id  // Store for cleanup

        // Then - branches should be created
        val storedBranches = forkRepository.getBranches(
            fork.id
        )
        storedBranches shouldHaveSize 2
        storedBranches.all { it.status == BranchStatus.PENDING } shouldBe true
    }

    @Test
    fun `should detect cooperative fork completion when all branches complete`() = runTest {
        // Given - a fork with 2 branches
        val forkPosition = NodePosition.root.addToken(Token.DO).addIndex(0).addName("testFork")
        setupFork(forkPosition, branchCount = 2, compete = false)

        val fork = forkRepository.findByWorkflowIdAndPosition(testWorkflowInfo.workflowId, forkPosition)!!
        lastForkId = fork.id

        // When - complete first branch
        val result1 = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 0,
            branchOutput = JsonPrimitive("result-0")
        )

        // Then - not complete yet
        result1.isComplete shouldBe false
        result1.completedCount shouldBe 1

        // When - complete second branch
        val result2 = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 1,
            branchOutput = JsonPrimitive("result-1")
        )

        // Then - fork should be complete
        result2.isComplete shouldBe true
        result2.completedCount shouldBe 2
        result2.compete shouldBe false

        // Verify all branches are completed
        val branches = result2.branches
        branches.filter { it.status == BranchStatus.COMPLETED } shouldHaveSize 2
    }

    @Test
    fun `should detect compete fork completion on first branch`() = runTest {
        // Given - a compete fork with 3 branches
        val forkPosition = NodePosition.root.addToken(Token.DO).addIndex(0).addName("testFork")
        setupFork(forkPosition, branchCount = 3, compete = true)

        val fork = forkRepository.findByWorkflowIdAndPosition(testWorkflowInfo.workflowId, forkPosition)!!
        lastForkId = fork.id

        // When - complete first branch
        val result = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 0,
            branchOutput = JsonPrimitive("winner")
        )

        // Then - fork should be complete immediately
        result.isComplete shouldBe true
        result.completedCount shouldBe 1
        result.compete shouldBe true

        // Verify only one branch is completed
        val completedBranches = result.branches.filter { it.status == BranchStatus.COMPLETED }
        completedBranches shouldHaveSize 1
        completedBranches.first().branchIndex shouldBe 0
    }

    @Test
    fun `should handle multiple concurrent branch completions`() = runTest {
        // Given - a fork with 3 branches
        val forkPosition = NodePosition.root.addToken(Token.DO).addIndex(0).addName("testFork")
        setupFork(forkPosition, branchCount = 3, compete = false)

        val fork = forkRepository.findByWorkflowIdAndPosition(testWorkflowInfo.workflowId, forkPosition)!!
        lastForkId = fork.id

        // When - complete branches concurrently (simulated by sequential calls)
        val result1 = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 0,
            branchOutput = JsonPrimitive("result-0")
        )

        val result2 = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 1,
            branchOutput = JsonPrimitive("result-1")
        )

        val result3 = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 2,
            branchOutput = JsonPrimitive("result-2")
        )

        // Then - completion counts should be accurate
        result1.completedCount shouldBe 1
        result1.isComplete shouldBe false

        result2.completedCount shouldBe 2
        result2.isComplete shouldBe false

        result3.completedCount shouldBe 3
        result3.isComplete shouldBe true

        // Verify all branches have their outputs
        val finalBranches = result3.branches
        finalBranches.all { it.output != null } shouldBe true
    }

    @Test
    fun `should cleanup fork after completion`() = runTest {
        // Given - a completed fork
        val forkPosition = NodePosition.root.addToken(Token.DO).addIndex(0).addName("testFork")
        setupFork(forkPosition, branchCount = 1, compete = false)

        // Get the fork that was created
        val fork = forkRepository.findByWorkflowIdAndPosition(testWorkflowInfo.workflowId, forkPosition)
        fork shouldNotBe null
        lastForkId = fork!!.id

        forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = 0,
            branchOutput = JsonPrimitive("result")
        )

        // Verify fork exists
        forkRepository.findByWorkflowIdAndPosition(
            testWorkflowInfo.workflowId,
            forkPosition
        ) shouldNotBe null

        // When - delete fork
        forkRepository.delete(fork.id)

        // Then - fork should be deleted
        forkRepository.findByWorkflowIdAndPosition(
            testWorkflowInfo.workflowId,
            forkPosition
        ) shouldBe null
    }

    // Helper function to setup a fork for testing
    private suspend fun setupFork(
        forkPosition: NodePosition,
        branchCount: Int,
        compete: Boolean
    ) {
        // Register a workflow with the specified fork configuration
        registerCustomForkWorkflow(branchCount, compete)

        val forkState = WorkflowState.RunningFork(
            taskStates = emptyMap(),
            nodePosition = forkPosition,
            rawInput = JsonPrimitive("input"),
            completedBranches = emptyMap()
        )

        val instanceMessage = InstanceMessage(
            workflowInfo = testWorkflowInfo,
            workflowState = forkState,
            parentId = null
        )

        val forkStartedMessage = DatabaseMessage.WorkflowPersistence(instanceMessage)
        databaseMessageHandler.handle(forkStartedMessage)
    }

    /**
     * Registers a custom fork workflow with specified branch count and compete mode.
     */
    private fun registerCustomForkWorkflow(branchCount: Int, compete: Boolean) {
        val branchesList = (0 until branchCount).joinToString("\n") { index ->
            """
    - branch$index:
        set:
          result: "branch-$index-output"
    """.trimIndent()
        }

        val workflowYaml = """
    document:
      dsl: '1.0.0'
      namespace: test
      name: fork-test
      version: '1.0.0'
    do:
      - testFork:
          fork:
            compete: $compete
            branches:
${branchesList.prependIndent("              ")}
""".trimIndent()

        println(workflowYaml)
        DefinitionCache.parseAndPut(workflowYaml)
    }
}
