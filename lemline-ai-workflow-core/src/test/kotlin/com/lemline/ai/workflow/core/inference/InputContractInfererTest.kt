// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.inference

import com.lemline.ai.workflow.common.model.InputContractSource
import com.lemline.ai.workflow.common.model.InputContractSummary
import com.lemline.ai.workflow.common.model.IntentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputContractInfererTest {

    private val inferer = InputContractInferer()

    @Test
    fun `should infer required fields from intent patterns`() {
        val intent = IntentSnapshot(
            purpose = "Process order approvals",
            requiresHttpCall = true,
            requiresCondition = true,
            requiresLoop = true,
        )

        val inference = inferer.infer(
            previous = InputContractSummary(),
            intent = intent,
            userMessage = "Build a workflow that calls an API, loops on items, and branches on approval.",
            minimumConfidence = 0.60,
        )

        val names = inference.summary.fields.map { it.name }.toSet()
        assertTrue("resourceId" in names)
        assertTrue("shouldProceed" in names)
        assertTrue("items" in names)
        assertEquals(InputContractSource.INFERRED, inference.summary.source)
    }

    @Test
    fun `should detect ambiguity when user provides json fields without explicit types`() {
        val intent = IntentSnapshot(
            purpose = "Handle payment",
            requiresHttpCall = true,
        )

        val inference = inferer.infer(
            previous = InputContractSummary(),
            intent = intent,
            userMessage = """payload is {"customerId":"abc","amount":120} maybe more later""",
            minimumConfidence = 0.60,
        )

        assertTrue(inference.unresolvedCriticalAmbiguities > 0)
        assertTrue(inference.summary.confidence < 0.90)
    }
}
