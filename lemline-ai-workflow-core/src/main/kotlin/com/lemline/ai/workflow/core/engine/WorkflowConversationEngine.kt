// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.engine

import com.lemline.ai.workflow.common.model.ChatMessage
import com.lemline.ai.workflow.common.model.ConfirmationSnapshot
import com.lemline.ai.workflow.common.model.ConversationState
import com.lemline.ai.workflow.common.model.ConversationStatus
import com.lemline.ai.workflow.common.model.InputContractSource
import com.lemline.ai.workflow.common.model.MessageRole
import com.lemline.ai.workflow.common.model.TurnRequest
import com.lemline.ai.workflow.common.model.TurnResult
import com.lemline.ai.workflow.common.model.ValidationLevel
import com.lemline.ai.workflow.common.model.WorkflowAssumption
import com.lemline.ai.workflow.common.store.ConversationStateStore
import com.lemline.ai.workflow.core.config.WorkflowConversationConfig
import com.lemline.ai.workflow.core.generation.SkillBackedWorkflowGenerator
import com.lemline.ai.workflow.core.inference.InputContractInferer
import com.lemline.ai.workflow.core.inference.IntentExtractor
import com.lemline.ai.workflow.core.llm.WorkflowAuthoringModel
import com.lemline.ai.workflow.core.validation.WorkflowValidationPipeline
import java.time.Instant

