// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.test.ops.CleanerRepositoryTest
import com.lemline.runner.common.test.ops.CrudRepositoryTest
import com.lemline.runner.common.test.ops.IdRepositoryTest
import com.lemline.runner.common.test.ops.OutboxRepositoryTest
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** SQL timestamp columns store microseconds. */
internal fun Instant.atMicros(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1_000 * 1_000)

internal fun randomCreationEventModel(
    workflowId: WorkflowId = WorkflowId.random(),
    scheduledFor: Instant? = Clock.System.now().atMicros(),
) = CreationEventModel(
    id = IDV7.random(),
    workflowId = workflowId,
    workflowNamespace = "ns-${Random.nextInt(1000)}",
    eventType = "org.open-workflow-specification.workflow.created.v1",
    eventTime = Clock.System.now().atMicros(),
    eventPayload = """{"specversion":"1.0","id":"${IDV7.random()}"}""",
    outboxScheduledFor = scheduledFor,
)

abstract class CreationEventRepositoryTestBase {

    protected abstract fun getDatabaseConfig(): DatabaseConfig
    protected abstract fun getRepository(): CreationEventRepository

    private fun createEntity() = randomCreationEventModel()
    private fun modifyEntity(entity: CreationEventModel): CreationEventModel {
        val randomInstant = (Clock.System.now() + Random.nextInt(-1000, 1000).days).atMicros()
        return entity.copy().apply { outboxDelayedUntil = randomInstant }
    }

    @Nested
    inner class CrudTests : CrudRepositoryTest<CreationEventModel>(
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        modifyEntity = ::modifyEntity
    )

    @Nested
    inner class IdTests : IdRepositoryTest<CreationEventModel>(
        idRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity
    )

    @Nested
    inner class OutboxTests : OutboxRepositoryTest<CreationEventModel>(
        outboxRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class CleanerTests : CleanerRepositoryTest<CreationEventModel>(
        cleanerRepository = { getRepository() },
        crudRepository = { getRepository() },
        createEntity = ::createEntity,
        getEntityKey = { it.id },
        databaseConfig = { getDatabaseConfig() }
    )

    @Nested
    inner class LifecycleTests {

        @BeforeEach
        fun clear() = runTest { getRepository().deleteAll() }

        @Test
        fun `a duplicate event ID is ignored and keeps the first frozen payload`() = runTest {
            val first = randomCreationEventModel()
            getRepository().insert(first) shouldBe 1
            getRepository().insert(first.copy(eventPayload = "{}", eventTime = first.eventTime + 1.days)) shouldBe 0
            val stored = getRepository().findById(first.id)!!
            stored.eventPayload shouldBe first.eventPayload
            stored.eventTime shouldBe first.eventTime
        }

        @Test
        fun `claims only ready unfinished rows among the given ids`() = runTest {
            val workflowId = WorkflowId.random()
            val ready = randomCreationEventModel(workflowId)
            val delayed = randomCreationEventModel(workflowId, scheduledFor = (Clock.System.now() + 1.days).atMicros())
            val exhausted = randomCreationEventModel(workflowId).apply { outboxAttemptCount = 5 }
            val done = randomCreationEventModel(workflowId).apply { outboxCompletedAt = Clock.System.now().atMicros() }
            val other = randomCreationEventModel()
            listOf(ready, delayed, exhausted, done, other).forEach { getRepository().insert(it) }

            getDatabaseConfig().withTransaction { conn ->
                getRepository().findReadyByIds(listOf(ready.id, delayed.id, exhausted.id, done.id), 5, conn)
                    .map { it.id } shouldBe listOf(ready.id)
                getRepository().findReadyByIds(emptyList(), 5, conn) shouldBe emptyList()
            }
            getRepository().findByWorkflowId(workflowId).map { it.id }.toSet() shouldBe
                setOf(ready.id, delayed.id, exhausted.id, done.id)
        }
    }
}
