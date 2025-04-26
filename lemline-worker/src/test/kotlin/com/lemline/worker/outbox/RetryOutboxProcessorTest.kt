// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.bases.OutboxProcessorTest
import com.lemline.worker.repositories.RetryRepository
import com.lemline.worker.tests.profiles.H2Profile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Concrete test class for OutboxProcessor using RetryModel and RetryRepository.
 * Inherits test logic from the abstract OutboxProcessorTest.
 */
@QuarkusTest
@TestProfile(H2Profile::class) // Apply H2 profile for testing
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
