// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.mock

import com.lemline.common.json.jsonValue
import com.lemline.common.json.parseJson
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.getWorkflowToTest
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MockConfigurationParserTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load and execute HTTP mocks with YAML response bodies`() = runTest {
        val file = tempDir.resolve("mocks.yaml")
        Files.writeString(
            file, """
                http:
                  - match: {method: GET}
                    response:
                      headers: {retry-after: 120}
                      body:
                        sku: SKU-1
                        available: 0
                        details: {price: 2.75, labels: [true, null, "true", '2', on]}
                  - match: {method: POST}
                    response:
                      body: {created: true}
                  - match: {method: PUT}
                    response:
                      body: [7, false, "null"]
                  - match: {method: DELETE}
                    response:
                      body: "0"
            """.trimIndent()
        )

        val executor = MockActivityExecutor.fromFile(file.toString())
        val bodies = listOf(
            parseJson("""{"sku":"SKU-1","available":0,"details":{"price":2.75,"labels":[true,null,"true","2","on"]}}"""),
            parseJson("""{"created":true}"""),
            parseJson("""[7,false,"null"]"""),
            jsonValue("0"),
        )
        assertEquals(bodies, executor.mockConfig.httpMocks.map { it.response.body })
        assertEquals(mapOf("retry-after" to "120"), executor.mockConfig.httpMocks.first().response.headers)

        val workflow = getWorkflowToTest(
            """
            do:
              - getStock:
                  call: http
                  with:
                    method: get
                    endpoint: https://shop.example/stock
            """.trimIndent()
        )
        val output = FullOrchestrator.start(
            workflow = workflow,
            activityExecutor = executor,
            functionResolver = MockFunctionResolver(),
            cloudEventHook = InMemoryCloudEventHook(),
            lifecycleHook = LifecycleEventHook.NOOP,
        )
        assertEquals(bodies.first(), output)
    }

    @Test
    fun `load JSON HTTP mock body`() {
        val config = MockConfigurationParser.fromJson(
            """{"http":[{"match":{},"response":{"body":{"sku":"SKU-1","available":0}}}]}"""
        )

        assertEquals(parseJson("""{"sku":"SKU-1","available":0}"""), config.httpMocks.single().response.body)
    }
}
