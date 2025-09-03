// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitOutboxModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createRandomEntity() = WaitOutboxModel(
        id = UUID.randomUUID(),
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = randomString,
            workflowVersion = randomString,
            workflowPosition = randomString,
            workflowState = randomString,
            parentId = null,
        ),
        outboxScheduledFor = randomInstant,
    )

    override fun changeDelayedUntil(model: WaitOutboxModel) = model.copy(outboxDelayedUntil = randomInstant)
}
