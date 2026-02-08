// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.engine

import com.lemline.ai.workflow.common.model.ClarificationFocus
import com.lemline.ai.workflow.common.model.ClarificationQuestion
import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.model.InputContractSource

class ClarificationPlanner {

    fun nextQuestion(state: ConversationState): ClarificationQuestion? {
        if (state.intent.purpose == null) {
            return ClarificationQuestion(
                focus = ClarificationFocus.GOAL,
                question = "What is the single business outcome this workflow must achieve?",
            )
        }

        if (!state.intent.requiresHttpCall && !state.intent.requiresLoop && !state.intent.requiresCondition) {
            return ClarificationQuestion(
                focus = ClarificationFocus.CONTROL_FLOW,
                question = "Should the workflow include an API call, a loop, a condition, or a combination of these?",
            )
        }

        if (state.inputContractSummary.requiredFields.isEmpty()) {
            return ClarificationQuestion(
                focus = ClarificationFocus.INPUT_CONTRACT,
                question = "Please provide the required input fields with types (example: orderId:string, amount:number).",
            )
        }

        if (state.inputContractSummary.source == InputContractSource.INFERRED && state.clarificationCount == 0) {
            return ClarificationQuestion(
                focus = ClarificationFocus.INPUT_CONTRACT,
                question = "I inferred the input contract. Can you confirm each required field type and requiredness?",
            )
        }

        if (state.inputContractSummary.confidence < 0.60) {
            return ClarificationQuestion(
                focus = ClarificationFocus.INPUT_CONTRACT,
                question = "Can you confirm the exact type and requiredness of each input field?",
            )
        }

        if (state.inputContractSummary.fields.any { !it.required }) {
            return ClarificationQuestion(
                focus = ClarificationFocus.INPUT_CONTRACT,
                question = "Which optional fields should become required for this workflow to run safely?",
            )
        }

        return null
    }
}
