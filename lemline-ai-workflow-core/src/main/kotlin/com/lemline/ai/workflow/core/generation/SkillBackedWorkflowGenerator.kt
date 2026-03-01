// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.generation

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.core.llm.WorkflowAuthoringModel
import com.lemline.ai.workflow.core.skill.ServerlessWorkflowSkillLoader

class SkillBackedWorkflowGenerator(
    skillRootPath: String,
    private val deterministicBuilder: DeterministicWorkflowYamlBuilder = DeterministicWorkflowYamlBuilder(),
    private val authoringModel: WorkflowAuthoringModel? = null,
) {
    private val skillContext = ServerlessWorkflowSkillLoader.load(skillRootPath)

    suspend fun generate(state: ConversationState): String {
        val fallback = deterministicBuilder.build(state)
        val model = authoringModel ?: return fallback

        val userPrompt = buildPrompt(state)
        val raw = model.generateWorkflowYaml(
            systemPrompt = skillContext.systemPrompt,
            userPrompt = userPrompt,
        )
        val sanitized = sanitizeYaml(raw)
        return sanitized.ifBlank { fallback }
    }

    private fun buildPrompt(state: ConversationState): String = buildString {
        appendLine("Create one Serverless Workflow v1.0.0 YAML.")
        appendLine("Conversation summary:")
        appendLine("- purpose: ${state.intent.purpose ?: "unspecified"}")
        appendLine("- requiresHttpCall: ${state.intent.requiresHttpCall}")
        appendLine("- requiresLoop: ${state.intent.requiresLoop}")
        appendLine("- requiresCondition: ${state.intent.requiresCondition}")
        appendLine("- requiresEventWait: ${state.intent.requiresEventWait}")
        appendLine("- requiresRetry: ${state.intent.requiresRetry}")
        appendLine("Resolved input contract:")
        state.inputContractSummary.fields.forEach { field ->
            appendLine("- ${field.name}: ${field.type.schemaType}, required=${field.required}")
        }
        appendLine("Assumptions:")
        state.assumptions.forEach { appendLine("- ${it.statement}") }
        appendLine("Constraints:")
        appendLine("- Always include top-level input block.")
        appendLine("- Include input.schema when confidence >= 0.60.")
        appendLine("- Output only raw YAML, no Markdown fences.")
    }

    private fun sanitizeYaml(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }
        return trimmed
            .removePrefix("```yaml")
            .removePrefix("```yml")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
