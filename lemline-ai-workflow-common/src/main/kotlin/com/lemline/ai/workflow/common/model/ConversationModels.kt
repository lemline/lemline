// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.common.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: String,
)

@Serializable
enum class InputFieldType(val schemaType: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    OBJECT("object"),
    ARRAY("array"),
}

@Serializable
data class InputField(
    val name: String,
    val type: InputFieldType,
    val required: Boolean,
    val description: String,
    val example: String? = null,
)

@Serializable
enum class InputContractSource {
    PROVIDED,
    INFERRED,
    MIXED,
}

@Serializable
data class InputContractSummary(
    val fields: List<InputField> = emptyList(),
    val requiredFields: List<String> = emptyList(),
    val format: String = "json",
    val source: InputContractSource = InputContractSource.INFERRED,
    val confidence: Double = 0.0,
    val humanSummary: String = "No input contract resolved yet.",
)

@Serializable
enum class ClarificationFocus {
    GOAL,
    CONTROL_FLOW,
    INTEGRATIONS,
    INPUT_CONTRACT,
    OUTPUT_CONTRACT,
    ERROR_HANDLING,
    CONFIRMATION,
}

@Serializable
enum class ValidationProfile(val profileName: String) {
    SPEC_STRICT("spec-strict"),
    LEMLINE_COMPATIBLE("lemline-compatible"),
}

@Serializable
enum class ValidationLevel {
    ERROR,
    WARNING,
    INFO,
}

@Serializable
data class ValidationIssue(
    val level: ValidationLevel,
    val code: String,
    val message: String,
    val path: String? = null,
)

@Serializable
data class ValidationReport(
    val profile: ValidationProfile,
    val valid: Boolean,
    val issues: List<ValidationIssue>,
)

@Serializable
data class WorkflowAssumption(
    val id: String,
    val statement: String,
)

@Serializable
data class ClarificationQuestion(
    val question: String,
    val focus: ClarificationFocus,
)

@Serializable
data class ConfirmationSnapshot(
    val workflowPurposeSummary: String,
    val inputContractSummary: InputContractSummary,
    val assumptions: List<WorkflowAssumption>,
    val needsConfirmation: Boolean = true,
)

@Serializable
data class TurnRequest(
    val sessionId: String,
    val userMessage: String,
    val profile: ValidationProfile = ValidationProfile.SPEC_STRICT,
    val clarificationCap: Int = 8,
)

@Serializable
sealed interface TurnResult {

    @Serializable
    data class ClarificationRequired(
        val question: ClarificationQuestion,
        val turnCount: Int,
    ) : TurnResult

    @Serializable
    data class ConfirmationRequired(
        val snapshot: ConfirmationSnapshot,
        val turnCount: Int,
    ) : TurnResult

    @Serializable
    data class FinalWorkflow(
        val workflowYaml: String,
        val validationReport: ValidationReport,
        val assumptions: List<WorkflowAssumption>,
        val inputContractSummary: InputContractSummary,
        val confidence: Double,
        val needsConfirmation: Boolean = false,
    ) : TurnResult

    @Serializable
    data class Failure(
        val message: String,
        val recoverable: Boolean = true,
    ) : TurnResult
}
