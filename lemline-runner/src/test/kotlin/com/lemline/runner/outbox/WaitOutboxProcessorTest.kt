// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.bases.OutboxProcessorTest
import com.lemline.runner.random.random
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Runs the OutboxProcessorTest suite for WaitModel
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@ExperimentalTime
@ExperimentalSerializationApi
internal class WaitOutboxProcessorTest : OutboxProcessorTest<WaitOutboxModel>() {

    @Inject // Inject the specific repository
    lateinit var waitRepository: WaitRepository

    @Inject // Inject the failure repository
    override lateinit var failureRepository: FailureRepository

    // Implement the abstract repository property
    override val outboxRepository: OutboxRepository<WaitOutboxModel> by lazy { waitRepository }

    // Implement the abstract KClass property
    override val modelClass: KClass<WaitOutboxModel> = WaitOutboxModel::class

    // Implement the abstract factory method
    override fun createTestModel(payload: String) = WaitOutboxModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage(
            workflowInfo = WorkflowInfo.random(),
            workflowState = WorkflowEvent.WaitStarted.random(),
        ),
        scheduledFor = Clock.System.now(),
    )
}
