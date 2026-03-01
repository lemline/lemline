---
title: AI Workflow Conversation Library
---

# AI Workflow Conversation Library

This reference describes the headless Kotlin/JVM library that builds valid Serverless Workflow `1.0.0` definitions from a multi-turn conversation.

The library is channel-agnostic: it can run behind WhatsApp, web chat, Slack, or any interface that can exchange text turns.

## What It Solves

The library manages a conversation loop where:

1. The user expresses intent in natural language.
2. The engine asks clarification questions.
3. The engine resolves an explicit input contract (provided or inferred).
4. The engine requires a confirmation snapshot.
5. The engine generates a workflow YAML and returns validation results and assumptions.

## Modules

The implementation is split into five Gradle modules:

| Module | Responsibility |
|---|---|
| `:lemline-ai-workflow-common` | Public models and store interfaces |
| `:lemline-ai-workflow-core` | Conversation engine, inference, generation, validation |
| `:lemline-ai-workflow-llm-langchain4j` | Optional LangChain4j model bridge |
| `:lemline-ai-workflow-store-inmemory` | In-memory state store |
| `:lemline-ai-workflow-store-postgres` | PostgreSQL state store with JSONB payload |

## Public Conversation API

The public API is turn-based.

Core request:

```kotlin
TurnRequest(
    sessionId = "chat-session-123",
    userMessage = "Build an order workflow with approval and API calls",
    profile = ValidationProfile.SPEC_STRICT,
    clarificationCap = 8,
)
```

Possible outputs (`TurnResult`):

- `ClarificationRequired`
- `ConfirmationRequired`
- `FinalWorkflow`
- `Failure`

## Input Contract Intelligence

The engine always resolves an explicit input contract.

Returned model:

```kotlin
InputContractSummary(
    fields = listOf(...),
    requiredFields = listOf(...),
    format = "json",
    source = PROVIDED | INFERRED | MIXED,
    confidence = 0.0..1.0,
    humanSummary = "..."
)
```

Rules:

1. If user input contract is clear, it is adopted.
2. If missing, a minimal required contract is inferred.
3. If ambiguous, focused input-contract clarifications are asked.
4. If clarification cap is reached, finalization can proceed with explicit assumptions and confidence downgrade.

## Confirmation Snapshot

Before final YAML generation, the engine emits `ConfirmationRequired` with:

1. workflow purpose summary
2. resolved input contract summary
3. explicit assumptions

This is mandatory in the normal flow.

## Final Output Contract

`FinalWorkflow` includes:

1. workflow YAML
2. validation report
3. assumptions
4. input contract summary
5. confidence
6. `needsConfirmation` flag for cap fallback cases

## YAML Generation Behavior

The core generator enforces:

1. `document` and `do` blocks
2. top-level `input` block
3. `input.schema` when confidence is sufficient
4. `input.from` mapping
5. explicit assumptions outside YAML payload when inputs are inferred

## Skill-Guided Authoring and Validation

The core module loads and uses the local skill:

`.claude/skills/serverless-workflow-writer/SKILL.md`

Key references used by implementation:

- `references/02-data-and-expressions.md`
- `references/11-authoring-checklist.md`
- `references/13-semantic-constraints.md`
- `references/15-generate-from-prompt.md`

Validator integration:

- `scripts/validate_workflow.py`

## Validation Pipeline

Validation combines:

1. Skill script validation (`spec-strict` or `lemline-compatible` profile)
2. Input-focused semantic checks:
   - `input` block presence
   - `input.schema` expectation when confidence is high
   - warnings for undeclared input path usage
   - inferred required fields must appear in assumptions

## Persistence

State is persisted per `sessionId` using `ConversationStateStore`.

In-memory store:

- `InMemoryConversationStateStore`

PostgreSQL store:

- `PostgresConversationStateStore`
- table: `lemline_ai_workflow_conversations`
- tracks `revision` and `parent_revision_id` for linear history with branch-ready metadata

## Example Integration (Any Chat Surface)

```kotlin
val engine = WorkflowConversationEngine(
    stateStore = InMemoryConversationStateStore()
)

val response = engine.processTurn(
    TurnRequest(
        sessionId = sessionIdFromYourChannel,
        userMessage = incomingTextFromUser
    )
)

when (response) {
    is TurnResult.ClarificationRequired -> sendText(response.question.question)
    is TurnResult.ConfirmationRequired -> sendText(
        "Please confirm:\n" +
            "- Purpose: ${response.snapshot.workflowPurposeSummary}\n" +
            "- Input: ${response.snapshot.inputContractSummary.humanSummary}\n" +
            "- Assumptions: ${response.snapshot.assumptions.joinToString { it.statement }}"
    )
    is TurnResult.FinalWorkflow -> persistWorkflowYaml(response.workflowYaml)
    is TurnResult.Failure -> sendText("Unable to continue: ${response.message}")
}
```

## Suggested Defaults

Recommended defaults:

1. `ValidationProfile.SPEC_STRICT`
2. clarification cap `8`
3. conservative input inference (minimal required fields first)
4. explicit confirmation before final output

## Related Code

- `lemline-ai-workflow-common/src/main/kotlin/com/lemline/ai/workflow/common/model/ConversationModels.kt`
- `lemline-ai-workflow-common/src/main/kotlin/com/lemline/ai/workflow/common/model/ConversationState.kt`
- `lemline-ai-workflow-core/src/main/kotlin/com/lemline/ai/workflow/core/engine/WorkflowConversationEngine.kt`
- `lemline-ai-workflow-core/src/main/kotlin/com/lemline/ai/workflow/core/inference/InputContractInferer.kt`
- `lemline-ai-workflow-core/src/main/kotlin/com/lemline/ai/workflow/core/validation/WorkflowValidationPipeline.kt`
- `lemline-ai-workflow-store-postgres/src/main/kotlin/com/lemline/ai/workflow/store/postgres/PostgresConversationStateStore.kt`
