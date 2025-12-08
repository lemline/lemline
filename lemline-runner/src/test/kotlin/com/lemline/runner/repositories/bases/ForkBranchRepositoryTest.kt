// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ForkBranchRepository
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.bases.ops.CrudRepositoryTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for ForkBranchRepository implementations.
 *
 * Tests CRUD operations and custom methods for fork branch entities.
 * Fork branches have a composite key (fork_id, name) and require a parent fork.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class ForkBranchRepositoryTest {

    @Inject
    protected lateinit var repository: ForkBranchRepository

    @Inject
    protected lateinit var forkRepository: ForkRepository

    // Shared fork for tests that need a parent
    private var sharedFork: ForkModel? = null

    @BeforeEach
    fun setup() = runTest {
        // Clear existing data
        repository.deleteAll()
        forkRepository.deleteAll()
        sharedFork = null
    }

    /**
     * Creates a fork to use as parent for branch tests.
     */
    private fun getOrCreateFork(): ForkModel {
        return sharedFork ?: ForkModel.random().also {
            sharedFork = it
            runBlocking { forkRepository.insert(it) }
        }
    }

    /**
     * Creates an entity for CrudTests. Creates a fork if needed.
     */
    private fun createEntity(): ForkBranchModel {
        val fork = getOrCreateFork()
        return ForkBranchModel.random(fork.id)
    }

    /**
     * Modifies an entity for update tests.
     */
    private fun modifyEntity(entity: ForkBranchModel): ForkBranchModel {
        return entity.copy(
            output = """{"modified": true}""",
            completedAt = Clock.System.now()
        )
    }

    // ========== Nested Standard Repository Tests ==========

    @Nested
    inner class CrudTests : CrudRepositoryTest<ForkBranchModel>(
        crudRepository = { repository },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    // ========== findByForkId Tests ==========

    @Test
    fun `findByForkId should return empty list when fork has no branches`() = runTest {
        // Given - a fork with no branches
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        // When
        val branches = repository.findByForkId(fork.id)

        // Then
        branches.shouldBeEmpty()
    }

    @Test
    fun `findByForkId should return all branches for a fork`() = runTest {
        // Given - a fork with 3 branches
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        val branches = listOf(
            ForkBranchModel.random(fork.id).copy(name = "branch-a"),
            ForkBranchModel.random(fork.id).copy(name = "branch-b"),
            ForkBranchModel.random(fork.id).copy(name = "branch-c")
        )
        repository.insert(branches)

        // When
        val result = repository.findByForkId(fork.id)

        // Then
        result shouldHaveSize 3
        result.map { it.name } shouldContainExactlyInAnyOrder listOf("branch-a", "branch-b", "branch-c")
    }

    @Test
    fun `findByForkId should return branches ordered by name`() = runTest {
        // Given - a fork with branches inserted out of order
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        val branches = listOf(
            ForkBranchModel.random(fork.id).copy(name = "zebra"),
            ForkBranchModel.random(fork.id).copy(name = "alpha"),
            ForkBranchModel.random(fork.id).copy(name = "beta")
        )
        repository.insert(branches)

        // When
        val result = repository.findByForkId(fork.id)

        // Then
        result.map { it.name } shouldBe listOf("alpha", "beta", "zebra")
    }

    @Test
    fun `findByForkId should only return branches for specified fork`() = runTest {
        // Given - two forks with branches
        val fork1 = ForkModel.random()
        val fork2 = ForkModel.random()
        forkRepository.insert(listOf(fork1, fork2))

        val branches1 = listOf(
            ForkBranchModel.random(fork1.id).copy(name = "fork1-branch1"),
            ForkBranchModel.random(fork1.id).copy(name = "fork1-branch2")
        )
        val branches2 = listOf(
            ForkBranchModel.random(fork2.id).copy(name = "fork2-branch1")
        )
        repository.insert(branches1 + branches2)

        // When
        val result = repository.findByForkId(fork1.id)

        // Then
        result shouldHaveSize 2
        result.all { it.forkId == fork1.id } shouldBe true
    }

    @Test
    fun `findByForkId should return empty list for non-existent fork`() = runTest {
        // When
        val result = repository.findByForkId(IDV7.random())

        // Then
        result.shouldBeEmpty()
    }

    // ========== deleteByForkId Tests ==========

    @Test
    fun `deleteByForkId should delete all branches for a fork`() = runTest {
        // Given - a fork with 3 branches
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        val branches = listOf(
            ForkBranchModel.random(fork.id).copy(name = "branch-1"),
            ForkBranchModel.random(fork.id).copy(name = "branch-2"),
            ForkBranchModel.random(fork.id).copy(name = "branch-3")
        )
        repository.insert(branches)

        // Verify branches exist
        repository.findByForkId(fork.id) shouldHaveSize 3

        // When
        val deleted = repository.deleteByForkId(fork.id)

        // Then
        deleted shouldBe 3
        repository.findByForkId(fork.id).shouldBeEmpty()
    }

    @Test
    fun `deleteByForkId should not affect branches of other forks`() = runTest {
        // Given - two forks with branches
        val fork1 = ForkModel.random()
        val fork2 = ForkModel.random()
        forkRepository.insert(listOf(fork1, fork2))

        val branches1 = listOf(
            ForkBranchModel.random(fork1.id).copy(name = "fork1-branch")
        )
        val branches2 = listOf(
            ForkBranchModel.random(fork2.id).copy(name = "fork2-branch")
        )
        repository.insert(branches1 + branches2)

        // When - delete fork1's branches
        repository.deleteByForkId(fork1.id)

        // Then - fork2's branches should remain
        repository.findByForkId(fork2.id) shouldHaveSize 1
    }

    // ========== Branch Update Tests ==========

    @Test
    fun `should update branch output and completion timestamp`() = runTest {
        // Given - a fork with a branch
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        val branch = ForkBranchModel.random(fork.id).copy(
            name = "test-branch",
            output = null,
            completedAt = null
        )
        repository.insert(branch)

        // When - update branch with output
        val completedAt = Clock.System.now()
        val updated = branch.copy(
            output = """{"result": "success"}""",
            completedAt = completedAt
        )
        val updateCount = repository.update(updated)

        // Then
        updateCount shouldBe 1
        val retrieved = repository.findByForkId(fork.id).first()
        retrieved.output shouldBe """{"result": "success"}"""
        retrieved.completedAt shouldBe completedAt
    }

    @Test
    fun `should update branch with error information`() = runTest {
        // Given - a fork with a branch
        val fork = ForkModel.random()
        forkRepository.insert(fork)

        val branch = ForkBranchModel.random(fork.id).copy(
            name = "failing-branch",
            output = null,
            completedAt = null,
            failedAt = null
        )
        repository.insert(branch)

        // When - mark branch as failed
        val failedAt = Clock.System.now()
        val updated = branch.copy(
            failedAt = failedAt,
            errorReason = "TaskFailed",
            errorClass = "java.lang.RuntimeException",
            errorMessage = "Something went wrong",
            errorStackTrace = "at com.example.Test.test(Test.kt:42)"
        )
        val updateCount = repository.update(updated)

        // Then
        updateCount shouldBe 1
        val retrieved = repository.findByForkId(fork.id).first()
        retrieved.failedAt shouldBe failedAt
        retrieved.errorReason shouldBe "TaskFailed"
        retrieved.errorClass shouldBe "java.lang.RuntimeException"
        retrieved.errorMessage shouldBe "Something went wrong"
        retrieved.errorStackTrace shouldBe "at com.example.Test.test(Test.kt:42)"
    }
}
