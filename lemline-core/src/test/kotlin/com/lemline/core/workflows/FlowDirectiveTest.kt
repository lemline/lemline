// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FlowDirectiveTest {

    // ========================================
    // Serialization/Deserialization Tests
    // ========================================

    @Test
    fun `should serialize Continue to JSON`() {
        // Given
        val directive: FlowDirective = FlowDirectiveEnum.Continue

        // When
        val serialized = LemlineJson.encodeToString(directive)

        // Then
        val expected = """{"type":"continue"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `should deserialize Continue from JSON`() {
        // Given
        val json = """{"type":"continue"}"""

        // When
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(json)

        // Then
        assertEquals(FlowDirectiveEnum.Continue, deserialized)
        assertIs<FlowDirectiveEnum.Continue>(deserialized)
    }

    @Test
    fun `should serialize Exit to JSON`() {
        // Given
        val directive: FlowDirective = FlowDirectiveEnum.Exit

        // When
        val serialized = LemlineJson.encodeToString(directive)

        // Then
        val expected = """{"type":"exit"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `should deserialize Exit from JSON`() {
        // Given
        val json = """{"type":"exit"}"""

        // When
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(json)

        // Then
        assertEquals(FlowDirectiveEnum.Exit, deserialized)
        assertIs<FlowDirectiveEnum.Exit>(deserialized)
    }

    @Test
    fun `should serialize End to JSON`() {
        // Given
        val directive: FlowDirective = FlowDirectiveEnum.End

        // When
        val serialized = LemlineJson.encodeToString(directive)

        // Then
        val expected = """{"type":"end"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `should deserialize End from JSON`() {
        // Given
        val json = """{"type":"end"}"""

        // When
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(json)

        // Then
        assertEquals(FlowDirectiveEnum.End, deserialized)
        assertIs<FlowDirectiveEnum.End>(deserialized)
    }

    @Test
    fun `should serialize FlowDirectiveGoto to JSON`() {
        // Given
        val directive: FlowDirective = FlowDirectiveGoto("targetTask")

        // When
        val serialized = LemlineJson.encodeToString(directive)

        // Then
        val expected = """{"type":"goto","target":"targetTask"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `should deserialize FlowDirectiveGoto from JSON`() {
        // Given
        val json = """{"type":"goto","target":"targetTask"}"""

        // When
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(json)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals("targetTask", deserialized.target)
    }

    @Test
    fun `should handle FlowDirectiveGoto with empty target`() {
        // Given
        val directive: FlowDirective = FlowDirectiveGoto("")

        // When
        val serialized = LemlineJson.encodeToString(directive)
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals("", deserialized.target)
    }

    @Test
    fun `should handle FlowDirectiveGoto with special characters in target`() {
        // Given
        val specialTarget = "task-with_special.chars:123"
        val directive: FlowDirective = FlowDirectiveGoto(specialTarget)

        // When
        val serialized = LemlineJson.encodeToString(directive)
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals(specialTarget, deserialized.target)
    }

    @Test
    fun `should round-trip serialize and deserialize all FlowDirective types`() {
        // Given
        val directives = listOf(
            FlowDirectiveEnum.Continue,
            FlowDirectiveEnum.Exit,
            FlowDirectiveEnum.End,
            FlowDirectiveGoto("someTask"),
            FlowDirectiveGoto("another-task"),
        )

        // When/Then
        directives.forEach { directive ->
            val serialized = LemlineJson.encodeToString(directive)
            val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)
            assertEquals(directive, deserialized)
        }
    }

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

    // ========================================
    // Type Hierarchy and Equality Tests
    // ========================================

    @Test
    fun `FlowDirectiveEnum types should be instances of FlowDirective`() {
        // Given/When/Then
        assertTrue(FlowDirectiveEnum.Continue is FlowDirective)
        assertTrue(FlowDirectiveEnum.Exit is FlowDirective)
        assertTrue(FlowDirectiveEnum.End is FlowDirective)
    }

    @Test
    fun `FlowDirectiveGoto should be instance of FlowDirective`() {
        // Given
        val goto = FlowDirectiveGoto("task")

        // When/Then
        assertTrue(goto is FlowDirective)
    }

    @Test
    fun `FlowDirectiveEnum Continue should be singleton`() {
        // Given/When
        val continue1 = FlowDirectiveEnum.Continue
        val continue2 = FlowDirectiveEnum.Continue

        // Then
        assertTrue(continue1 === continue2)
    }

    @Test
    fun `FlowDirectiveEnum Exit should be singleton`() {
        // Given/When
        val exit1 = FlowDirectiveEnum.Exit
        val exit2 = FlowDirectiveEnum.Exit

        // Then
        assertTrue(exit1 === exit2)
    }

    @Test
    fun `FlowDirectiveEnum End should be singleton`() {
        // Given/When
        val end1 = FlowDirectiveEnum.End
        val end2 = FlowDirectiveEnum.End

        // Then
        assertTrue(end1 === end2)
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

    // ========================================
    // Edge Cases and Special Scenarios
    // ========================================

    @Test
    fun `should handle FlowDirectiveGoto with whitespace in target`() {
        // Given
        val target = "  task with spaces  "
        val directive: FlowDirective = FlowDirectiveGoto(target)

        // When
        val serialized = LemlineJson.encodeToString(directive)
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals(target, deserialized.target)
    }

    @Test
    fun `should handle FlowDirectiveGoto with unicode characters`() {
        // Given
        val target = "タスク-𝕦𝕟𝕚𝕔𝕠𝕕𝕖"
        val directive: FlowDirective = FlowDirectiveGoto(target)

        // When
        val serialized = LemlineJson.encodeToString(directive)
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals(target, deserialized.target)
    }

    @Test
    fun `should handle FlowDirectiveGoto with very long target`() {
        // Given
        val target = "a".repeat(1000)
        val directive: FlowDirective = FlowDirectiveGoto(target)

        // When
        val serialized = LemlineJson.encodeToString(directive)
        val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

        // Then
        assertIs<FlowDirectiveGoto>(deserialized)
        assertEquals(target, deserialized.target)
    }

    @Test
    fun `should preserve FlowDirectiveGoto target exactly as provided`() {
        // Given - Various edge case targets
        val targets = listOf(
            "task",
            "task-name",
            "task_name",
            "task.name",
            "task:name",
            "123",
            "UPPERCASE",
            "camelCase",
            "snake_case",
            "kebab-case",
            "dot.notation",
        )

        // When/Then
        targets.forEach { target ->
            val directive: FlowDirective = FlowDirectiveGoto(target)
            val serialized = LemlineJson.encodeToString(directive)
            val deserialized = LemlineJson.decodeFromString<FlowDirective>(serialized)

            assertIs<FlowDirectiveGoto>(deserialized)
            assertEquals(target, deserialized.target, "Target should be preserved: $target")
        }
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
