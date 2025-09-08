// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.common.values.IDV7
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.FailureRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
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

    @Test
    fun `should insert and retrieve a failure with all fields`() = runTest {
        val ex = IllegalStateException("boom")
        val model = FailureModel.from(
            id = IDV7.random(),
            instance = InstanceMessage.random(),
            error = ex,
        )

        repository.insert(model) shouldBe 1

        // listAll should return the same model
        val all = repository.listAll()
        all shouldHaveSize 1
        val retrieved = all.first()

        // basic id and instance mapping
        retrieved shouldBe model
    }

    @Test
    fun `should insert and retrieve a failure with only payload`() = runTest {
        val ex = IllegalStateException("boom")
        val model = FailureModel.from(
            id = IDV7.random(),
            payload = "payload",
            error = ex,
        )

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
        val instance1 = InstanceMessage.random()
        val instance2 = InstanceMessage.random()

        val f1 = FailureModel.from(IDV7.random(), instance1, RuntimeException("e1")).copy(payload = "m1")
        val f2 = FailureModel.from(IDV7.random(), instance1, RuntimeException("e2")).copy(payload = "m2")
        val f3 = FailureModel.from(IDV7.random(), instance2, RuntimeException("e3")).copy(payload = "m3")

        repository.insert(listOf(f1, f2, f3))

        val found1 = repository.findWithWorkflowId(instance1.workflowId)
        found1.map { it.payload }.toSet() shouldBe setOf("m1", "m2")

        val found2 = repository.findWithWorkflowId(instance2.workflowId)
        found2.map { it.payload }.toSet() shouldBe setOf("m3")
    }

    @Test
    fun `count and deleteAll should work`() = runTest {
        val instance = InstanceMessage.random()
        val failures = List(3) { idx ->
            FailureModel.from(IDV7.random(), instance, RuntimeException("err-$idx")).copy(payload = "m$idx")
        }
        repository.insert(failures)
        repository.count() shouldBe 3

        repository.deleteAll()
        repository.count() shouldBe 0
        repository.listAll() shouldHaveSize 0
    }

    @Test
    fun `deleteById should remove an existing failure`() = runTest {
        // Given
        val failure = FailureModel.from(IDV7.random(), InstanceMessage.random(), RuntimeException("boom"))
        repository.insert(failure)

        // When
        val deletedCount = repository.deleteById(failure.id)

        // Then
        deletedCount shouldBe 1
        repository.findById(failure.id) shouldBe null
    }

    @Test
    fun `deleteById should return 0 if failure does not exist`() = runTest {
        // Given
        val failure = FailureModel.from(IDV7.random(), InstanceMessage.random(), RuntimeException("boom"))
        repository.insert(failure)
        val randomId = IDV7.random()

        // When
        val deletedCount = repository.deleteById(randomId)

        // Then
        deletedCount shouldBe 0
        repository.findById(failure.id) shouldNotBe null
    }
}
