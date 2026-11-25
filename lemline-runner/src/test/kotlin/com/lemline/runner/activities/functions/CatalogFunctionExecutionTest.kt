// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities.functions

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.parseJson
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.workflows.WorkflowCache
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogFunctionExecutionTest {
    private val resolver = FunctionResolver()

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `resolves the same catalog reference separately for each workflow`() = runBlocking {
        val first = fileCatalog("first catalog", "first")
        val second = fileCatalog("second catalog", "second")

        assertEquals(parseJson("""{"source":"first"}"""), execute("first-catalog", "'$first'"))
        assertEquals(parseJson("""{"source":"second"}"""), execute("second-catalog", "{uri: '$second'}"))
        assertEquals(parseJson("""{"source":"first"}"""), execute("first-catalog", "'$first'"))
    }

    @Test
    fun `downloads a function from the configured HTTP catalog root`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog/get-product/1.0.0/function.yaml") { exchange ->
            exchange.use {
                val body = "set: {source: http}".toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/catalog/"
            assertEquals(parseJson("""{"source":"http"}"""), execute("http-catalog", "'$endpoint'", "shopCatalog"))
        } finally {
            server.stop(0)
        }
    }

    private fun fileCatalog(directory: String, source: String): String {
        val root = tmp.resolve(directory)
        val definition = root.resolve("get-product/1.0.0/function.yaml")
        Files.createDirectories(definition.parent)
        Files.writeString(definition, "set: {source: $source}")
        return root.toUri().toString()
    }

    private suspend fun execute(name: String, endpoint: String, catalog: String = "shop-catalog"): JsonNode {
        val workflow = WorkflowCache.parseYamlAndPut(
            """
            document: {dsl: '1.0.3', namespace: test, name: $name, version: '1.0.0'}
            use:
              catalogs:
                $catalog:
                  endpoint: $endpoint
            do:
              - getProduct:
                  call: get-product:1.0.0@$catalog
            """.trimIndent()
        )
        return FullOrchestrator.start(
            workflow = workflow,
            activityExecutor = MockActivityExecutor(MockConfiguration.empty()),
            functionResolver = resolver,
            cloudEventHook = InMemoryCloudEventHook(),
            lifecycleHook = LifecycleEventHook.NOOP,
        )
    }
}
