// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.validation

import com.lemline.ai.workflow.common.model.InputContractSource
import com.lemline.ai.workflow.common.model.InputContractSummary
import com.lemline.ai.workflow.common.model.ValidationIssue
import com.lemline.ai.workflow.common.model.ValidationLevel
import com.lemline.ai.workflow.common.model.ValidationProfile
import com.lemline.ai.workflow.common.model.ValidationReport
import com.lemline.ai.workflow.common.model.WorkflowAssumption
import com.lemline.ai.workflow.core.skill.ServerlessWorkflowSkillLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

class WorkflowValidationPipeline(
    private val runSkillScript: Boolean,
    private val skillRootPath: String,
) {

    fun validate(
        yaml: String,
        profile: ValidationProfile,
        inputContractSummary: InputContractSummary,
        assumptions: List<WorkflowAssumption>,
    ): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        issues += validateInputBlocks(yaml, inputContractSummary, assumptions)
        issues += validateInputPathUsages(yaml, inputContractSummary)
        if (runSkillScript) {
            issues += validateWithSkillScript(yaml, profile)
        }

        val hasError = issues.any { it.level == ValidationLevel.ERROR }
        return ValidationReport(
            profile = profile,
            valid = !hasError,
            issues = issues.toList(),
        )
    }

    private fun validateInputBlocks(
        yaml: String,
        input: InputContractSummary,
        assumptions: List<WorkflowAssumption>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (!yaml.contains("\ninput:") && !yaml.startsWith("input:")) {
            issues += ValidationIssue(
                level = ValidationLevel.ERROR,
                code = "MISSING_INPUT_BLOCK",
                message = "Workflow must declare a top-level input block.",
                path = "$.input",
            )
        }
        if (input.confidence >= 0.60 && !yaml.contains("\n  schema:")) {
            issues += ValidationIssue(
                level = ValidationLevel.ERROR,
                code = "MISSING_INPUT_SCHEMA",
                message = "input.schema is required for confident input contracts.",
                path = "$.input.schema",
            )
        }
        if (input.source != InputContractSource.PROVIDED) {
            val missing = input.requiredFields.filter { name ->
                assumptions.none { assumption -> assumption.statement.contains("'$name'") }
            }
            if (missing.isNotEmpty()) {
                issues += ValidationIssue(
                    level = ValidationLevel.ERROR,
                    code = "MISSING_INFERENCE_ASSUMPTIONS",
                    message = "Each inferred required input field must appear in assumptions: ${missing.joinToString(", ")}.",
                    path = "$.assumptions",
                )
            }
        }
        return issues
    }

    private fun validateInputPathUsages(
        yaml: String,
        input: InputContractSummary,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val declared = input.fields.map { it.name }.toSet()
        if (declared.isEmpty()) {
            return issues
        }

        val dottedMatches = Regex("""(?:${'$'}input|\.)\.([a-zA-Z_][a-zA-Z0-9_]*)""")
            .findAll(yaml)
            .map { it.groupValues[1] }
            .toSet()
        val missing = dottedMatches - declared
        if (missing.isNotEmpty()) {
            issues += ValidationIssue(
                level = ValidationLevel.WARNING,
                code = "UNDECLARED_INPUT_PATH",
                message = "Workflow references undeclared input paths: ${missing.sorted().joinToString(", ")}.",
                path = "$.do",
            )
        }
        return issues
    }

    private fun validateWithSkillScript(
        yaml: String,
        profile: ValidationProfile,
    ): List<ValidationIssue> {
        val skillRoot = Path.of(skillRootPath)
        val validator = ServerlessWorkflowSkillLoader.validatorScriptPath(skillRoot)
        if (!skillRoot.exists() || !validator.exists()) {
            return listOf(
                ValidationIssue(
                    level = ValidationLevel.WARNING,
                    code = "SKILL_VALIDATOR_NOT_FOUND",
                    message = "Skill validator script not found at ${validator.absolutePathString()}.",
                )
            )
        }

        val tempFile = Files.createTempFile("lemline-ai-workflow-", ".yaml")
        return try {
            Files.writeString(tempFile, yaml)
            val process = ProcessBuilder(
                "python3",
                validator.absolutePathString(),
                tempFile.absolutePathString(),
                "--profile",
                profile.profileName,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            parseSkillOutput(output, exitCode)
        } catch (exception: Exception) {
            listOf(
                ValidationIssue(
                    level = ValidationLevel.WARNING,
                    code = "SKILL_VALIDATOR_EXECUTION_FAILED",
                    message = "Unable to execute skill validator script: ${exception.message}",
                )
            )
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun parseSkillOutput(
        output: String,
        exitCode: Int,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val linePattern = Regex("""^(ERROR|WARN)\s+([^:]+):\s+(.+)$""")
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val match = linePattern.find(line) ?: return@forEach
                val level = if (match.groupValues[1] == "ERROR") ValidationLevel.ERROR else ValidationLevel.WARNING
                issues += ValidationIssue(
                    level = level,
                    code = "SKILL_VALIDATOR",
                    path = match.groupValues[2],
                    message = match.groupValues[3],
                )
            }

        if (exitCode != 0 && issues.none { it.level == ValidationLevel.ERROR }) {
            issues += ValidationIssue(
                level = ValidationLevel.ERROR,
                code = "SKILL_VALIDATOR_FAILED",
                message = "Skill validator returned non-zero exit code $exitCode.",
            )
        }
        return issues
    }
}
