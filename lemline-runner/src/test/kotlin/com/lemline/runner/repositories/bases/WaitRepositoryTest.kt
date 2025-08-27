// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.WaitRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime

/**
 * Abstract base class for wait repository tests.
 */
@ExperimentalTime
internal abstract class WaitRepositoryTest : OutboxRepositoryTest<WaitModel>() {

    @Inject
    override lateinit var repository: WaitRepository

    override fun createRandomEntity() = WaitModel(
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

    override fun changeDelayedUntil(model: WaitModel) = model.copy(outboxDelayedUntil = randomInstant)
}
