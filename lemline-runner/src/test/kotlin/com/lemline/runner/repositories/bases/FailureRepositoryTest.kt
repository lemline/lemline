// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.repositories.FailureRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Abstract base test suite for FailureRepository implementations.
 *
 * Verifies core persistence and retrieval operations for FailureModel entities
 * across different database backends. Concrete DB-specific test classes should
 * extend this class and provide the proper Quarkus test profile.
 */
@ExperimentalTime
internal abstract class FailureRepositoryTest {

    @Inject
    protected lateinit var repository: FailureRepository

    @BeforeEach
    fun clean() = runTest {
        repository.deleteAll()
    }

    private fun sampleInstance(): InstanceMessage = InstanceMessage.fromStrings(
        workflowId = UUID.randomUUID(),
        workflowName = "wf-name",
        workflowVersion = "1.0.0",
        workflowPosition = "pos",
        workflowState = "state",
        parentId = null,
    )

    @Test
    fun `should insert and retrieve a failure with all fields`() = runTest {
        val ex = IllegalStateException("boom")
        val model = FailureModel.from(
            instance = sampleInstance(),
            error = ex,
        ).copy(message = "custom message")

        repository.insert(model) shouldBe 1

        // listAll should return the same model
        val all = repository.listAll()
        all shouldHaveSize 1
        val retrieved = all.first()

        // basic id and instance mapping
        retrieved shouldBe model
    }

    @Test
    fun `should find failures by workflow id`() = runTest {
        val instance1 = sampleInstance()
        val instance2 = sampleInstance()

        val f1 = FailureModel.from(instance1, RuntimeException("e1")).copy(message = "m1")
        val f2 = FailureModel.from(instance1, RuntimeException("e2")).copy(message = "m2")
        val f3 = FailureModel.from(instance2, RuntimeException("e3")).copy(message = "m3")

        repository.insert(listOf(f1, f2, f3))

        val found1 = repository.findWithWorkflowId(instance1.workflowId)
        found1.map { it.message }.toSet() shouldBe setOf("m1", "m2")

        val found2 = repository.findWithWorkflowId(instance2.workflowId)
        found2.map { it.message }.toSet() shouldBe setOf("m3")
    }

    @Test
    fun `count and deleteAll should work`() = runTest {
        val instance = sampleInstance()
        val failures = List(3) { idx ->
            FailureModel.from(instance, RuntimeException("err-$idx")).copy(message = "m$idx")
        }
        repository.insert(failures)
        repository.count() shouldBe 3

        repository.deleteAll()
        repository.count() shouldBe 0
        repository.listAll() shouldHaveSize 0
    }
}
