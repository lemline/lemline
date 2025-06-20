// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.RunModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.RunRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class RunOutboxProcessorTest : OutboxProcessorTest<RunModel>() {

    @Inject // Inject the specific repository
    lateinit var runRepository: RunRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<RunModel>
        get() = runRepository

    // Implement the abstract KClass property
    override val modelClass: KClass<RunModel> = RunModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String): RunModel {
        // Use the RunModel companion object factory method
        return RunModel(
            message = "Test Retry Message: $payload",
            delayedUntil = Instant.now() // Ensure ready for processing
        )
    }
}
