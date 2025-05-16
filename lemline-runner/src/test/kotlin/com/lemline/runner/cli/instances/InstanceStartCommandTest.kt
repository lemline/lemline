package com.lemline.runner.cli.instances

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.runner.cli.common.InteractiveWorkflowSelector
import com.lemline.runner.messaging.Message
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Field
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import picocli.CommandLine

class InstanceStartCommandTest {

    private lateinit var command: InstanceStartCommand
    private lateinit var definitionRepository: DefinitionRepository
    private lateinit var selector: InteractiveWorkflowSelector
    private lateinit var emitter: Emitter<String>

    private lateinit var workflowName: String
    private lateinit var workflowVersion: String
    private lateinit var workflowDefinition: DefinitionModel
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private val messageSlot = slot<String>()

    @BeforeEach
    fun setup() {
        // Create mocks
        definitionRepository = mockk()
        selector = mockk()
        emitter = mockk()

        // Create command and inject mocks
        command = InstanceStartCommand()
        injectField(command, "definitionRepository", definitionRepository)
        injectField(command, "selector", selector)
        injectField(command, "emitter", emitter)

        workflowName = "testWorkflow"
        workflowVersion = "1.0.0"
        workflowDefinition = DefinitionModel(
            name = workflowName,
            version = workflowVersion,
            definition = """
                document:
                  dsl: 1.0.0
                  namespace: test
                  name: $workflowName
                  version: '$workflowVersion'
                do: 
                  - wait30Seconds:
                      wait: PT30S
            """.trimIndent()
        )

        every { definitionRepository.findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
        every { definitionRepository.listByName(workflowName) } returns listOf(workflowDefinition)
        every { emitter.send(any<String>()) } returns CompletableFuture.completedFuture(null)

        // Save original streams
        originalOut = System.out
        originalErr = System.err

        // Set up capture streams
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))

        cmd = CommandLine(command)
    }

    @AfterEach
    fun cleanup() {
        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    /**
     * Helper method to execute command and verify basic success conditions
     * Returns the captured Message for further assertions
     */
    private fun executeCommandAndVerify(vararg args: String): Message {
        // When
        val exitCode = cmd.execute(*args)

        // Then
        if (exitCode != 0) {
            println("Command failed with exit code $exitCode")
            println("Error output: $errStream")
            println("Standard output: $outStream")
        }
        exitCode shouldBe 0
        outStream.toString() shouldContain "Workflow instance started successfully."

        verify { emitter.send(capture(messageSlot)) }

        val sentMessage = LemlineJson.decodeFromString<Message>(messageSlot.captured)
        sentMessage.name shouldBe workflowName
        sentMessage.version shouldBe workflowVersion

        return sentMessage
    }

    @Nested
    inner class JsonObjectTests {
        @Test
        fun `should properly parse valid JSON object input`() {
            // Given
            val inputJsonString = """{"key": "value", "number": 123}"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            // Build the expected JSON object explicitly for clarity
            val expectedJson = buildJsonObject {
                put("key", "value")
                put("number", 123)
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }

        @Test
        fun `should properly parse nested JSON object input`() {
            // Given
            val inputJsonString = """{"outer": {"inner": "value", "number": 42}}"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            // Build the expected nested JSON object
            val expectedJson = buildJsonObject {
                put("outer", buildJsonObject {
                    put("inner", "value")
                    put("number", 42)
                })
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }

        @Test
        fun `should properly parse JSON with single quotes`() {
            // Given - Note: The outer quotes are needed for the CLI arg, but won't be part of the JSON
            val inputJsonString = """{'key': 'value', 'number': 123}"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            // When using single quotes in JSON, they become part of the keys and string values
            val expectedJson = buildJsonObject {
                put("'key'", "'value'")
                put("'number'", 123)
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }

        @Test
        fun `should properly parse JSON with no quotes`() {
            // Given - Note: The outer quotes are needed for the CLI arg, but won't be part of the JSON
            val inputJsonString = """{key: value, number: 123}"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            // When using single quotes in JSON, they become part of the keys and string values
            val expectedJson = buildJsonObject {
                put("key", "value")
                put("number", 123)
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }
    }

    @Nested
    inner class JsonArrayTests {
        @Test
        fun `should properly parse JSON array input`() {
            // Given
            val inputJsonString = """[1, 2, 3, "four"]"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            val expectedJson = buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(2))
                add(JsonPrimitive(3))
                add(JsonPrimitive("four"))
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }

        @Test
        fun `should properly parse nested array in object input`() {
            // Given
            val inputJsonString = """{"items": [1, 2, {"name": "three"}]}"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            val expectedJson = buildJsonObject {
                put("items", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(2))
                    addJsonObject {
                        put("name", "three")
                    }
                })
            }

            sentMessage.states[NodePosition.root]?.rawInput shouldBe expectedJson
        }
    }

    @Nested
    inner class JsonPrimitiveTests {
        @Test
        fun `should properly parse string primitive input`() {
            // Given
            val inputJsonString = """"just a string""""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive("just a string")
        }

        @Test
        fun `should properly parse number primitive input`() {
            // Given
            val inputJsonString = """42"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive(42)
        }

        @Test
        fun `should properly parse number within single quote`() {
            // Given
            val inputJsonString = """'42'"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive("'42'")
        }

        @Test
        fun `should properly parse number within double quote`() {
            // Given
            val inputJsonString = """"42""""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive("42")
        }

        @Test
        fun `should properly parse boolean primitive input`() {
            // Given
            val inputJsonString = """true"""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive(true)
        }

        @Test
        fun `should properly parse boolean within double quote`() {
            // Given
            val inputJsonString = """"true""""

            // When & Then
            val sentMessage = executeCommandAndVerify(workflowName, workflowVersion, "--input", inputJsonString)

            sentMessage.states[NodePosition.root]?.rawInput shouldBe JsonPrimitive("true")
        }
    }

    @Test
    fun `should use empty JSON object when no input is provided`() {
        // When & Then
        val sentMessage = executeCommandAndVerify(workflowName, workflowVersion)

        // Get the raw input as a JsonElement
        val rawInput = sentMessage.states[NodePosition.root]?.rawInput

        // When no input is provided, the command should use an empty JSON object
        rawInput shouldBe JsonObject(emptyMap())
    }

    // Helper method to inject dependencies using reflection
    private fun injectField(target: Any, fieldName: String, value: Any) {
        val field = findField(target.javaClass, fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    // Helper method to find a field in a class or its superclasses
    private fun findField(clazz: Class<*>, fieldName: String): Field {
        try {
            return clazz.getDeclaredField(fieldName)
        } catch (e: NoSuchFieldException) {
            val superClass = clazz.superclass
            if (superClass != null) {
                return findField(superClass, fieldName)
            }
            throw e
        }
    }
}
