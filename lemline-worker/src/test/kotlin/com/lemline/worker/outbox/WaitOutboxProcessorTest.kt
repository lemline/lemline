// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.worker.models.WaitModel
import com.lemline.worker.outbox.bases.OutboxProcessorTest
import com.lemline.worker.repositories.WaitRepository
import com.lemline.worker.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Runs the OutboxProcessorTest suite for WaitModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class WaitOutboxProcessorTest : OutboxProcessorTest<WaitModel>() {

    @Inject // Inject the specific repository
    lateinit var waitRepository: WaitRepository

    // Implement the abstract repository property
    override val testRepository: OutboxRepository<WaitModel>
        get() = waitRepository

    // Implement the abstract KClass property
    override val modelClass: KClass<WaitModel> = WaitModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = WaitModel.create(
        message = "Test Wait Message: $payload",
        delayedUntil = Instant.now() // Ensure ready for processing
    )
}
