// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.RetryRepository
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
internal class RetryOutboxProcessorTest : OutboxProcessorTest<RetryOutboxModel>() {

    @Inject // Inject the retry repository
    lateinit var retryRepository: RetryRepository

    @Inject // Inject the failure repository
    override lateinit var failureRepository: FailureRepository

    // Implement the abstract repository property
    override val outboxRepository: OutboxRepository<RetryOutboxModel> by lazy { retryRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<RetryOutboxModel> = RetryOutboxModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = RetryOutboxModel(
        id = UUID.randomUUID(),
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = Random.nextBytes(10).toString(),
            workflowVersion = Random.nextBytes(10).toString(),
            workflowPosition = Random.nextBytes(10).toString(),
            workflowState = "Test Retry Message: $payload",
            parentId = null,
        ),
        outboxScheduledFor = null,
    )
}
