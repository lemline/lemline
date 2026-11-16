// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.llm

interface WorkflowAuthoringModel {
    suspend fun generateWorkflowYaml(
        systemPrompt: String,
        userPrompt: String,
    ): String
}
