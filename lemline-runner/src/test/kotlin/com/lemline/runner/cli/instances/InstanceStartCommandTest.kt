// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.WorkflowState
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.setup
import com.lemline.runner.starters.Starter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Field
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

@ExperimentalTime
@ExperimentalSerializationApi
class InstanceStartCommandTest {

    private lateinit var command: InstanceStartCommand
    private lateinit var definitions: Definitions
    private lateinit var definitionRepository: DefinitionRepository
    private lateinit var instanceEmitter: InstanceMessageEmitter
    private lateinit var databaseEmitter: DatabaseMessageEmitter

    private var workflowNamespace = WorkflowNamespace("test")
    private var workflowName = WorkflowName("testWorkflow")
    private var workflowNameWithSchema = WorkflowName("testWorkflowWithSchema")
    private var workflowVersion = WorkflowVersion("1.0.0")
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var messageSlot: CapturingSlot<InstanceMessage>

    private val workflowDefinition = DefinitionModel(
        namespace = workflowNamespace,
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

    private val workflowWithSchema = DefinitionModel(
        namespace = workflowNamespace,
        name = workflowNameWithSchema,
        version = workflowVersion,
        definition = """
                    document:
                      dsl: 1.0.0
                      namespace: test
                      name: $workflowNameWithSchema
                      version: '$workflowVersion'
                    input:
                      schema:
                        format: json
                        document:
                          type: object
                          properties:
                            userId:
                              type: string
                            firstName:
                              type: string
                            lastName:
                              type: string
                          required: [ userId, lastName ]
                    do:
                      - wait30Seconds:
                          wait: PT30S
                """.trimIndent()
    )

    init {
        DefinitionCache.clear()
    }

    @BeforeEach
    fun setup() {
        messageSlot = slot<InstanceMessage>()
        // Emitter mocks
        instanceEmitter = mockk(relaxUnitFun = true)
        databaseEmitter = mockk(relaxUnitFun = true)

        // Definition repository mock
        definitionRepository = mockk()

        // Configure a repository to return this workflow
        coEvery {
            definitionRepository.findByNameAndVersion(workflowNamespace, workflowName, workflowVersion)
        } returns workflowDefinition
        coEvery {
            definitionRepository.findByNameAndVersion(workflowNamespace, workflowNameWithSchema, workflowVersion)
        } returns workflowWithSchema
        coEvery { definitionRepository.listByName(workflowNamespace, workflowName) } returns listOf(workflowDefinition)
        coEvery { definitionRepository.listByName(workflowNamespace, workflowNameWithSchema) } returns listOf(
            workflowWithSchema
        )

        definitions = Definitions()
        definitions.inject("definitionRepository", definitionRepository)

        // Create command and inject mocks
        val starter = Starter()
        starter.inject("definitions", definitions)

        command = InstanceStartCommand()
        command.inject("starter", starter)
        command.inject("instanceEmitter", instanceEmitter)
        command.inject("databaseEmitter", databaseEmitter)

        // Save original streams
        originalOut = System.out
        originalErr = System.err

        // Set up capture streams
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))

