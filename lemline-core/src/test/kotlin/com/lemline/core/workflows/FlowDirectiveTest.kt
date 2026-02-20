// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.tasks.FlowDirective
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.core.tasks.FlowDirectiveGoto
import com.lemline.core.tasks.toJava
import com.lemline.core.tasks.toKotlin
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FlowDirectiveTest {
    // ========================================
    // Java/Kotlin Conversion Tests
    // ========================================

    @Test
    fun `should convert Continue to Java FlowDirective`() {
        // Given
        val kotlinDirective = FlowDirectiveEnum.Continue

        // When
        val javaDirective = kotlinDirective.toJava()

        // Then
        assertEquals(io.serverlessworkflow.api.types.FlowDirectiveEnum.CONTINUE, javaDirective.flowDirectiveEnum)
    }

    @Test
    fun `should convert Exit to Java FlowDirective`() {
        // Given
        val kotlinDirective = FlowDirectiveEnum.Exit

        // When
        val javaDirective = kotlinDirective.toJava()

        // Then
        assertEquals(io.serverlessworkflow.api.types.FlowDirectiveEnum.EXIT, javaDirective.flowDirectiveEnum)
    }

    @Test
    fun `should convert End to Java FlowDirective`() {
        // Given
        val kotlinDirective = FlowDirectiveEnum.End

        // When
        val javaDirective = kotlinDirective.toJava()

        // Then
        assertEquals(io.serverlessworkflow.api.types.FlowDirectiveEnum.END, javaDirective.flowDirectiveEnum)
    }

    @Test
    fun `should convert FlowDirectiveGoto to Java FlowDirective`() {
        // Given
        val targetTask = "myTask"
        val kotlinDirective = FlowDirectiveGoto(targetTask)

        // When
        val javaDirective = kotlinDirective.toJava()

        // Then
        assertEquals(targetTask, javaDirective.string)
    }

    @Test
    fun `should convert Java Continue to Kotlin FlowDirective`() {
        // Given
        val javaDirective = io.serverlessworkflow.api.types.FlowDirective().apply {
            flowDirectiveEnum = io.serverlessworkflow.api.types.FlowDirectiveEnum.CONTINUE
        }

        // When
        val kotlinDirective = javaDirective.toKotlin()

        // Then
        assertEquals(FlowDirectiveEnum.Continue, kotlinDirective)
    }

    @Test
    fun `should convert Java Exit to Kotlin FlowDirective`() {
        // Given
        val javaDirective = io.serverlessworkflow.api.types.FlowDirective().apply {
            flowDirectiveEnum = io.serverlessworkflow.api.types.FlowDirectiveEnum.EXIT
        }

        // When
        val kotlinDirective = javaDirective.toKotlin()

        // Then
        assertEquals(FlowDirectiveEnum.Exit, kotlinDirective)
    }

    @Test
    fun `should convert Java End to Kotlin FlowDirective`() {
        // Given
        val javaDirective = io.serverlessworkflow.api.types.FlowDirective().apply {
            flowDirectiveEnum = io.serverlessworkflow.api.types.FlowDirectiveEnum.END
        }

        // When
        val kotlinDirective = javaDirective.toKotlin()

        // Then
        assertEquals(FlowDirectiveEnum.End, kotlinDirective)
    }

    @Test
    fun `should convert Java String FlowDirective to Kotlin FlowDirectiveGoto`() {
        // Given
        val targetTask = "targetTask"
        val javaDirective = io.serverlessworkflow.api.types.FlowDirective().apply {
            string = targetTask
        }

        // When
        val kotlinDirective = javaDirective.toKotlin()

        // Then
        assertIs<FlowDirectiveGoto>(kotlinDirective)
        assertEquals(targetTask, kotlinDirective.target)
    }

    @Test
    fun `should throw exception when converting invalid Java FlowDirective`() {
        // Given - Create a FlowDirective with null value (edge case)
        val javaDirective = io.serverlessworkflow.api.types.FlowDirective()

        // When/Then
        assertThrows<IllegalArgumentException> {
            javaDirective.toKotlin()
        }
    }

    @Test
    fun `should round-trip convert all FlowDirective types between Kotlin and Java`() {
        // Given
        val kotlinDirectives = listOf(
            FlowDirectiveEnum.Continue,
            FlowDirectiveEnum.Exit,
            FlowDirectiveEnum.End,
            FlowDirectiveGoto("task1"),
            FlowDirectiveGoto("task2"),
        )

        // When/Then - Convert to Java and back
        kotlinDirectives.forEach { kotlinDirective ->
            val javaDirective = kotlinDirective.toJava()
            val convertedBack = javaDirective.toKotlin()
            assertEquals(kotlinDirective, convertedBack)
        }
    }

    @Test
    fun `FlowDirectiveGoto instances with same target should be equal`() {
        // Given
        val goto1 = FlowDirectiveGoto("task1")
        val goto2 = FlowDirectiveGoto("task1")

        // When/Then
        assertEquals(goto1, goto2)
        assertEquals(goto1.hashCode(), goto2.hashCode())
    }

    @Test
    fun `FlowDirectiveGoto instances with different targets should not be equal`() {
        // Given
        val goto1 = FlowDirectiveGoto("task1")
        val goto2 = FlowDirectiveGoto("task2")

        // When/Then
        assertNotEquals(goto1, goto2)
    }

    @Test
    fun `Different FlowDirectiveEnum types should not be equal`() {
        // Given/When/Then
        assertNotEquals<FlowDirective>(FlowDirectiveEnum.Continue, FlowDirectiveEnum.Exit)
        assertNotEquals<FlowDirective>(FlowDirectiveEnum.Continue, FlowDirectiveEnum.End)
        assertNotEquals<FlowDirective>(FlowDirectiveEnum.Exit, FlowDirectiveEnum.End)
    }

    @Test
    fun `FlowDirectiveEnum and FlowDirectiveGoto should not be equal`() {
        // Given
        val enumDirective: FlowDirective = FlowDirectiveEnum.Continue
        val gotoDirective: FlowDirective = FlowDirectiveGoto("continue")

        // When/Then
        assertNotEquals(enumDirective, gotoDirective)
    }

    @Test
    fun `FlowDirectiveGoto copy should create new instance with different target`() {
        // Given
        val original = FlowDirectiveGoto("originalTask")

        // When
        val copied = original.copy(target = "newTask")

        // Then
        assertEquals("originalTask", original.target)
        assertEquals("newTask", copied.target)
        assertNotEquals(original, copied)
    }

    @Test
    fun `FlowDirectiveGoto toString should include target`() {
        // Given
        val directive = FlowDirectiveGoto("myTask")

        // When
        val string = directive.toString()

        // Then
        assertTrue(string.contains("myTask"))
    }
}
