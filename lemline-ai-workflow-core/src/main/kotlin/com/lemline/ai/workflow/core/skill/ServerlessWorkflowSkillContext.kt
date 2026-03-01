// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.skill

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

data class ServerlessWorkflowSkillContext(
    val skillRoot: Path,
    val systemPrompt: String,
    val checklist: String,
)

object ServerlessWorkflowSkillLoader {

    private val requiredReferences = listOf(
        "references/02-data-and-expressions.md",
        "references/11-authoring-checklist.md",
        "references/13-semantic-constraints.md",
        "references/15-generate-from-prompt.md",
    )

    fun load(rootPath: String): ServerlessWorkflowSkillContext {
        val root = Paths.get(rootPath)
        val skillFile = root.resolve("SKILL.md")
        val available = skillFile.exists()
        if (!available) {
            return ServerlessWorkflowSkillContext(
                skillRoot = root,
                systemPrompt = "Serverless Workflow v1.0.0 authoring. Output one valid YAML workflow.",
                checklist = "Skill files unavailable.",
            )
        }

        val skillBody = skillFile.readText()
        val references = requiredReferences.joinToString("\n\n") { relativePath ->
            val path = root.resolve(relativePath)
            if (path.exists()) {
                "## $relativePath\n${path.readText()}"
            } else {
                "## $relativePath\n(Missing reference)"
            }
        }

        val checklist = buildString {
            appendLine("Required skill assets:")
            appendLine("- scripts/validate_workflow.py")
            appendLine("- scripts/run_eval_suite.sh")
            appendLine("Required references:")
            requiredReferences.forEach { appendLine("- $it") }
        }

        return ServerlessWorkflowSkillContext(
            skillRoot = root,
            systemPrompt = "$skillBody\n\n$references",
            checklist = checklist,
        )
    }

    fun validatorScriptPath(root: Path): Path = root.resolve("scripts/validate_workflow.py")

    fun ensureSkillIsAccessible(root: Path): Boolean = Files.exists(root.resolve("SKILL.md"))
}
