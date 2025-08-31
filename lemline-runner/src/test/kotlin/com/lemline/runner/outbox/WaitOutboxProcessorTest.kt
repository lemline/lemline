// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime

/**
 * Runs the OutboxProcessorTest suite for WaitModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class WaitOutboxProcessorTest : OutboxProcessorTest<WaitOutboxModel>() {

    @Inject // Inject the specific repository
    lateinit var waitRepository: WaitRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<WaitOutboxModel> by lazy { waitRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<WaitOutboxModel> = WaitOutboxModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = WaitOutboxModel(
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = Random.nextBytes(10).toString(),
            workflowVersion = Random.nextBytes(10).toString(),
            workflowPosition = Random.nextBytes(10).toString(),
            workflowState = "Test Retry Message: $payload",
            parentId = null
        ),
        outboxScheduledFor = null,
    )
}
