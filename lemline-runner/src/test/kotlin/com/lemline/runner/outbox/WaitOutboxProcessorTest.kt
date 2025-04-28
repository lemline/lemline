// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.WaitModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
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
    override fun createTestModel(payload: String) = WaitModel(
        message = "Test Wait Message: $payload",
        delayedUntil = Instant.now() // Ensure ready for processing
    )
}
