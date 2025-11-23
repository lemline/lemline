// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.RetryModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.random.random
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Runs the OutboxProcessorTest suite for RetryModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class RetryOutboxProcessorTest : OutboxProcessorTest<RetryModel>() {

    @Inject // Inject the retry repository
    lateinit var retryRepository: RetryRepository

    @Inject // Inject the failure repository
    override lateinit var failureRepository: FailureRepository

    // Implement the abstract repository property
    override val outboxRepository: OutboxRepository<RetryModel> by lazy { retryRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<RetryModel> = RetryModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = RetryModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = WorkflowInfo.random(),
            workflowState = WorkflowEvent.RetryScheduled.random(),
        ),
        outboxScheduledFor = Clock.System.now(),
        errorReason = "test-error-reason",
        errorClass = "test-error-class",
        errorMessage = "test-error-message",
        errorStackTrace = "test-error-stacktrace",
    )
}
