// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.generation

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.model.InputField
import com.lemline.ai.workflow.common.model.InputFieldType
import kotlin.math.absoluteValue

class DeterministicWorkflowYamlBuilder {

    fun build(state: ConversationState): String = buildString {
        val workflowName = slugify(state.intent.purpose ?: "generated-workflow")
        appendLine("document:")
        appendLine("  dsl: \"1.0.0\"")
        appendLine("  namespace: ai.generated")
        appendLine("  name: $workflowName")
        appendLine("  version: \"1.0.0\"")
        appendLine("  title: \"AI generated workflow\"")
        appendInputBlock(state)
        if (state.intent.requiresRetry) {
            appendLine("use:")
            appendLine("  retries:")
            appendLine("    transientHttp:")
            appendLine("      limit:")
            appendLine("        attempt:")
            appendLine("          count: 3")
            appendLine("      backoff:")
            appendLine("        exponential: {}")
            appendLine("  timeouts:")
            appendLine("    defaultTimeout:")
            appendLine("      after:")
            appendLine("        seconds: 20")
        }
        appendLine("do:")
        appendLine("  - normalizeInput:")
        appendLine("      set:")
        appendLine("        payload: \${ \$input }")
        if (state.intent.requiresCondition) {
            appendLine("  - evaluateGuard:")
            appendLine("      switch:")
            appendLine("        - runMain:")
            appendLine("            when: \${ .shouldProceed == true }")
            appendLine("            then: mainPath")
            appendLine("        - default:")
            appendLine("            then: skipPath")
        }
        appendLine("  - mainPath:")
        appendLine("      do:")
        appendNestedMainTasks(state)
        if (state.intent.requiresCondition) {
            appendLine("  - skipPath:")
            appendLine("      set:")
            appendLine("        skipped: true")
            appendLine("      then: end")
        }
        appendLine("output:")
        appendLine("  as: \${ . }")
    }

    private fun StringBuilder.appendInputBlock(state: ConversationState) {
        val fields = state.inputContractSummary.fields.ifEmpty {
            listOf(
                InputField(
                    name = "payload",
                    type = InputFieldType.OBJECT,
                    required = true,
                    description = "Fallback request payload.",
                )
            )
        }
        appendLine("input:")
        if (state.inputContractSummary.confidence >= 0.60) {
            appendLine("  schema:")
            appendLine("    type: object")
            appendLine("    additionalProperties: true")
            val required = fields.filter { it.required }
            if (required.isNotEmpty()) {
                appendLine("    required:")
                required.forEach { appendLine("      - ${it.name}") }
            }
            appendLine("    properties:")
            fields.forEach { field ->
                appendLine("      ${field.name}:")
                appendLine("        type: ${field.type.schemaType}")
                appendLine("        description: \"${field.description.escapeYaml()}\"")
                field.example?.let {
                    appendLine("        example: \"${it.escapeYaml()}\"")
                }
            }
        }
        appendLine("  from: \${ \$input }")
    }

    private fun StringBuilder.appendNestedMainTasks(state: ConversationState) {
        if (state.intent.requiresHttpCall) {
            if (state.intent.requiresRetry) {
                appendLine("        - invokeExternalApi:")
                appendLine("            try:")
                appendLine("              - callRemote:")
                appendLine("                  call: http")
                appendLine("                  timeout: defaultTimeout")
                appendLine("                  with:")
                appendLine("                    method: post")
                appendLine("                    endpoint:")
                appendLine("                      uri: https://api.example.com/workflows/process")
                appendLine("                    body:")
                appendLine("                      input: \${ . }")
                appendLine("            catch:")
                appendLine("              errors:")
                appendLine("                with:")
                appendLine("                  type: https://serverlessworkflow.io/spec/1.0.0/errors/communication")
                appendLine("                  status: 503")
                appendLine("              retry: transientHttp")
                appendLine("              do:")
                appendLine("                - setFailure:")
                appendLine("                    set:")
                appendLine("                      communicationFailure: true")
            } else {
                appendLine("        - invokeExternalApi:")
                appendLine("            call: http")
                appendLine("            with:")
                appendLine("              method: post")
                appendLine("              endpoint:")
                appendLine("                uri: https://api.example.com/workflows/process")
                appendLine("              body:")
                appendLine("                input: \${ . }")
            }
        }

        if (state.intent.requiresLoop) {
            appendLine("        - iterateItems:")
            appendLine("            for:")
            appendLine("              each: item")
            appendLine("              in: .items")
            appendLine("            do:")
            appendLine("              - markItem:")
            appendLine("                  set:")
            appendLine("                    lastProcessedItem: \${ \$item }")
        }

        if (state.intent.requiresEventWait) {
            appendLine("        - waitForSignal:")
            appendLine("            wait:")
            appendLine("              seconds: 5")
        }

        appendLine("        - markMainCompleted:")
        appendLine("            set:")
        appendLine("              mainPathCompleted: true")
        appendLine("            then: end")
    }

    private fun slugify(input: String): String {
        val normalized = input.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (normalized.isNotBlank()) {
            return normalized
        }
        return "workflow-${input.hashCode().absoluteValue}"
    }
}

private fun String.escapeYaml(): String = replace("\"", "\\\"")
