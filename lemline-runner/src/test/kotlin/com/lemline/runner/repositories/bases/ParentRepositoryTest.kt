// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.ParentModel
import com.lemline.runner.repositories.ParentRepository
import jakarta.inject.Inject
import java.util.*
import kotlin.time.ExperimentalTime


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class ParentRepositoryTest : OutboxRepositoryTest<ParentModel>() {

    @Inject
    override lateinit var repository: ParentRepository

    override fun createRandomEntity() = ParentModel(
        instance = InstanceMessage.fromStrings(
            workflowId = UUID.randomUUID(),
            workflowName = randomString,
            workflowVersion = randomString,
            workflowPosition = randomString,
            workflowState = randomString,
            parentId = null,
        ),
        outboxScheduledFor = null,
    )

    override fun changeDelayedUntil(model: ParentModel) = model.copy(outboxDelayedUntil = randomInstant)
}
