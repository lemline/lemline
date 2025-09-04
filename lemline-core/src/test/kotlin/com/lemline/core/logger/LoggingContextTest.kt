// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.logger

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.PositionPointer
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import io.kotest.matchers.shouldBe
import java.util.*
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class LoggingContextTest {

    @Test
    fun `non-suspending withWorkflowContext sets and restores MDC`() {
        // Ensure MDC is initially empty
        MDC.clear()
        assertNull(MDC.get(LoggingContext.WORKFLOW_ID))

        val id1 = UUID.randomUUID()
        val block: () -> Int = {
            // Inside block, MDC should be populated
            MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id1.toString()
            MDC.get(LoggingContext.WORKFLOW_NAME) shouldBe "example"
            MDC.get(LoggingContext.WORKFLOW_VERSION) shouldBe "1.0.0"
            MDC.get(LoggingContext.CURRENT_POSITION) shouldBe NodePosition.root.toString()
            42
        }
        val res = withWorkflowContext(
            workflowId = WorkflowId(id1),
            workflowName = WorkflowName("example"),
            workflowVersion = WorkflowVersion("1.0.0"),
            currentPosition = NodePosition.root,
            block = block,
        )

        res shouldBe 42

        // After block returns, MDC should be restored (empty)
        MDC.get(LoggingContext.WORKFLOW_ID) shouldBe null
        MDC.get(LoggingContext.WORKFLOW_NAME) shouldBe null
        MDC.get(LoggingContext.WORKFLOW_VERSION) shouldBe null
        MDC.get(LoggingContext.CURRENT_POSITION) shouldBe null
    }

    @Test
    fun `suspending withWorkflowContext propagates across coroutines and thread switches`() = runTest {
        MDC.clear()

        val id2 = UUID.randomUUID()
        val outer: suspend () -> Int = {
            // Initial assertions on current context and MDC
            MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString()
            val ctx = currentCoroutineContext()[WorkflowLoggingContext]
            ctx?.workflowId shouldBe id2.toString()
            ctx?.workflowName shouldBe "coroutines"
            ctx?.workflowVersion shouldBe "2.0"
            ctx?.currentPosition shouldBe NodePosition.root.toString()

            // Switch thread pool
            withContext(Dispatchers.Default) {
                // Even after switching threads, MDCContext should propagate
                MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString()
                val inner = currentCoroutineContext()[WorkflowLoggingContext]
                inner?.workflowName shouldBe "coroutines"

                // Delay to ensure suspension/resume
                delay(10)

                // Launch child coroutine and verify isolation from siblings
                val job = launch {
                    MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString()
                    currentCoroutineContext()[WorkflowLoggingContext]?.workflowVersion shouldBe "2.0"

                    // Nested override partially updates values and merges with current
                    val nestedBlock: suspend () -> Int = {
                        MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString() // unchanged
                        MDC.get(LoggingContext.WORKFLOW_NAME) shouldBe "nested" // overridden
                        // Switch again to IO to test another dispatcher
                        withContext(Dispatchers.IO) {
                            MDC.get(LoggingContext.WORKFLOW_NAME) shouldBe "nested"
                            currentCoroutineContext()[WorkflowLoggingContext]?.workflowName shouldBe "nested"
                        }
                        7
                    }
                    val v = withWorkflowContext(
                        workflowName = WorkflowName("nested"),
                        block = nestedBlock,
                    )
                    v shouldBe 7

                    // After nested block, previous values are visible again
                    MDC.get(LoggingContext.WORKFLOW_NAME) shouldBe "coroutines"
                }
                job.join()

                // Start a sibling async and ensure it sees same base context
                val deferred = async {
                    MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString()
                    currentCoroutineContext()[WorkflowLoggingContext]?.currentPosition shouldBe NodePosition.root.toString()
                    // Create a new child context to ensure restoration works
                    val childBlock: suspend () -> Unit = {
                        MDC.get(LoggingContext.CURRENT_POSITION) shouldBe "/root/child"
                    }
                    withWorkflowContext(
                        currentPosition = PositionPointer("root/child").toPosition(),
                        block = childBlock,
                    )
                    // Restoration to previous value
                    MDC.get(LoggingContext.CURRENT_POSITION) shouldBe NodePosition.root.toString()
                    100
                }
                deferred.await()
            }

            // Back to original context, still present
            MDC.get(LoggingContext.WORKFLOW_ID) shouldBe id2.toString()
            1234
        }
        val result = withWorkflowContext(
            workflowId = WorkflowId(id2),
            workflowName = WorkflowName("coroutines"),
            workflowVersion = WorkflowVersion("2.0"),
            currentPosition = NodePosition.root,
            block = outer,
        )

        result shouldBe 1234

        // After outer block completes, MDC is restored (cleared as initially set)
        MDC.get(LoggingContext.WORKFLOW_ID) shouldBe null
    }

    @Test
    fun `partial overrides take values from current WorkflowLoggingContext`() = runTest {
        MDC.clear()

        val id3 = UUID.randomUUID()
        val outer: suspend () -> Int = {
            // Inside outer, override only currentPosition
            val inner: suspend () -> Int = {
                val ctx = currentCoroutineContext()[WorkflowLoggingContext]
                ctx?.workflowId shouldBe id3.toString()
                ctx?.workflowName shouldBe "outer"
                ctx?.workflowVersion shouldBe "v1"
                ctx?.currentPosition shouldBe "/root/1"
                5
            }
            withWorkflowContext(
                currentPosition = PositionPointer("root/1").toPosition(),
                block = inner,
            )
        }
        val value = withWorkflowContext(
            workflowId = WorkflowId(id3),
            workflowName = WorkflowName("outer"),
            workflowVersion = WorkflowVersion("v1"),
            currentPosition = PositionPointer("root/0").toPosition(),
            block = outer,
        )

        value shouldBe 5
        MDC.get(LoggingContext.WORKFLOW_ID) shouldBe null
    }
}
