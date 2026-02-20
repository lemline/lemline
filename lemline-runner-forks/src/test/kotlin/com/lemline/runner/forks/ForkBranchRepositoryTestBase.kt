// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.forks

import com.lemline.common.values.IDV7
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
abstract class ForkBranchRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getForkRepository(): ForkRepository
    protected abstract fun getBranchRepository(): ForkBranchRepository

    private var sharedFork: ForkModel? = null

    @BeforeEach
    fun setup() = runTest {
        getBranchRepository().deleteAll()
        getForkRepository().deleteAll()
        sharedFork = null
    }

    private fun getOrCreateFork(): ForkModel {
        return sharedFork ?: randomForkModel().also {
            sharedFork = it
            runBlocking { getForkRepository().insert(it) }
        }
    }

    private fun createEntity(): ForkBranchModel {
        val fork = getOrCreateFork()
        return randomForkBranchModel(fork.id)
    }

    private fun modifyEntity(entity: ForkBranchModel): ForkBranchModel {
        return entity.copy(
            branchOutput = """{"modified": true}""",
            completedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        )
    }

    // ========== Nested Standard Repository Tests ==========

    @Nested
    inner class CrudTests : CrudRepositoryTest<ForkBranchModel>(
        crudRepository = { getBranchRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    // ========== findByForkId Tests ==========

    @Test
    fun `findByForkId should return empty list when fork has no branches`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branches = getBranchRepository().findByForkId(fork.id)

        branches.shouldBeEmpty()
    }

    @Test
    fun `findByForkId should return all branches for a fork`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branches = listOf(
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-a"),
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-b"),
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-c")
        )
        getBranchRepository().insert(branches)

        val result = getBranchRepository().findByForkId(fork.id)

        result shouldHaveSize 3
        result.map { it.branchPosition } shouldContainExactlyInAnyOrder listOf("branch-a", "branch-b", "branch-c")
    }

    @Test
    fun `findByForkId should return branches ordered by name`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branches = listOf(
            randomForkBranchModel(fork.id).copy(branchPosition = "zebra"),
            randomForkBranchModel(fork.id).copy(branchPosition = "alpha"),
            randomForkBranchModel(fork.id).copy(branchPosition = "beta")
        )
        getBranchRepository().insert(branches)

        val result = getBranchRepository().findByForkId(fork.id)

        result.map { it.branchPosition } shouldBe listOf("alpha", "beta", "zebra")
    }

    @Test
    fun `findByForkId should only return branches for specified fork`() = runTest {
        val fork1 = randomForkModel()
        val fork2 = randomForkModel()
        getForkRepository().insert(listOf(fork1, fork2))

        val branches1 = listOf(
            randomForkBranchModel(fork1.id).copy(branchPosition = "fork1-branch1"),
            randomForkBranchModel(fork1.id).copy(branchPosition = "fork1-branch2")
        )
        val branches2 = listOf(
            randomForkBranchModel(fork2.id).copy(branchPosition = "fork2-branch1")
        )
        getBranchRepository().insert(branches1 + branches2)

        val result = getBranchRepository().findByForkId(fork1.id)

        result shouldHaveSize 2
        result.all { it.forkId == fork1.id } shouldBe true
    }

    @Test
    fun `findByForkId should return empty list for non-existent fork`() = runTest {
        val result = getBranchRepository().findByForkId(IDV7.random())

        result.shouldBeEmpty()
    }

    // ========== deleteByForkId Tests ==========

    @Test
    fun `deleteByForkId should delete all branches for a fork`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branches = listOf(
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-1"),
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-2"),
            randomForkBranchModel(fork.id).copy(branchPosition = "branch-3")
        )
        getBranchRepository().insert(branches)

        getBranchRepository().findByForkId(fork.id) shouldHaveSize 3

        val deleted = getBranchRepository().deleteByForkId(fork.id)

        deleted shouldBe 3
        getBranchRepository().findByForkId(fork.id).shouldBeEmpty()
    }

    @Test
    fun `deleteByForkId should not affect branches of other forks`() = runTest {
        val fork1 = randomForkModel()
        val fork2 = randomForkModel()
        getForkRepository().insert(listOf(fork1, fork2))

        val branches1 = listOf(
            randomForkBranchModel(fork1.id).copy(branchPosition = "fork1-branch")
        )
        val branches2 = listOf(
            randomForkBranchModel(fork2.id).copy(branchPosition = "fork2-branch")
        )
        getBranchRepository().insert(branches1 + branches2)

        getBranchRepository().deleteByForkId(fork1.id)

        getBranchRepository().findByForkId(fork2.id) shouldHaveSize 1
    }

    // ========== Branch Update Tests ==========

    @Test
    fun `should update branch output and completion timestamp`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branch = randomForkBranchModel(fork.id).copy(
            branchPosition = "test-branch",
            branchOutput = null,
            completedAt = null
        )
        getBranchRepository().insert(branch)

        // Truncate to seconds to avoid flaky tests from sub-second rounding differences across databases
        val completedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val updated = branch.copy(
            branchOutput = """{"result": "success"}""",
            completedAt = completedAt
        )
        val updateCount = getBranchRepository().update(updated)

        updateCount shouldBe 1
        val retrieved = getBranchRepository().findByForkId(fork.id).first()
        retrieved.branchOutput shouldBe """{"result": "success"}"""
        retrieved.completedAt shouldBe completedAt
    }

    @Test
    fun `should update branch with error information`() = runTest {
        val fork = randomForkModel()
        getForkRepository().insert(fork)

        val branch = randomForkBranchModel(fork.id).copy(
            branchPosition = "failing-branch",
            branchOutput = null,
            completedAt = null,
            failedAt = null
        )
        getBranchRepository().insert(branch)

        val failedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val updated = branch.copy(
            failedAt = failedAt,
            errorReason = "TaskFailed",
            errorClass = "java.lang.RuntimeException",
            errorMessage = "Something went wrong",
            errorStackTrace = "at com.example.Test.test(Test.kt:42)"
        )
        val updateCount = getBranchRepository().update(updated)

        updateCount shouldBe 1
        val retrieved = getBranchRepository().findByForkId(fork.id).first()
        retrieved.failedAt shouldBe failedAt
        retrieved.errorReason shouldBe "TaskFailed"
        retrieved.errorClass shouldBe "java.lang.RuntimeException"
        retrieved.errorMessage shouldBe "Something went wrong"
        retrieved.errorStackTrace shouldBe "at com.example.Test.test(Test.kt:42)"
    }
}
