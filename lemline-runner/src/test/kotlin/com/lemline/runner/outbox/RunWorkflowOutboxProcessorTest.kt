// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.instances.InstanceMessageTest.Companion.sampleInstance
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
internal class RunWorkflowOutboxProcessorTest : OutboxProcessorTest<ParentOutboxModel>() {

    @Inject // Inject the specific repository
    lateinit var parentRepository: ParentRepository

    @Inject // Inject the failure repository
    override lateinit var failureRepository: FailureRepository

    // Implement the abstract repository property
    override val outboxRepository: OutboxRepository<ParentOutboxModel> by lazy { parentRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<ParentOutboxModel> = ParentOutboxModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = ParentOutboxModel(
        id = IDV7.new(),
        instanceMessage = sampleInstance(),
        outboxScheduledFor = null
    )
}
