// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.ids

import com.github.f4b6a3.uuid.UuidCreator

/**
 * Utility class for generating IDs used throughout the application.
 * Centralizes ID generation logic to ensure consistency.
 */
object IdGenerator {
    /**
     * Generates a time-ordered UUID string.
     * Uses UuidCreator to create a time-ordered UUID which provides both uniqueness
     * and chronological ordering.
     *
     * @return A string representation of a time-ordered UUID
     */
    fun generateTimeBasedId(): String {
        return UuidCreator.getTimeOrderedEpoch().toString()
    }
}
