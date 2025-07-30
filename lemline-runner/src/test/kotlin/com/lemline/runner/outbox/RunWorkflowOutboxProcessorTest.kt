// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.RunWorkflowModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.RunWorkflowRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.reflect.KClass

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class RunWorkflowOutboxProcessorTest : OutboxProcessorTest<RunWorkflowModel>() {

    @Inject // Inject the specific repository
    lateinit var runWorkflowRepository: RunWorkflowRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<RunWorkflowModel>
        get() = runWorkflowRepository

    // Implement the abstract KClass property
    override val modelClass: KClass<RunWorkflowModel> = RunWorkflowModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = RunWorkflowModel(
        message = "Test Retry Message: $payload",
    )
}
