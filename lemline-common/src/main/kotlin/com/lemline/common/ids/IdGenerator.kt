// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.ids

import com.github.f4b6a3.uuid.UuidCreator
import java.util.*

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

    /**
     * Generates a random UUID string.
     * Useful when time ordering is not required.
     *
     * @return A string representation of a random UUID
     */
    fun generateRandomId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Generates a prefixed ID.
     * Useful for creating IDs that need a specific prefix for identification.
     *
     * @param prefix The prefix to add to the ID
     * @param separator The separator between prefix and ID, defaults to "-"
     * @return A prefixed string representation of a time-ordered UUID
     */
    fun generatePrefixedId(prefix: String, separator: String = "-"): String {
        return "$prefix$separator${generateTimeBasedId()}"
    }
}
