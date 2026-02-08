// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.engine

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.model.TurnRequest
import com.lemline.ai.workflow.common.model.TurnResult
import com.lemline.ai.workflow.common.store.ConversationStateStore
import com.lemline.ai.workflow.core.config.WorkflowConversationConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkflowConversationEngineTest {

    @Test
    fun `confirmation payload should include input contract summary`() = runBlocking {
        val engine = newEngine()

        val result = engine.processTurn(
            TurnRequest(
                sessionId = "s-confirmation",
                userMessage = "Create an order workflow with API call and condition, fields: orderId:string amount:number shouldProceed:boolean",
            )
        )

        assertTrue(result is TurnResult.ConfirmationRequired)
        val required = result.snapshot.inputContractSummary.requiredFields.toSet()
        assertTrue("orderId" in required)
        assertTrue("amount" in required)
        assertTrue("shouldProceed" in required)
    }

    @Test
    fun `should ask input clarifications when user gives no input format then finalize with schema`() = runBlocking {
        val engine = newEngine()
        val sessionId = "s-no-input"

        val first = engine.processTurn(
            TurnRequest(
                sessionId = sessionId,
                userMessage = "Need a workflow that calls an API and checks a condition before continuing.",
            )
        )
        assertTrue(first is TurnResult.ClarificationRequired)
        assertEquals("INPUT_CONTRACT", first.question.focus.name)

        val second = engine.processTurn(
            TurnRequest(
                sessionId = sessionId,
                userMessage = "Use fields: resourceId:string shouldProceed:boolean",
            )
        )
        assertTrue(second is TurnResult.ConfirmationRequired)

        val third = engine.processTurn(
            TurnRequest(
                sessionId = sessionId,
                userMessage = "yes confirm",
            )
        )
        assertTrue(third is TurnResult.FinalWorkflow)
        assertContains(third.workflowYaml, "\ninput:")
        assertContains(third.workflowYaml, "\n  schema:")
    }

    @Test
    fun `cap reached with ambiguity should finalize with assumptions and downgraded confidence`() = runBlocking {
        val engine = newEngine()
        val sessionId = "s-cap"

        val first = engine.processTurn(
            TurnRequest(
                sessionId = sessionId,
                userMessage = "Need something with API calls, maybe some data, not sure.",
                clarificationCap = 1,
            )
        )
        assertTrue(first is TurnResult.ClarificationRequired)

        val second = engine.processTurn(
            TurnRequest(
                sessionId = sessionId,
                userMessage = "still ambiguous maybe anything works",
                clarificationCap = 1,
            )
        )
        assertTrue(second is TurnResult.FinalWorkflow)
        assertTrue(second.needsConfirmation)
        assertTrue(second.assumptions.isNotEmpty())
    }

    @Test
    fun `golden prompts should always produce yaml with input block`() = runBlocking {
        val prompts = listOf(
            "Build a workflow for batch invoice processing with loops and API calls.",
            "Build a workflow to call an API and send approval or rejection.",
            "Build a workflow and maybe unknown input details for customer checks.",
        )

        prompts.forEachIndexed { index, prompt ->
            val engine = newEngine()
            val session = "golden-$index"
            var nextMessage = prompt
            var finalized: TurnResult.FinalWorkflow? = null

            repeat(6) {
                when (
                    val turn = engine.processTurn(
                        TurnRequest(
                            sessionId = session,
                            userMessage = nextMessage,
                        )
                    )
                ) {
                    is TurnResult.ClarificationRequired -> {
                        nextMessage = when (turn.question.focus.name) {
                            "CONTROL_FLOW" -> "Use an API call with a condition in the workflow."
                            "GOAL" -> "Goal is to validate customer data and route processing."
                            else -> "fields: resourceId:string shouldProceed:boolean items:array"
                        }
                    }

                    is TurnResult.ConfirmationRequired -> {
                        nextMessage = "yes confirm"
                    }

                    is TurnResult.FinalWorkflow -> {
                        finalized = turn
                        return@repeat
                    }

                    is TurnResult.Failure -> fail("Conversation failed for prompt '$prompt': ${turn.message}")
                }
            }

            val final = finalized ?: fail("Conversation did not reach FinalWorkflow for prompt '$prompt'")
            assertContains(final.workflowYaml, "\ninput:")
        }
    }

    private fun newEngine(): WorkflowConversationEngine = WorkflowConversationEngine(
        stateStore = TestConversationStateStore(),
        config = WorkflowConversationConfig(
            runSkillValidatorScript = false,
        ),
    )
}

private class TestConversationStateStore : ConversationStateStore {
    private val states = linkedMapOf<String, ConversationState>()

    override suspend fun load(sessionId: String): ConversationState? = states[sessionId]

    override suspend fun save(state: ConversationState): ConversationState {
        states[state.sessionId] = state
        return state
    }

    override suspend fun delete(sessionId: String) {
        states.remove(sessionId)
    }
}
