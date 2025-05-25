// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.lemline.core.json.toJsonElement
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for ExpressionEvaluator.eval function, particularly focused on
 * string interpolation scenarios like 'echo "Hello, \(.user.name)"'
 */
class ExpressionEvaluatorTest {

    private fun evaluateExpression(input: String, filter: String, scope: String = "{}") =
        JQExpression.eval(
            input.toJsonElement(),
            JsonPrimitive(filter),
            scope.toJsonElement() as JsonObject,
            true
        )

    @Test
    fun `should handle simple string interpolation with property access`() {
        val input = """{"name": "World"}"""
        val filter = "\"Hello, \" + .name + \"!\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Hello, World!"), result)
    }

    @Test
    fun `should handle nested property access in string interpolation`() {
        val input = """{"user": {"name": "Alice", "age": 30}}"""
        val filter = "\"Hello, \\(.user.name)! You are \\(.user.age) years old.\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Hello, Alice! You are 30 years old."), result)
    }

    @Test
    fun `should handle multiple interpolations in echo command using concatenation`() {
        val input = """{"greeting": "Hello", "target": "World", "punctuation": "!"}"""
        val filter = "\"echo \\\"\" + .greeting + \", \" + .target + .punctuation + \"\\\"\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("echo \"Hello, World!\""), result)
    }

    @Test
    fun `should handle array access in string interpolation`() {
        val input = """{"items": ["first", "second", "third"]}"""
        val filter = "\"First item: \\(.items[0]), Last item: \\(.items[-1])\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("First item: first, Last item: third"), result)
    }

    @Test
    fun `should handle complex shell command with interpolation`() {
        val input = """{"filename": "data.txt", "directory": "/tmp", "pattern": "error"}"""
        val filter = "\"grep '\\(.pattern)' \\(.directory)/\\(.filename)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("grep 'error' /tmp/data.txt"), result)
    }

    @Test
    fun `should handle string interpolation with filters and functions`() {
        val input = """{"name": "john doe", "count": 5}"""
        val filter = "\"User: \\(.name), Count: \\(.count * 2)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("User: john doe, Count: 10"), result)
    }

    @Test
    fun `should handle string interpolation with conditional filters`() {
        val input = """{"status": "active", "name": "service"}"""
        val filter =
            "\"Service \" + .name + \" is \" + (if .status == \"active\" then \"running\" else \"stopped\" end)"

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Service service is running"), result)
    }

    @Test
    fun `should handle null values in interpolation`() {
        val input = """{"existing": "value", "null_field": null}"""
        val filter = "\"Existing: \\(.existing), Missing: \\(.missing), Null: \\(.null_field)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Existing: value, Missing: null, Null: null"), result)
    }

    @Test
    fun `should handle special characters in interpolated strings`() {
        val input =
            """{"message": "Hello \"quoted\" string with 'single quotes'", "path": "/path/with spaces/file.txt"}"""
        val filter = "\"echo '\\(.message)' > \\\"\\(.path)\\\"\""

        val result = evaluateExpression(input, filter)

        assertEquals(
            JsonPrimitive("echo 'Hello \"quoted\" string with 'single quotes'' > \"/path/with spaces/file.txt\""),
            result
        )
    }

    @Test
    fun `should handle mathematical operations in interpolation`() {
        val input = """{"a": 10, "b": 5}"""
        val filter = "\"Result: \\(.a + .b), Product: \\(.a * .b), Division: \\(.a / .b)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Result: 15, Product: 50, Division: 2"), result)
    }

    @Test
    fun `should handle string concatenation using plus operator`() {
        val input = """{"first": "Hello", "second": "World"}"""
        val filter = ".first + \" \" + .second + \"!\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Hello World!"), result)
    }

    @Test
    fun `should handle object property access with dynamic keys`() {
        val input =
            """{"config": {"production": "prod-value", "development": "dev-value"}, "environment": "production"}"""
        val filter = "\"Config value: \\(.config[.environment])\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Config value: prod-value"), result)
    }

    @Test
    fun `should handle array filtering and simple access`() {
        val input =
            """{"users": [{"name": "Alice", "active": true}, {"name": "Bob", "active": false}, {"name": "Charlie", "active": true}]}"""
        val filter = "\"First user: \\(.users[0].name), Active: \\(.users[0].active)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("First user: Alice, Active: true"), result)
    }

    @Test
    fun `should handle nested interpolations with scope variables`() {
        val input = """{"name": "Alice"}"""
        val scope = """{"prefix": "Dr."}"""
        val filter = "\"Greeting: Hello, \" + \$prefix + \" \" + .name + \"!\""

        val result = evaluateExpression(input, filter, scope)

        assertEquals(JsonPrimitive("Greeting: Hello, Dr. Alice!"), result)
    }

    @Test
    fun `should handle environment variable style interpolation`() {
        val input = """{"HOME": "/home/user", "USER": "alice", "PATH": "/usr/bin:/bin"}"""
        val filter = "\"export PATH=\\(.PATH):\\(.HOME)/bin && echo \\\"Welcome \\(.USER)\\\"\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("export PATH=/usr/bin:/bin:/home/user/bin && echo \"Welcome alice\""), result)
    }

    @Test
    fun `should handle interpolation with type conversion`() {
        val input = """{"number": 42, "boolean": true, "array": [1, 2, 3]}"""
        val filter = "\"Number: \\(.number), Boolean: \\(.boolean), First array item: \\(.array[0])\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("Number: 42, Boolean: true, First array item: 1"), result)
    }

    @Test
    fun `should handle error cases gracefully with invalid interpolation`() {
        val input = """{"valid": "value"}"""

        try {
            val filter = "\"Invalid: \\(.valid | invalid_function)\""
            evaluateExpression(input, filter)
            assert(false) { "Expected exception for invalid JQ function" }
        } catch (e: IllegalArgumentException) {
            assert(e.message?.contains("Unable to evaluate") == true)
        }
    }

    @Test
    fun `should handle multiple nested object access`() {
        val input =
            """{"app": {"database": {"connection": {"host": "localhost", "port": 5432, "database": "myapp"}}}}"""
        val filter =
            "\"postgresql://\\(.app.database.connection.host):\\(.app.database.connection.port)/\\(.app.database.connection.database)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("postgresql://localhost:5432/myapp"), result)
    }

    @Test
    fun `should handle string interpolation in complex command with escaping`() {
        val input = """{"search_term": "error message", "log_file": "/var/log/app.log"}"""
        val filter = "\"grep -n '\\(.search_term)' '\\(.log_file)' | head -10\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("grep -n 'error message' '/var/log/app.log' | head -10"), result)
    }

    @Test
    fun `should handle serverless workflow filter syntax with dollar prefix`() {
        val input = """{"name": "World"}"""
        val filter = "\${.name}"

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("World"), result)
    }

    @Test
    fun `should handle complex shell command construction`() {
        val input = """{"source_dir": "/source", "target_dir": "/target", "file_pattern": "*.log"}"""
        val filter = "\"find '\\(.source_dir)' -name '\\(.file_pattern)' -exec cp {} '\\(.target_dir)/{}' \\\\;\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("find '/source' -name '*.log' -exec cp {} '/target/{}' \\;"), result)
    }

    @Test
    fun `should handle conditional string building`() {
        val input = """{"debug": true, "command": "ls"}"""
        val filter = ".command + (if .debug then \" -la\" else \"\" end)"

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("ls -la"), result)
    }

    @Test
    fun `should handle array access operations for command arguments`() {
        val input = """{"args": ["-l", "-a", "--color=auto"]}"""
        val filter = "\"ls \" + .args[0] + \" \" + .args[1] + \" \" + .args[2]"

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("ls -l -a --color=auto"), result)
    }

    @Test
    fun `should handle URL construction with interpolation`() {
        val input =
            """{"protocol": "https", "host": "api.example.com", "port": 443, "path": "/v1/users", "id": 123}"""
        val filter = "\"\\(.protocol)://\\(.host):\\(.port)\\(.path)/\\(.id)\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("https://api.example.com:443/v1/users/123"), result)
    }

    @Test
    fun `should handle JSON-like string construction`() {
        val input = """{"name": "test", "value": 42, "enabled": true}"""
        val filter = "\"{\\\"name\\\":\\\"\\(.name)\\\",\\\"value\\\":\\(.value),\\\"enabled\\\":\\(.enabled)}\""

        val result = evaluateExpression(input, filter)

        assertEquals(JsonPrimitive("{\"name\":\"test\",\"value\":42,\"enabled\":true}"), result)
    }
}
