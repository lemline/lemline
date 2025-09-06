// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories.bases

import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.models.IDV7
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.random.random
import com.lemline.runner.repositories.ParentRepository
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


/**
 * Abstract base class for retry repository tests.
 */
@ExperimentalTime
internal abstract class ParentRepositoryTest : OutboxRepositoryTest<ParentOutboxModel>() {

    @Inject
    override lateinit var repository: ParentRepository

    override fun createRandomEntity() = ParentOutboxModel(
        id = IDV7.random(),
        instanceMessage = InstanceMessage.random(),
        outboxScheduledFor = null,
    )

    override fun changeDelayedUntil(model: ParentOutboxModel) = model.copy(outboxDelayedUntil = Instant.random())
}
