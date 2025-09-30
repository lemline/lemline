// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.models.bases

import com.lemline.common.json.JsonSerializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

sealed interface OutboxModelBase : CleanerModelBase, OutboxColumnsBase, WithInstanceMessage, JsonSerializable

interface OptionalOutboxModel : OptionalCleanerModel, OutboxModelBase, OptionalOutboxColumns

interface OutboxModel : CleanerModel, OutboxModelBase, OutboxColumns


/**
 * Represents a model from an outbox, with nullable outboxDelayedUntil.
 */
interface OptionalOutboxColumns : OutboxColumnsBase, OptionalCleanerColumns {
    var runDelayedUntil: Instant?
}

/**
 * Represents a model from an outbox, with definite outboxDelayedUntil.
 */
interface OutboxColumns : OutboxColumnsBase, CleanerColumns {
    var runDelayedUntil: Instant
}

interface OutboxColumnsBase : CleanerColumnsBase {
    var runAttemptCount: Int
    var runLastErrorClass: String?
    var runLastErrorMessage: String?
    var runLastErrorStackTrace: String?
}

var OutboxColumnsBase.runDelayedUntil
    get() = when (this) {
        is OutboxColumns -> this.runDelayedUntil
        is OptionalOutboxColumns -> this.runDelayedUntil
        else -> error("Unknown OutboxColumnsBase model $this")
    }
    set(value) = when (this) {
        is OutboxColumns -> this.runDelayedUntil = value!!
        is OptionalOutboxColumns -> this.runDelayedUntil = value
        else -> error("Unknown OutboxColumnsBase model $this")
    }
