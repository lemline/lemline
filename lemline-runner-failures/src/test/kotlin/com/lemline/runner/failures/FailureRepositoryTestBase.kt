// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.failures

import com.lemline.common.random.random
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.random.*
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

abstract class FailureRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): FailureRepository

    private fun createEntity() = FailureModel.random()
    private fun modifyEntity(entity: FailureModel) =
        entity.copy(payload = "modified-payload-${System.currentTimeMillis()}")

    @BeforeEach
    fun clean() = runTest {
        getRepository().deleteAll()
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<FailureModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<FailureModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    // ========== Custom FailureRepository Tests ==========

    @Test
    fun `should insert and retrieve a failure with all fields`() = runTest {
        val ex = IllegalStateException("boom")
        val model = FailureModel.from(
            id = IDV7.random(),
            instance = InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomWorkflowFailedEvent(),
            ),
            exception = ex,
        )

        getRepository().insert(model) shouldBe 1

        val all = getRepository().listAll()
        all shouldHaveSize 1
        val retrieved = all.first()

        retrieved shouldBe model
    }

    @Test
    fun `should insert and retrieve a failure with only payload`() = runTest {
        val ex = IllegalStateException("boom")
        val model = FailureModel.from(
            id = IDV7.random(),
            payload = "payload",
            exception = ex,
        )

        getRepository().insert(model) shouldBe 1

        val all = getRepository().listAll()
        all shouldHaveSize 1
        val retrieved = all.first()

        retrieved shouldBe model
    }

    @Test
    fun `should find failures by workflow id`() = runTest {
        val instance1 = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomWorkflowFailedEvent(),
        )
        val instance2 = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomWorkflowFailedEvent(),
        )

        val f1 = FailureModel.from(IDV7.random(), instance1, RuntimeException("e1")).copy(payload = "m1")
        val f2 = FailureModel.from(IDV7.random(), instance1, RuntimeException("e2")).copy(payload = "m2")
        val f3 = FailureModel.from(IDV7.random(), instance2, RuntimeException("e3")).copy(payload = "m3")

        getRepository().insert(listOf(f1, f2, f3))

        val found1 = getRepository().findByWorkflowId(instance1.workflowId)
        found1.map { it.payload }.toSet() shouldBe setOf("m1", "m2")

        val found2 = getRepository().findByWorkflowId(instance2.workflowId)
        found2.map { it.payload }.toSet() shouldBe setOf("m3")
    }

    @Test
    fun `count and deleteAll should work`() = runTest {
        val instance = InstanceMessage(
            workflowInfo = randomWorkflowInfo(),
            workflowState = randomWorkflowFailedEvent(),
        )
        val failures = List(3) { idx ->
            FailureModel.from(IDV7.random(), instance, RuntimeException("err-$idx")).copy(payload = "m$idx")
        }
        getRepository().insert(failures)
        getRepository().countAll() shouldBe 3

        getRepository().deleteAll()
        getRepository().countAll() shouldBe 0
        getRepository().listAll() shouldHaveSize 0
    }

    @Test
    fun `deleteById should remove an existing failure`() = runTest {
        val failure = FailureModel.from(
            IDV7.random(),
            InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomWorkflowFailedEvent(),
            ),
            RuntimeException("boom")
        )
        getRepository().insert(failure)

        val deletedCount = getRepository().deleteById(failure.id)

        deletedCount shouldBe 1
        getRepository().findById(failure.id) shouldBe null
    }

    @Test
    fun `deleteById should return 0 if failure does not exist`() = runTest {
        val failure = FailureModel.from(
            IDV7.random(),
            InstanceMessage(
                workflowInfo = randomWorkflowInfo(),
                workflowState = randomWorkflowFailedEvent(),
            ),
            RuntimeException("boom")
        )
        getRepository().insert(failure)
        val randomId = IDV7.random()

        val deletedCount = getRepository().deleteById(randomId)

        deletedCount shouldBe 0
        getRepository().findById(failure.id) shouldNotBe null
    }

}
