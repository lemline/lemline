package com.lemline.core.expressions

import com.lemline.core.RuntimeDescriptor
import com.lemline.core.expressions.scopes.Scope
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.json.LemlineJson
import com.lemline.core.loadWorkflowFromYaml
import com.lemline.core.set
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.*

class ScopeTest : StringSpec({

    val workflowPath = "/examples/do-single.yaml"
    val workflow = loadWorkflowFromYaml(workflowPath)
    val testInput: JsonElement = JsonObject(mapOf("test" to JsonPrimitive("value")))
    val testOutput: JsonElement = JsonObject(mapOf("result" to JsonPrimitive(true)))
    val testDefinition = JsonObject(mapOf("type" to JsonPrimitive("set")))
    val testStartedAt = DateTimeDescriptor.from(Instant.now())
    val testWorkflowDescriptor = WorkflowDescriptor(
        id = UUID.randomUUID().toString(),
        definition = LemlineJson.encodeToElement(workflow),
        input = testInput,
        startedAt = LemlineJson.encodeToElement(testStartedAt)
    )
    val testTaskDescriptor = TaskDescriptor(
        name = "Test Task",
        reference = "/do/0/testTask",
        definition = testDefinition,
        input = testInput,
        output = testOutput,
        startedAt = LemlineJson.encodeToElement(testStartedAt)
    )
    val testContext = LemlineJson.jsonObject.set("custom", "data")
    val testSecrets = mapOf("secretKey" to JsonPrimitive("secretValue"))

    "should serialize and deserialize Workflow" {

        val jsonString = LemlineJson.encodeToElement(workflow).toString()
        val deserializedWorkflow = LemlineJson.jacksonMapper.readValue(jsonString, Workflow::class.java)

        deserializedWorkflow.document.dsl shouldBe workflow.document.dsl
        deserializedWorkflow.document.name shouldBe workflow.document.name
        deserializedWorkflow.document.version shouldBe workflow.document.version
    }

    "should serialize and deserialize RunTimeDescriptor" {

        val jsonString = LemlineJson.encodeToString(RuntimeDescriptor)
        val deserializedRuntimeDescriptor = LemlineJson.decodeFromString<RuntimeDescriptor>(jsonString)

        deserializedRuntimeDescriptor shouldBe RuntimeDescriptor
    }

    "should serialize and deserialize WorkflowDescriptor" {

        val jsonString = LemlineJson.encodeToString(testWorkflowDescriptor)
        val deserializedDescriptor = LemlineJson.decodeFromString<WorkflowDescriptor>(jsonString)

        deserializedDescriptor shouldBe testWorkflowDescriptor
    }

    "should serialize and deserialize TaskDescriptor" {

        val jsonString = LemlineJson.encodeToString(testTaskDescriptor)
        val deserializedDescriptor = LemlineJson.decodeFromString<TaskDescriptor>(jsonString)

        deserializedDescriptor shouldBe testTaskDescriptor
    }

    "should serialize and deserialize Scope" {
        val originalScope = Scope(
            context = testContext,
            input = testInput,
            output = testOutput,
            secrets = testSecrets,
            task = testTaskDescriptor,
            workflow = testWorkflowDescriptor,
            runtime = RuntimeDescriptor
        )

        val jsonString = originalScope.toJsonObject().toString()
        val deserializedScope = LemlineJson.decodeFromString<Scope>(jsonString)

        // Compare individual components retrieved from the map
        deserializedScope shouldBe originalScope
    }
}) 