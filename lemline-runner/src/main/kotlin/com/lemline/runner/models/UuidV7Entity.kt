// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

/**
 * Base class for entities using UUID v7 as a primary key.
 * This class provides the common ID field and generation strategy for all entities.
 *
 * UUID v7 (time-ordered) provides:
 * - Time-based ordering for efficient indexing
 * - Global uniqueness
 * - No central coordination needed
 * - Better performance than UUID v4
 */
abstract class UuidV7Entity {
    abstract val id: String
}
