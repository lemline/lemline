// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class RunWorkflowOutboxProcessorTest : OutboxProcessorTest<ParentOutboxModel>() {

    @Inject // Inject the specific repository
    lateinit var parentRepository: ParentRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<ParentOutboxModel> by lazy { parentRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<ParentOutboxModel> = ParentOutboxModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = ParentOutboxModel(
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = Random.nextBytes(10).toString(),
            workflowVersion = Random.nextBytes(10).toString(),
            workflowPosition = Random.nextBytes(10).toString(),
            workflowState = "Test Retry Message: $payload",
            parentId = null,
        ),
        outboxScheduledFor = null
    )
}
