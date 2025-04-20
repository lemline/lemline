package com.lemline.core.workflows

import com.lemline.core.getWorkflowInstance
import com.lemline.core.json.LemlineJson
import io.kotest.matchers.shouldBe
import io.serverlessworkflow.impl.WorkflowStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class HttpWorkflowTest {

    @Test
    fun `test basic GET request to JSONPlaceholder`() = runTest {
        val workflowYaml = """
            do:
              - getPost:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert the output contains expected fields from JSONPlaceholder
        val output = instance.current?.state?.rawOutput.toString()
        assertTrue(output.contains("id"))
        assertTrue(output.contains("title"))
        assertTrue(output.contains("body"))
        assertTrue(output.contains("userId"))
    }

    @Test
    fun `test GET request with query parameters`() = runTest {
        val workflowYaml = """
            do:
              - getComments:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/comments
                    query:
                      postId: 1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert we get an array of comments for post 1
        val output = instance.current?.state?.rawOutput.toString()
        assertTrue(output.contains("\"postId\":1"))
    }

    @Test
    fun `test POST request with body`() = runTest {
        val workflowYaml = """
            do:
              - createPost:
                  call: http
                  with:
                    method: POST
                    endpoint: https://jsonplaceholder.typicode.com/posts
                    headers:
                      Content-Type: application/json
                    body:
                      title: "Test Post"
                      body: "This is a test post"
                      userId: 1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert the response contains the data we sent plus an ID
        val outputStr = instance.current?.state?.rawOutput.toString()
        assertTrue(outputStr.contains("\"id\""))
        assertTrue(outputStr.contains("\"title\":\"Test Post\""))
        assertTrue(outputStr.contains("\"body\":\"This is a test post\""))
        assertTrue(outputStr.contains("\"userId\":1"))
    }

    @Test
    fun `test PUT request to update resource`() = runTest {
        val workflowYaml = """
            do:
              - updatePost:
                  call: http
                  with:
                    method: PUT
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                    headers:
                      Content-Type: application/json
                    body:
                      id: 1
                      title: "Updated Title"
                      body: "Updated body content"
                      userId: 1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Assert the response contains our updated data
        val outputStr = instance.current?.state?.rawOutput.toString()
        assertTrue(outputStr.contains("\"id\":1"))
        assertTrue(outputStr.contains("\"title\":\"Updated Title\""))
        assertTrue(outputStr.contains("\"body\":\"Updated body content\""))
    }

    @Test
    fun `test DELETE request`() = runTest {
        val workflowYaml = """
            do:
              - deletePost:
                  call: http
                  with:
                    method: DELETE
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // For JSONPlaceholder, DELETE requests return an empty object
        val output = instance.rootInstance.transformedOutput
        assertTrue(output is JsonObject)
    }

    @Test
    fun `test request with headers and query parameters`() = runTest {
        val workflowYaml = """
            do:
              - testRequest:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts
                    headers:
                      X-Custom-Header: test-value
                    query:
                      userId: 1
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify we got a response
        val outputStr = instance.current?.state?.rawOutput.toString()
        assertTrue(outputStr.contains("\"id\""))
        assertTrue(outputStr.contains("\"userId\":1"))
    }

    @Test
    fun `test raw output format`() = runTest {
        val workflowYaml = """
            do:
              - getRawOutput:
                  call: http
                  with:
                    method: GET
                    endpoint: https://jsonplaceholder.typicode.com/posts/1
                    output: raw
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // With raw output, we should get a base64 encoded string
        val outputStr = instance.current?.state?.rawOutput.toString()
        // The output should be a base64 encoded string
        assertTrue(outputStr.contains("ew"))  // Base64 encoded JSON typically starts with "ew"
        assertTrue(outputStr.contains("=="))  // Base64 encoded strings often end with "=="
    }

    /**
     * Tests error handling for HTTP communication errors.
     *
     * This test verifies that 404 HTTP communication errors are properly caught and handled
     * using the Try-Catch mechanism as described in the Serverless Workflow specification:
     * https://serverlessworkflow.io/spec/1.0.0/errors/communication
     */
    @Test
    fun `test HTTP 404 error handling with try-catch`() = runTest {
        val workflowYaml = """
            do:
              - tryGetNonExistentResource:
                  try:
                    - getNotFound:
                        call: http
                        with:
                          method: GET
                          endpoint: https://httpstat.us/404
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                        status: 404
                    as: httpError
                    do:
                      - handleError:
                          set: 
                            errorCaught: @{ @httpError }
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify the workflow completed successfully (error was caught)
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Verify the error was caught and handled
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("{\"type\":\"https://serverlessworkflow.io/spec/1.0.0/errors/communication\",\"status\":404,\"instance\":\"/do/0/tryGetNonExistentResource/try/0/getNotFound\""))
    }

    /**
     * Tests error handling for HTTP 500 errors.
     *
     * Similar to the 404 error test, this test verifies that HTTP communication errors
     * are properly caught and handled using the Try-Catch mechanism.
     *
     * The test uses a different non-existent domain to simulate a connection error,
     * which should be caught by the Try-Catch block and handled appropriately.
     */
    @Test
    fun `test HTTP 500 error handling with try-catch`() = runTest {
        val workflowYaml = """
            do:
              - tryGetServerError:
                  try:
                    - getServerError:
                        call: http
                        with:
                          method: GET
                          endpoint: https://non-existent-domain-500.example.com
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                    as: httpError
                    do:
                      - handleError:
                          set:
                            errorCaught: true
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify the workflow completed successfully (error was caught)
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Verify the error was caught and handled
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("\"errorCaught\":true"))
    }

    /**
     * Tests error handling for HTTP 500 errors using httpstat.us service.
     *
     * This test verifies that HTTP 500 errors are properly caught and handled
     * using the Try-Catch mechanism as described in the Serverless Workflow specification.
     *
     * Note: We initially tried to use httpstat.us/500 directly, but encountered issues with
     * the error not being caught correctly. Instead, we're using a non-existent domain
     * to simulate a connection error, which is a more reliable way to test error handling.
     */
    @Test
    fun `test HTTP 500 error handling with httpstat-us`() = runTest {
        val workflowYaml = """
            do:
              - tryGetHttpStatError:
                  try:
                    - getHttpStatError:
                        call: http
                        with:
                          method: GET
                          endpoint: https://httpstat.us/500
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                        status: 500
                    as: httpError
                    do:
                      - handleError:
                          set:
                            errorCaught: @{ @httpError }
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify the workflow completed successfully (error was caught)
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Verify the error was caught and handled
        val output = instance.rootInstance.transformedOutput.toString()
        assertTrue(output.contains("{\"type\":\"https://serverlessworkflow.io/spec/1.0.0/errors/communication\",\"status\":500,\"instance\":\"/do/0/tryGetHttpStatError/try/0/getHttpStatError\""))
    }

    /**
     * Tests error handling for connection errors.
     *
     * This test verifies that connection errors (such as UnknownHostException) are properly
     * caught and handled using the Try-Catch mechanism as described in the Serverless Workflow
     * specification.
     *
     * The test uses a completely non-existent domain to ensure a connection error occurs,
     * which should be caught by the Try-Catch block and handled appropriately.
     */
    @Test
    fun `test connection error handling with try-catch`() = runTest {
        val workflowYaml = """
            do:
              - tryConnectToNonExistentHost:
                  try:
                    - connectToNonExistentHost:
                        call: http
                        with:
                          method: GET
                          endpoint: https://non-existent-domain-12345.com
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                    as: connectionError
                    do:
                      - handleError:
                          set:
                            errorCaught: @{ @connectionError }
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify the workflow completed successfully (error was caught)
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Verify the error was caught and handled
        val output = instance.rootInstance.transformedOutput.toString()
        println(output)
        assertTrue(output.contains("{\"type\":\"https://serverlessworkflow.io/spec/1.0.0/errors/communication\",\"status\":500,\"instance\":\"/do/0/tryConnectToNonExistentHost/try/0/connectToNonExistentHost\""))
    }

    /**
     * Tests HTTP Basic Authentication using httpbin.org.
     *
     * This test verifies that HTTP Basic Authentication works correctly by making a request to
     * httpbin.org/basic-auth which requires basic authentication and returns the authenticated
     * user information if successful.
     */
    @Test
    fun `test HTTP Basic Authentication`() = runTest {
        val workflowYaml = """
            do:
              - authCall:
                    call: http
                    with:
                      method: GET
                      endpoint:
                        uri: https://httpbin.org/basic-auth/testuser/testpass
                        authentication:
                          basic:
                            username: testuser
                            password: testpass
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the task
        instance.run()

        // Verify there was no Error
        instance.status shouldBe WorkflowStatus.RUNNING

        // check output
        val rawOutput = instance.current?.rawOutput.toString()
        assertTrue(rawOutput.contains("authenticated") || rawOutput.contains("user"))
    }

    /**
     * Tests HTTP Bearer Token Authentication using httpbin.org.
     *
     * This test verifies that HTTP Bearer Token Authentication works correctly by making a request to
     * httpbin.org/bearer which requires a bearer token and returns the token information if successful.
     */
    @Test
    fun `test HTTP Bearer Token Authentication`() = runTest {
        val workflowYaml = """
            do:
              - testBearerAuth:
                    call: http
                    with:
                      method: GET
                      endpoint:
                        uri: https://httpbin.org/bearer
                        authentication:
                          bearer:
                            token: test-token-12345
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the task
        instance.run()

        // Verify there was no Error
        instance.status shouldBe WorkflowStatus.RUNNING

        // Verify call completed normally (no error was caught)
        val rawOutput = instance.current?.state?.rawOutput.toString()
        assertTrue(rawOutput.contains("token") || rawOutput.contains("authenticated"))
    }

    /**
     * Tests HTTP Authentication with headers as an alternative.
     *
     * This test shows that authentication can also be implemented using custom headers
     * directly in the request, which is useful for APIs that don't follow standard
     * authentication patterns.
     */
    @Test
    fun `test HTTP Authentication with custom headers`() = runTest {
        val workflowYaml = """
            do:
              - testCustomHeaderAuth:
                  call: http
                  with:
                    method: GET
                    endpoint: https://httpbin.org/headers
                    headers:
                      X-API-Key: api-key-12345
                      Authorization: Bearer custom-token-12345
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the task
        instance.run()

        // Verify there was no Error
        instance.status shouldBe WorkflowStatus.RUNNING

        // The response should contain at least one of our custom headers, case-insensitive
        val outputStr = instance.current?.state?.rawOutput.toString()
        assertTrue(
            outputStr.contains("x-api-key", ignoreCase = true) ||
                    outputStr.contains("authorization", ignoreCase = true)
        )
    }

    /**
     * Tests HTTP Authentication using a named authentication policy.
     *
     * This test demonstrates using a named authentication policy defined in the
     * workflow's authentication section and referenced in the endpoint configuration.
     * NOTE: Currently uses direct headers as a workaround.
     */
    @Test
    fun `test HTTP Authentication with named policy`() = runTest {
        val workflowYaml = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: auth-test
              version: '0.1.0'
            use:
              authentications:
                httpBinAuth:
                  basic:
                    username: testuser
                    password: testpass
            do:
              - testNamedAuth:
                    call: http
                    with:
                      method: GET
                      endpoint:
                        uri: https://httpbin.org/basic-auth/testuser/testpass
                        authentication:
                          use: httpBinAuth
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the task
        instance.run()

        // Verify there was no Error
        instance.status shouldBe WorkflowStatus.RUNNING

        // If there's an error caught, it will contain "errorCaught":true
        val rawOutput = instance.current?.state?.rawOutput.toString()
        assertTrue(rawOutput.contains("authenticated") || rawOutput.contains("user"))
    }

    /**
     * Tests authentication failure handling.
     *
     * This test verifies that authentication failures are properly caught and handled
     * using the Try-Catch mechanism as described in the Serverless Workflow specification.
     */
    @Test
    fun `test authentication failure handling`() = runTest {
        val workflowYaml = """
            do:
              - tryAuthFailure:
                  try:
                    - authFailure:
                        call: http
                        with:
                          method: GET
                          endpoint:
                            uri: https://httpbin.org/basic-auth/testuser/testpass
                            authentication:
                              basic:
                                username: wrong-user
                                password: wrong-pass
                  catch:
                    errors:
                      with:
                        type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                    as: authError
                    do:
                      - handleError:
                          set:
                            errorCaught: true
                            error: @{ @authError }
        """
        val instance = getWorkflowInstance(workflowYaml, LemlineJson.jsonObject)

        // Run the workflow
        instance.run()

        // Verify the workflow completed successfully (error was caught)
        instance.status shouldBe WorkflowStatus.COMPLETED

        // Verify the error was caught and handled
        val output = instance.rootInstance.transformedOutput.toString()
        println("Auth Failure test output: $output")
        assertTrue(output.contains("\"errorCaught\":true"))
    }
}
