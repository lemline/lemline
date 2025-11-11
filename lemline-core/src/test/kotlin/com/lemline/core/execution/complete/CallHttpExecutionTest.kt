// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.complete

import com.lemline.core.getWorkflowNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Integration tests for HTTP call execution using CompleteOrchestrator.
 *
 * Tests HTTP calls to real external services (JSONPlaceholder API) to verify:
 * - Basic HTTP method support (GET, POST, PUT, DELETE)
 * - Query parameter handling
 * - Request body handling
 * - Header support
 * - Response parsing
 */
@ExperimentalTime
class
CallHttpExecutionTest {

    @Test
    fun `http call can perform GET request`() = runTest {
        val yaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // JSONPlaceholder returns a post object with id, title, body, userId
        assertEquals(1, output["id"]?.jsonPrimitive?.int)
        assertTrue(output.containsKey("title"))
        assertTrue(output.containsKey("body"))
        assertTrue(output.containsKey("userId"))
    }

    @Test
    fun `http call can perform GET request with query parameters`() = runTest {
        val yaml = """
            do:
              - getComments:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/comments
                    query:
                      postId: 1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonArray

        // Should return an array of comments for post 1
        assertTrue(output.size > 0)
        val firstComment = output[0].jsonObject
        assertEquals(1, firstComment["postId"]?.jsonPrimitive?.int)
    }

    @Test
    fun `http call can perform POST request with body`() = runTest {
        val yaml = """
            do:
              - createPost:
                  call: http
                  with:
                    method: POST
                    endpoint: https://jsonplaceholder.typicode.com/posts
                    headers:
                      Content-Type: application/json
                    body:
                      title: Test Post
                      body: This is a test post
                      userId: 1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // JSONPlaceholder mock API returns the created post with an id
        assertTrue(output.containsKey("id"))
        assertEquals("Test Post", output["title"]?.jsonPrimitive?.content)
        assertEquals("This is a test post", output["body"]?.jsonPrimitive?.content)
        assertEquals(1, output["userId"]?.jsonPrimitive?.int)
    }

    @Test
    fun `http call can perform PUT request`() = runTest {
        val yaml = """
            do:
              - updatePost:
                  call: http
                  with:
                    method: PUT
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                    body:
                      id: 1
                      title: Updated Title
                      body: Updated body
                      userId: 1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        assertEquals(1, output["id"]?.jsonPrimitive?.int)
        assertEquals("Updated Title", output["title"]?.jsonPrimitive?.content)
        assertEquals("Updated body", output["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `http call can perform DELETE request`() = runTest {
        val yaml = """
            do:
              - deletePost:
                  call: http
                  with:
                    method: DELETE
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap()))

        // JSONPlaceholder returns empty object for DELETE
        assertTrue(output is JsonObject)
    }

    @Test
    fun `http call can use input data via input transformation`() = runTest {
        val yaml = $$"""
            do:
              - createPost:
                  call: http
                  with:
                    method: POST
                    endpoint: https://jsonplaceholder.typicode.com/posts
                    body:
                      title: Dynamic Title
                      body: Dynamic body content
                      userId: 42
                  input:
                    from: ${ . }
        """
        val input = JsonObject(
            mapOf(
                "someData" to JsonPrimitive("test")
            )
        )

        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, input) as JsonObject

        assertTrue(output.containsKey("id"))
        assertEquals("Dynamic Title", output["title"]?.jsonPrimitive?.content)
        assertEquals("Dynamic body content", output["body"]?.jsonPrimitive?.content)
        assertEquals(42, output["userId"]?.jsonPrimitive?.int)
    }

    @Test
    fun `http call can chain multiple requests`() = runTest {
        val yaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
              - getUser:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/users/1
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Second call should get user data
        assertTrue(output.containsKey("id"))
        assertTrue(output.containsKey("name"))
        assertTrue(output.containsKey("email"))
    }

    @Test
    fun `http call can use output as response format`() = runTest {
        val yaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                    output: response
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Response format includes request, statusCode, headers, and content
        assertTrue(output.containsKey("request"))
        assertTrue(output.containsKey("statusCode"))
        assertTrue(output.containsKey("headers"))
        assertTrue(output.containsKey("content"))

        val request = output["request"]?.jsonObject
        assertEquals("GET", request?.get("method")?.jsonPrimitive?.content)

        assertEquals(200, output["statusCode"]?.jsonPrimitive?.int)

        val content = output["content"]?.jsonObject
        assertEquals(1, content?.get("id")?.jsonPrimitive?.int)
    }

    @Test
    fun `http call can use custom headers`() = runTest {
        val yaml = """
            do:
              - getWithHeaders:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                    headers:
                      Accept: application/json
                      User-Agent: Lemline-Test
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should successfully get the post
        assertEquals(1, output["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `http call result can be transformed with output as`() = runTest {
        val yaml = $$"""
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                  output:
                    as: '${ {postTitle: .title, postId: .id} }'
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Only the transformed fields should be in output
        assertTrue(output.containsKey("postTitle"))
        assertTrue(output.containsKey("postId"))
        assertEquals(1, output["postId"]?.jsonPrimitive?.int)
    }

    @Test
    fun `http call can be used within workflow steps`() = runTest {
        val yaml = $$"""
            do:
              - step1:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
              - step2:
                  set:
                    result: ${ .title }
        """
        val rootNode = getWorkflowNode(yaml)
        val output = CompleteOrchestrator.run(rootNode, JsonObject(emptyMap())) as JsonObject

        // Should have the title from the first HTTP call
        assertTrue(output.containsKey("result"))
        assertTrue(output["result"]?.jsonPrimitive?.content?.isNotEmpty() == true)
    }
}
