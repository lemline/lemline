// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.ForkModel
import com.lemline.runner.outbox.bases.CleanerProcessorTest
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.bases.OptionalCleanerRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class ForkCleanerProcessorTest : CleanerProcessorTest<ForkModel>() {

    @Inject // Inject the specific repository
    lateinit var forkRepository: ForkRepository

    // Implement the abstract repository property
    override val cleanerRepository: OptionalCleanerRepository<ForkModel> by lazy { forkRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<ForkModel> = ForkModel::class

    // Implement the abstract factory method
    override fun createTestModel(
        runStatus: RunStatus,
        runAt: Instant,
    ) = ForkModel.random().copy(runStatus = runStatus, runAt = runAt)
}
