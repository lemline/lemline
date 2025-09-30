// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.models.bases

import com.lemline.runner.outbox.bases.RunStatus
import com.lemline.runner.repositories.capabilities.IdColumn
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

sealed interface CleanerModelBase : CleanerColumnsBase

interface OptionalCleanerModel : CleanerModelBase, OptionalCleanerColumns

interface CleanerModel : CleanerModelBase, CleanerColumns


/**
 * Represents a model that includes a status and a nullable runAt date.
 */
interface OptionalCleanerColumns : CleanerColumnsBase {
    val runAt: Instant?
}

/**
 * Represents a model that includes a status and a definite runAt date.
 */
interface CleanerColumns : CleanerColumnsBase {
    val runAt: Instant
}

interface CleanerColumnsBase : IdColumn {
    var runStatus: RunStatus
}

internal val CleanerColumnsBase.runAt
    get() = when (this) {
        is CleanerColumns -> this.runAt
        is OptionalCleanerColumns -> this.runAt
        else -> error("Unknown CleanerColumnsBase model $this")
    }
