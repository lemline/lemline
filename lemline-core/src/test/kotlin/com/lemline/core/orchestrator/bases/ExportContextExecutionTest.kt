// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator.bases

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base tests for export.as directive.
 *
 * The export.as directive allows tasks to export data to the workflow context ($context),
 * making it available to subsequent tasks.
 */
abstract class ExportContextExecutionTest : FunSpec() {

    init {
        test("export context using expression syntax") {
            val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as: "${ {ctx: .} }"
              - second:
                  set:
                    number: ${ @context.ctx.foo }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["number"]?.jsonPrimitive?.int shouldBe 42
        }

        test("export context using JSON object") {
            val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                  export:
                    as:
                      ctx: .
              - second:
                  set:
                    number: ${ @context.ctx.foo }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["number"]?.jsonPrimitive?.int shouldBe 42
        }

        test("export partial data to context") {
            val yaml = $$"""
            do:
              - first:
                  set:
                    foo: 42
                    bar: "hello"
                  export:
                    as:
                      onlyFoo: .foo
              - second:
                  set:
                    fromContext: ${ @context.onlyFoo }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["fromContext"]?.jsonPrimitive?.int shouldBe 42
        }

        test("multiple tasks can export to context") {
            val yaml = $$"""
            do:
              - first:
                  set:
                    value1: 10
                  export:
                    as:
                      first: .value1
              - second:
                  set:
                    value2: 20
                  export:
                    as:
                      first: ${ @context.first }
                      second: .value2
              - third:
                  set:
                    sum: ${ @context.first + @context.second }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["sum"]?.jsonPrimitive?.int shouldBe 30
        }

        test("export can overwrite previous context values") {
            val yaml = $$"""
            do:
              - first:
                  set:
                    value: 10
                  export:
                    as:
                      shared: .value
              - second:
                  set:
                    value: 20
                  export:
                    as:
                      shared: .value
              - third:
                  set:
                    result: ${ @context.shared }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["result"]?.jsonPrimitive?.int shouldBe 20
        }

        test("export works with nested tasks") {
            val yaml = $$"""
            do:
              - outer:
                  do:
                    - inner:
                        set:
                          nested: "value"
                        export:
                          as:
                            fromNested: .nested
              - useNested:
                  set:
                    result: ${ @context.fromNested }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["result"]?.jsonPrimitive?.content shouldBe "value"
        }

        test("export can transform data before exporting") {
            val yaml = $$"""
            do:
              - compute:
                  set:
                    x: 10
                    y: 20
                  export:
                    as:
                      sum: ${ .x + .y }
              - use:
                  set:
                    doubled: ${ @context.sum * 2 }
        """
            val output = executeWorkflow(yaml, JsonObject(mapOf())) as JsonObject
            output["doubled"]?.jsonPrimitive?.int shouldBe 60
        }
    }

    protected abstract suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0"
    ): JsonElement
}