        cmd = CommandLine(command).setup()
    }

    @AfterEach
    fun cleanup() {
        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)

        clearAllMocks()
    }

    /**
     * Helper method to execute command and verify basic success conditions
     * Returns the captured Message for further assertions
     */
    private fun executeCommandAndVerify(vararg args: String): InstanceMessage {
        // When
        val exitCode = cmd.execute(*args)

        // Then
        if (exitCode != 0) {
            println("Command failed with exit code $exitCode")
            println("Error output: $errStream")
            println("Standard output: $outStream")
        }
        exitCode shouldBe 0
        outStream.toString() shouldContain "started successfully"

        coVerify { instanceEmitter.send(capture(messageSlot)) }

        val sentInstanceMessage = messageSlot.captured
        sentInstanceMessage.workflowName shouldBe workflowName
        sentInstanceMessage.workflowVersion shouldBe workflowVersion

        return sentInstanceMessage
    }

    @Test
    fun `should properly parse valid JSON object input`() {
        // Given
        val inputJsonString = """{"key": "value", "number": 123}"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        // Build the expected JSON object explicitly for clarity
        val expectedJson = buildJsonObject {
            put("key", "value")
            put("number", 123)
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }

    @Test
    fun `should properly parse nested JSON object input`() {
        // Given
        val inputJsonString = """{"outer": {"inner": "value", "number": 42}}"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        // Build the expected nested JSON object
        val expectedJson = buildJsonObject {
            put("outer", buildJsonObject {
                put("inner", "value")
                put("number", 42)
            })
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }

    @Test
    fun `should properly parse JSON with single quotes`() {
        // Given - Note: The outer quotes are needed for the CLI arg, but won't be part of the JSON
        val inputJsonString = """{'key': 'value', 'number': 123}"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        // Single quotes are normalized by the parser; expect standard JSON keys/values
        val expectedJson = buildJsonObject {
            put("key", "value")
            put("number", 123)
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }

    @Test
    fun `should properly parse JSON with no quotes`() {
        // This input is no longer supported without quotes; we normalize by requiring proper JSON instead
        val inputJsonString = """{"key": "value", "number": 123}"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        val expectedJson = buildJsonObject {
            put("key", "value")
            put("number", 123)
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }

    @Test
    fun `should properly parse JSON array input`() {
        // Given
        val inputJsonString = """[1, 2, 3, "four"]"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        val expectedJson = buildJsonArray {
            add(JsonPrimitive(1))
            add(JsonPrimitive(2))
            add(JsonPrimitive(3))
            add(JsonPrimitive("four"))
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }

    @Test
    fun `should properly parse nested array in object input`() {
        // Given
        val inputJsonString = """{"items": [1, 2, {"name": "three"}]}"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        val expectedJson = buildJsonObject {
            put("items", buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(2))
                addJsonObject {
                    put("name", "three")
                }
            })
        }

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe expectedJson
    }


    @Test
    fun `should use empty JSON object when no input is provided`() {
        // When & Then
        val sentMessage =
            executeCommandAndVerify(workflowNamespace.toString(), workflowName.toString(), workflowVersion.toString())

        // Get the raw input as a JsonElement
        val rawInput = (sentMessage.workflowState as WorkflowState.Starting).input

        // When no input is provided, the command should use an empty JSON object
        rawInput shouldBe JsonObject(emptyMap())
    }

    @Test
    fun `should properly parse string primitive input`() {
        // Given
        val inputJsonString = """"just a string""""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive("just a string")
    }

    @Test
    fun `should properly parse number primitive input`() {
        // Given
        val inputJsonString = """42"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive(42)
    }

    @Test
    fun `should properly parse number within single quote`() {
        // Given
        val inputJsonString = """'42'"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive("42")
    }

    @Test
    fun `should properly parse number within double quote`() {
        // Given
        val inputJsonString = """"42""""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive("42")
    }

    @Test
    fun `should properly parse boolean primitive input`() {
        // Given
        val inputJsonString = """true"""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive(true)
    }

    @Test
    fun `should properly parse boolean within double quote`() {
        // Given
        val inputJsonString = """"true""""

        // When & Then
        val sentMessage =
            executeCommandAndVerify(
                workflowNamespace.toString(),
                workflowName.toString(),
                workflowVersion.toString(),
                "--input",
                inputJsonString
            )

        (sentMessage.workflowState as WorkflowState.Starting).input shouldBe JsonPrimitive("true")
    }

    @Test
    fun `should validate input against schema when schema exists`() {
        // Execute command with valid input matching the schema
        val validInput = """{"userId": "user123", "firstName": "john", "lastName": "doe"}"""
        val exitCode = cmd.execute(
            workflowNamespace.toString(),
            workflowNameWithSchema.toString(),
            workflowVersion.toString(),
            "--input",
            validInput
        )

        // Verify command was successful
        exitCode shouldBe 0
    }

    @Test
    fun `should validate input against schema when schema exists (only required)`() {
        // Execute command with valid input matching the schema
        val validInput = """{"userId": "user123", "lastName": "doe"}"""
        val exitCode = cmd.execute(
            workflowNamespace.toString(),
            workflowNameWithSchema.toString(),
            workflowVersion.toString(),
            "--input",
            validInput
        )

        // Verify command was successful
        exitCode shouldBe 0
    }

    @Test
    fun `should fail when input validation fails`() {
        // Create an error slot to capture error messages
        val errorSlot = slot<String>()

        // Create a spy of the command that intercepts calls to error()
        val spyCommand = spyk(command) {
            every { cliError(capture(errorSlot)) } answers {
                throw RuntimeException("Error: ${errorSlot.captured}")
            }
        }

        // Create a new CommandLine with the spy
        val spyCmd = CommandLine(spyCommand).setup()

        // Execute command with invalid input (missing required lastName field)
        val invalidInput = """{"userId": "user123"}"""
        val code =
            spyCmd.execute(
                workflowNamespace.toString(),
                workflowNameWithSchema.toString(),
                workflowVersion.toString(),
                "--input",
                invalidInput
            )

        println("code = $code")
        // Verify the error message was captured
        errorSlot.captured shouldContain "Input validation failed against workflow schema"
        errorSlot.captured shouldContain "'lastName'"

        // Verify emitter was NOT called (we failed before sending the message)
        coVerify(exactly = 0) { instanceEmitter.send(any<InstanceMessage>()) }
    }

    // Helper method to inject dependencies using reflection
    private fun Any.inject(fieldName: String, value: Any) {
        val field = findField(javaClass, fieldName)
        field.isAccessible = true
        field.set(this, value)
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
