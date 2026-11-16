// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.manual

import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.model.TurnRequest
import com.lemline.ai.workflow.common.model.TurnResult
import com.lemline.ai.workflow.common.model.ValidationIssue
import com.lemline.ai.workflow.common.model.ValidationProfile
import com.lemline.ai.workflow.common.store.ConversationStateStore
import com.lemline.ai.workflow.core.config.WorkflowConversationConfig
import com.lemline.ai.workflow.core.engine.WorkflowConversationEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val env = ManualSettings.fromEnvironment()
    val engine = WorkflowConversationEngine(
        stateStore = ManualInMemoryStateStore(),
        config = WorkflowConversationConfig(
            defaultProfile = env.profile,
            defaultClarificationCap = env.clarificationCap,
            skillRootPath = env.skillRoot,
            runSkillValidatorScript = env.runValidator,
        ),
    )

    println("Manual AI Workflow Conversation")
    println("sessionId=${env.sessionId}")
    println("profile=${env.profile.profileName}")
    println("clarificationCap=${env.clarificationCap}")
    println("skillRoot=${env.skillRoot}")
    println("runValidator=${env.runValidator}")
    if (env.outputFile != null) {
        println("outputFile=${env.outputFile}")
    }
    println("Type '/exit' to quit. Type '/settings' to print runtime settings.")
    println()

    while (true) {
        print("you> ")
        val input = readLine()?.trim()
        if (input.isNullOrBlank()) {
            continue
        }
        if (input == "/exit") {
            println("Exiting.")
            return@runBlocking
        }
        if (input == "/settings") {
            println(env)
            continue
        }

        val result = engine.processTurn(
            TurnRequest(
                sessionId = env.sessionId,
                userMessage = input,
                profile = env.profile,
                clarificationCap = env.clarificationCap,
            )
        )

        when (result) {
            is TurnResult.ClarificationRequired -> {
                println("assistant> [clarification:${result.question.focus}] ${result.question.question}")
            }

            is TurnResult.ConfirmationRequired -> {
                println("assistant> [confirmation required]")
                println("  purpose: ${result.snapshot.workflowPurposeSummary}")
                println("  input: ${result.snapshot.inputContractSummary.humanSummary}")
                if (result.snapshot.assumptions.isNotEmpty()) {
                    println("  assumptions:")
                    result.snapshot.assumptions.forEach { println("    - ${it.statement}") }
                }
                println("  reply with 'yes' to confirm or provide corrections.")
            }

            is TurnResult.FinalWorkflow -> {
                println("assistant> [final workflow]")
                println("  confidence=${"%.2f".format(result.confidence)}")
                println("  needsConfirmation=${result.needsConfirmation}")
                println("  validation=${if (result.validationReport.valid) "valid" else "invalid"}")
                printValidationIssues(result.validationReport.issues)
                println()
                println("--- workflow.yaml ---")
                println(result.workflowYaml)
                println("--- end workflow.yaml ---")

                env.outputFile?.let { output ->
                    Files.createDirectories(output.parent)
                    Files.writeString(output, result.workflowYaml)
                    println("Saved YAML to $output")
                }
                println()
            }

            is TurnResult.Failure -> {
                println("assistant> [failure] ${result.message} (recoverable=${result.recoverable})")
            }
        }
    }
}

private fun printValidationIssues(
    issues: List<ValidationIssue>,
) {
    if (issues.isEmpty()) {
        println("  validation issues: none")
        return
    }
    println("  validation issues:")
    issues.forEach { issue ->
        val path = issue.path?.let { " ($it)" } ?: ""
        println("    - ${issue.level}: ${issue.code}$path -> ${issue.message}")
    }
}

private data class ManualSettings(
    val sessionId: String,
    val profile: ValidationProfile,
    val clarificationCap: Int,
    val skillRoot: String,
    val runValidator: Boolean,
    val outputFile: Path?,
) {
    companion object {
        fun fromEnvironment(): ManualSettings {
            val sessionId = getenvOrDefault("LEMLINE_AI_SESSION_ID", "manual-session")
            val profile = parseProfile(getenvOrDefault("LEMLINE_AI_PROFILE", "spec-strict"))
            val clarificationCap = getenvOrDefault("LEMLINE_AI_CLARIFICATION_CAP", "8").toIntOrNull()?.coerceAtLeast(1) ?: 8
            val skillRoot = getenvOrDefault("LEMLINE_AI_SKILL_ROOT", ".claude/skills/serverless-workflow-writer")
            val runValidator = parseBoolean(getenvOrDefault("LEMLINE_AI_RUN_VALIDATOR", "true"))
            val outputFile = System.getenv("LEMLINE_AI_OUTPUT_FILE")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
            return ManualSettings(
                sessionId = sessionId,
                profile = profile,
                clarificationCap = clarificationCap,
                skillRoot = skillRoot,
                runValidator = runValidator,
                outputFile = outputFile,
            )
        }

        private fun parseProfile(value: String): ValidationProfile = when (value.lowercase()) {
            "spec-strict" -> ValidationProfile.SPEC_STRICT
            "lemline-compatible" -> ValidationProfile.LEMLINE_COMPATIBLE
            else -> ValidationProfile.SPEC_STRICT
        }

        private fun parseBoolean(value: String): Boolean = when (value.lowercase()) {
            "1", "true", "yes", "y", "on" -> true
            else -> false
        }

        private fun getenvOrDefault(
            key: String,
            defaultValue: String,
        ): String = System.getenv(key)?.takeIf { it.isNotBlank() } ?: defaultValue
    }
}

private class ManualInMemoryStateStore : ConversationStateStore {
    private val states = linkedMapOf<String, ConversationState>()

    override suspend fun load(sessionId: String): ConversationState? = states[sessionId]

    override suspend fun save(state: ConversationState): ConversationState {
        states[state.sessionId] = state
        return state
    }

    override suspend fun delete(sessionId: String) {
        states.remove(sessionId)
    }
}
