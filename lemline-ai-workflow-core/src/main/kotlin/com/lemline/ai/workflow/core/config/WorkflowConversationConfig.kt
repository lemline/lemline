// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.config

import com.lemline.ai.workflow.common.model.ValidationProfile

data class WorkflowConversationConfig(
    val defaultProfile: ValidationProfile = ValidationProfile.SPEC_STRICT,
    val defaultClarificationCap: Int = 8,
    val dynamicClarificationTargetMin: Int = 6,
    val dynamicClarificationTargetMax: Int = 10,
    val hardClarificationCeiling: Int = 12,
    val minimumInputContractConfidence: Double = 0.60,
    val minimumFinalConfidence: Double = 0.55,
    val skillRootPath: String = ".claude/skills/serverless-workflow-writer",
    val runSkillValidatorScript: Boolean = true,
)