class WorkflowConversationEngine(
    private val stateStore: ConversationStateStore,
    authoringModel: WorkflowAuthoringModel? = null,
    private val config: WorkflowConversationConfig = WorkflowConversationConfig(),
) {
    private val intentExtractor = IntentExtractor()
    private val inputContractInferer = InputContractInferer()
    private val clarificationPlanner = ClarificationPlanner()
    private val confirmationDetector = ConfirmationDetector()
    private val workflowGenerator = SkillBackedWorkflowGenerator(
        skillRootPath = config.skillRootPath,
        authoringModel = authoringModel,
    )
    private val validationPipeline = WorkflowValidationPipeline(
        runSkillScript = config.runSkillValidatorScript,
        skillRootPath = config.skillRootPath,
    )

    suspend fun processTurn(request: TurnRequest): TurnResult {
        return try {
            val now = Instant.now().toString()
            val cap = request.clarificationCap.coerceAtMost(config.hardClarificationCeiling)
            var state = stateStore.load(request.sessionId) ?: ConversationState(
                sessionId = request.sessionId,
                createdAt = now,
                updatedAt = now,
                targetProfile = request.profile,
            )
            val isConfirmationReply = state.status == ConversationStatus.CONFIRMATION_REQUIRED &&
                confirmationDetector.isAffirmative(request.userMessage)

            state = addUserTurn(
                state = state.copy(targetProfile = request.profile),
                message = request.userMessage,
                now = now,
            )

            if (isConfirmationReply) {
                return finalize(state, needsConfirmation = false)
            }

            val extractedIntent = intentExtractor.extract(state.intent, request.userMessage)
            val inputInference = inputContractInferer.infer(
                previous = state.inputContractSummary,
                intent = extractedIntent,
                userMessage = request.userMessage,
                minimumConfidence = config.minimumInputContractConfidence,
            )
            state = state.copy(
                intent = extractedIntent.copy(
                    unresolvedCriticalAmbiguities = maxOf(
                        extractedIntent.unresolvedCriticalAmbiguities,
                        inputInference.unresolvedCriticalAmbiguities,
                    )
                ),
                inputContractSummary = inputInference.summary,
                assumptions = mergeAssumptions(state.assumptions, inputInference.assumptions),
                confidence = computeConversationConfidence(extractedIntent.unresolvedCriticalAmbiguities, inputInference.summary.confidence),
            )

            val forceInputClarification = state.inputContractSummary.source == InputContractSource.INFERRED &&
                state.clarificationCount == 0
            if (forceInputClarification && !capReached(state, cap)) {
                val firstQuestion = clarificationPlanner.nextQuestion(state)
                if (firstQuestion != null) {
                    state = state.copy(
                        clarificationCount = state.clarificationCount + 1,
                        status = ConversationStatus.COLLECTING,
                    )
                    save(state, now)
                    return TurnResult.ClarificationRequired(
                        question = firstQuestion,
                        turnCount = state.turnCount,
                    )
                }
            }

            val readyForConfirmation = readinessGate(state, inputInference.complete)
            val capReached = capReached(state, cap)

            if (!readyForConfirmation && !capReached) {
                val question = clarificationPlanner.nextQuestion(state)
                if (question != null) {
                    state = state.copy(
                        clarificationCount = state.clarificationCount + 1,
                        status = ConversationStatus.COLLECTING,
                    )
                    save(state, now)
                    return TurnResult.ClarificationRequired(
                        question = question,
                        turnCount = state.turnCount,
                    )
                }
            }

            if (!capReached) {
                val confirmation = buildConfirmationSnapshot(state)
                state = state.copy(
                    status = ConversationStatus.CONFIRMATION_REQUIRED,
                    confirmationSnapshot = confirmation,
                )
                save(state, now)
                return TurnResult.ConfirmationRequired(
                    snapshot = confirmation,
                    turnCount = state.turnCount,
                )
            }

            return finalize(state, needsConfirmation = true)
        } catch (exception: Exception) {
            TurnResult.Failure(
                message = "Failed to process turn: ${exception.message}",
                recoverable = true,
            )
        }
    }

    private suspend fun finalize(
        state: ConversationState,
        needsConfirmation: Boolean,
    ): TurnResult.FinalWorkflow {
        val now = Instant.now().toString()
        val yaml = workflowGenerator.generate(state)
        val report = validationPipeline.validate(
            yaml = yaml,
            profile = state.targetProfile,
            inputContractSummary = state.inputContractSummary,
            assumptions = state.assumptions,
        )
        val adjustedConfidence = if (report.issues.any { it.level == ValidationLevel.ERROR }) {
            (state.confidence - 0.20).coerceIn(0.0, 1.0)
        } else {
            state.confidence.coerceIn(0.0, 1.0)
        }
        val finalizedState = state.copy(
            status = ConversationStatus.FINALIZED,
            finalWorkflowYaml = yaml,
            finalValidation = report,
            confidence = adjustedConfidence,
        )
        save(finalizedState, now)
        return TurnResult.FinalWorkflow(
            workflowYaml = yaml,
            validationReport = report,
            assumptions = finalizedState.assumptions,
            inputContractSummary = finalizedState.inputContractSummary,
            confidence = adjustedConfidence,
            needsConfirmation = needsConfirmation,
        )
    }

    private fun readinessGate(
        state: ConversationState,
        inputContractComplete: Boolean,
    ): Boolean {
        val baseIntentComplete = state.intent.purpose != null &&
            (state.intent.requiresHttpCall || state.intent.requiresLoop || state.intent.requiresCondition || state.intent.requiresEventWait)
        val inputAcceptable = inputContractComplete || state.inputContractSummary.requiredFields.isNotEmpty()
        return baseIntentComplete &&
            inputAcceptable &&
            state.intent.unresolvedCriticalAmbiguities == 0
    }

    private fun buildConfirmationSnapshot(state: ConversationState): ConfirmationSnapshot {
        val purpose = state.intent.purpose ?: "Workflow purpose inferred from conversation."
        val assumptions = mergeAssumptions(
            state.assumptions,
            listOf(
                WorkflowAssumption(
                    id = "summary-input-source",
                    statement = "Input contract source is ${state.inputContractSummary.source.name.lowercase()}.",
                )
            ),
        )
        return ConfirmationSnapshot(
            workflowPurposeSummary = purpose,
            inputContractSummary = state.inputContractSummary,
            assumptions = assumptions,
            needsConfirmation = true,
        )
    }

    private fun mergeAssumptions(
        existing: List<WorkflowAssumption>,
        incoming: List<WorkflowAssumption>,
    ): List<WorkflowAssumption> = (existing + incoming)
        .distinctBy { it.id }

    private fun addUserTurn(
        state: ConversationState,
        message: String,
        now: String,
    ): ConversationState = state.copy(
        turnCount = state.turnCount + 1,
        updatedAt = now,
        messages = state.messages + ChatMessage(
            role = MessageRole.USER,
            content = message,
            timestamp = now,
        ),
    )

    private suspend fun save(
        state: ConversationState,
        now: String,
    ): ConversationState {
        val nextRevision = state.revision + 1
        val updated = state.copy(
            revision = nextRevision,
            parentRevisionId = state.revision.takeIf { it > 0 },
            updatedAt = now,
        )
        return stateStore.save(updated)
    }

    private fun computeConversationConfidence(
        intentAmbiguities: Int,
        inputConfidence: Double,
    ): Double {
        val penalty = intentAmbiguities * 0.07
        return (inputConfidence - penalty).coerceIn(0.0, 1.0)
    }

    private fun capReached(
        state: ConversationState,
        cap: Int,
    ): Boolean = state.clarificationCount >= cap
}
