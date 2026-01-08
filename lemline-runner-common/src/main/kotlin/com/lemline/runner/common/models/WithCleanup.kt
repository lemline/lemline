// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.models

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Interface for entities that support scheduled cleanup/deletion.
 *
 * The cleanup pattern works by:
 * 1. When an entity is ready for eventual deletion, `cleanupAfter` is set to `now + retention`
 * 2. Cleanup schedulers periodically delete records where `cleanupAfter < NOW()`
 *
 * This is simpler than calculating cutoff dates at query time - the cleanup query
 * just checks `WHERE cleanup_after < NOW()`.
 *
 * @see com.lemline.runner.common.cleaner.AbstractCleaner for cleanup infrastructure
 */
@ExperimentalTime
interface WithCleanup {

    /**
     * Timestamp after which this entity can be deleted by cleanup processes.
     * - NULL: Entity is still active and should not be deleted
     * - NOT NULL: Entity can be deleted after this timestamp
     *
     * Set to `now + retention period` when the entity completes its purpose.
     */
    var cleanupAfter: Instant?
}
