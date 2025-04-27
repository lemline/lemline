// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.GenericGenerator

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
@MappedSuperclass
abstract class UuidV7Entity {
    @Id
    @GeneratedValue(generator = "uuid7")
    @GenericGenerator(name = "uuid7", strategy = "com.lemline.runner.repositories.TimeOrderedUuidGenerator")
    @Column(name = "id", length = 36)
    lateinit var id: String
}
