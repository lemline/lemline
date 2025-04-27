// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.quarkus.test.junit.QuarkusTest
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@QuarkusTest
internal class SimpleDurationConverterTest {

    @Test
    fun `should convert seconds to duration`() {
        // Given
        val input = "30s"

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofSeconds(30), duration)
    }

    @Test
    fun `should convert minutes to duration`() {
        // Given
        val input = "5m"

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofMinutes(5), duration)
    }

    @Test
    fun `should convert hours to duration`() {
        // Given
        val input = "2h"

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofHours(2), duration)
    }

    @Test
    fun `should convert days to duration`() {
        // Given
        val input = "7d"

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofDays(7), duration)
    }

    @Test
    fun `should handle uppercase input`() {
        // Given
        val input = "30S"

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofSeconds(30), duration)
    }

    @Test
    fun `should handle whitespace`() {
        // Given
        val input = " 30s "

        // When
        val duration = input.toDuration()

        // Then
        assertEquals(Duration.ofSeconds(30), duration)
    }

    @Test
    fun `should throw exception for empty input`() {
        // Given
        val input = ""

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            input.toDuration()
        }
    }

    @Test
    fun `should throw exception for invalid format`() {
        // Given
        val input = "invalid"

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            input.toDuration()
        }
    }

    @Test
    fun `should throw exception for unknown unit`() {
        // Given
        val input = "30x"

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            input.toDuration()
        }
    }
}
