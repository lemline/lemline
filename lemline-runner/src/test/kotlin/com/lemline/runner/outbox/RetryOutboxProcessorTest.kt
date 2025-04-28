// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.RetryRepository
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
internal class RetryOutboxProcessorTest : OutboxProcessorTest<RetryModel>() {

    @Inject // Inject the specific repository
    lateinit var retryRepository: RetryRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<RetryModel>
        get() = retryRepository

    // Implement the abstract KClass property
    override val modelClass: KClass<RetryModel> = RetryModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String): RetryModel {
        // Use the RetryModel companion object factory method
        return RetryModel.create(
            message = "Test Retry Message: $payload",
            delayedUntil = Instant.now() // Ensure ready for processing
        )
    }
}
