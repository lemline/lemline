// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.models.WithId
import com.lemline.runner.outbox.OutBoxStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ExperimentalTime
interface OutboxIngestionMessage : WithId {
    val outBoxStatus: OutBoxStatus

    val outboxScheduledFor: Instant?
}
