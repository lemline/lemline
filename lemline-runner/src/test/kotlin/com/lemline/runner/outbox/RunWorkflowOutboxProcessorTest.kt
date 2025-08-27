// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ParentModel
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
internal class RunWorkflowOutboxProcessorTest : OutboxProcessorTest<ParentModel>() {

    @Inject // Inject the specific repository
    lateinit var parentRepository: ParentRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<ParentModel> by lazy { parentRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<ParentModel> = ParentModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = ParentModel(
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
