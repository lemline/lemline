// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.common.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationStatus {
    COLLECTING,
    CONFIRMATION_REQUIRED,
    FINALIZED,
    ERROR,
}

@Serializable
data class IntentSnapshot(
    val purpose: String? = null,
    val requiresHttpCall: Boolean = false,
    val requiresLoop: Boolean = false,
    val requiresCondition: Boolean = false,
    val requiresEventWait: Boolean = false,
    val requiresRetry: Boolean = false,
    val outputExpectation: String? = null,
    val unresolvedCriticalAmbiguities: Int = 0,
)

@Serializable
data class ConversationState(
    val sessionId: String,
    val revision: Long = 0,
    val parentRevisionId: Long? = null,
    val status: ConversationStatus = ConversationStatus.COLLECTING,
    val createdAt: String,
    val updatedAt: String,
    val turnCount: Int = 0,
    val clarificationCount: Int = 0,
    val targetProfile: ValidationProfile = ValidationProfile.SPEC_STRICT,
    val messages: List<ChatMessage> = emptyList(),
    val intent: IntentSnapshot = IntentSnapshot(),
    val inputContractSummary: InputContractSummary = InputContractSummary(),
    val assumptions: List<WorkflowAssumption> = emptyList(),
    val confirmationSnapshot: ConfirmationSnapshot? = null,
    val finalWorkflowYaml: String? = null,
    val finalValidation: ValidationReport? = null,
    val confidence: Double = 0.0,
)
