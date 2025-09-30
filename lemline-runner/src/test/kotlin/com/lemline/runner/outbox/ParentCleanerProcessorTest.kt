// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.models.ParentModel
import com.lemline.runner.outbox.bases.CleanerProcessorTest
import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentRepository
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
internal class ParentCleanerProcessorTest : CleanerProcessorTest<ParentModel>() {

    @Inject // Inject the specific repository
    lateinit var parentRepository: ParentRepository

    // Implement the abstract repository property
    override val cleanerRepository: OptionalCleanerRepository<ParentModel> by lazy { parentRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<ParentModel> = ParentModel::class

    // Implement the abstract factory method
    override fun createTestModel(
        runStatus: RunStatus,
        runAt: Instant
    ) = ParentModel.random().copy(
        runStatus = runStatus,
        runAt = runAt
    )
}
