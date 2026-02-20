// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.random

import com.lemline.common.random.random
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.UntilCondition
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.DoState
import com.lemline.core.states.ForState
import com.lemline.core.states.ForkState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.NodeState
import com.lemline.core.states.RaiseState
import com.lemline.core.states.RootState
import com.lemline.core.states.RunState
import com.lemline.core.states.SetState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.SwitchState
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryState
import com.lemline.core.states.WaitState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.tasks.FlowDirective
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.core.tasks.FlowDirectiveGoto
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

fun JsonElement.Companion.random(): JsonElement {
    return when (Random.nextInt(5)) {
        0 -> JsonPrimitive(Random.nextBoolean())
        1 -> JsonPrimitive(Random.nextInt())
        2 -> JsonPrimitive(Random.nextDouble())
        3 -> JsonPrimitive(String.random())
        else -> when (Random.nextBoolean()) {
            true -> buildJsonArray {
                repeat(Random.nextInt(1, 4)) {
                    add(JsonElement.random())
                }
            }

            false -> buildJsonObject {
                repeat(Random.nextInt(1, 4)) {
                    put(String.random(), JsonElement.random())
                }
            }
        }
    }
}

fun JsonElement.Companion.nullableRandom(): JsonElement {
    return when (Random.nextBoolean()) {
        true -> JsonNull
        false -> random()
    }
}

fun NodePosition.Companion.random() = NodePosition("/${String.random()}/${String.random()}/${String.random()}")

fun WorkflowInfo.Companion.random() = WorkflowInfo(
    namespace = WorkflowNamespace.random(),
    name = WorkflowName.random(),
    version = WorkflowVersion.random(),
)

fun randomNodeState() = when (Random.nextInt(12)) {
    0 -> randomTaskState()
    1 -> randomDoState()
    2 -> randomTaskState()
    3 -> randomForState()
    4 -> randomTaskState()
    5 -> randomRootState()
    6 -> randomTaskState()
    7 -> randomTaskState()
    8 -> randomTaskState()
    9 -> randomTryState()
    10 -> randomTaskState()
    else -> randomRootState()
}

fun randomTaskState() = TaskState(
    startedAt = Instant.random(),
)

fun randomDoState() = DoState(
    startedAt = Instant.random(),
    index = Random.nextInt(),
)

fun randomForState() = ForState(
    startedAt = Instant.random(),
    collection = listOf(JsonElement.random()),
    index = Random.nextInt(),
    forEach = String.random(),
    forAt = String.random()
)

fun randomRootState() = RootState(
    startedAt = Instant.random(),
    workflowId = WorkflowId.random(),
    workflowInput = JsonElement.random(),
    context = buildJsonObject {
        repeat(Random.nextInt(0, 4)) {
            put(String.random(), JsonElement.random())
        }
    },
    hasWaitingParent = Random.nextBoolean()
)

fun randomTryState() = TryState(
    startedAt = Instant.random(),
    transformedInput = JsonElement.random(),
    attemptIndex = Random.nextInt(0, 10),
    runningCatch = Random.nextBoolean(),
    lastError = when (Random.nextBoolean()) {
        true -> InternalException.Error.random()
        false -> null
    },
    errorAs = String.random(),
)

fun InternalException.Error.Companion.random() = InternalException.Error(
    type = String.random(),
    status = Random.nextInt(400, 600),
    position = String.random(),
    title = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
    details = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
)

// Random generators for orchestrator.WorkflowState

fun randomFlowDirective(): FlowDirective = when (Random.nextInt(4)) {
    0 -> FlowDirectiveEnum.Continue
    1 -> FlowDirectiveEnum.Exit
    2 -> FlowDirectiveEnum.End
    else -> FlowDirectiveGoto(String.random())
}

fun randomRunWorkflowConfig() = RunWorkflowConfig(
    namespace = WorkflowNamespace.random(),
    name = WorkflowName.random(),
    version = WorkflowVersion.random(),
    input = JsonElement.random(),
    sync = Random.nextBoolean()
)

fun randomWorkflowState(): WorkflowState = when (Random.nextBoolean()) {
    true -> randomWorkflowEvent()
    false -> randomWorkflowCommand()
}

fun randomWorkflowCommand(): WorkflowCommand {
    return when (Random.nextInt(2)) {
        0 -> randomResumeFromTaskCommand()
        else -> randomResumeWithCompletedTaskCommand()
    }
}

fun randomResumeFromTaskCommand() = WorkflowCommand.ResumeFromTask(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = randomFlowDirective(),
)

fun randomResumeWithCompletedTaskCommand() = WorkflowCommand.ResumeWithCompletedTask(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
)

fun randomWorkflowEvent(): WorkflowEvent {
    return when (Random.nextInt(9)) {
        0 -> randomWorkflowCompletedEvent()
        1 -> randomWorkflowFailedEvent()
        2 -> randomTaskScheduledEvent()
        3 -> randomWaitStartedEvent()
        4 -> randomTaskRetryScheduledEvent()
        5 -> randomRunWorkflowStartedEvent()
        6 -> randomForkStartedEvent()
        7 -> randomForkBranchCompletedEvent()
        else -> randomListenStartedEvent()
    }
}

fun NodeStack.Companion.random(): NodeStack {
    // Build a hierarchical stack where each frame is a child of the previous
    val rootPosition = NodePosition.root
    val childPosition = rootPosition.addName(String.random())
    val grandchildPosition = childPosition.addName(String.random())

    return NodeStack.fromFrames(
        listOf(
            StackFrame(rootPosition, randomRootState()),
            StackFrame(childPosition, randomNodeState()),
            StackFrame(grandchildPosition, randomNodeState())
        )
    )
}

fun randomWorkflowCompletedEvent() = WorkflowEvent.WorkflowCompleted(
    output = JsonElement.random(),
    completedAt = Clock.System.now(),
    nodeStack = NodeStack.random()
)

fun randomWorkflowFailedEvent() = WorkflowEvent.WorkflowFailed(
    nodeStack = NodeStack.random(),
    rawInput = when (Random.nextBoolean()) {
        true -> JsonElement.random()
        false -> null
    },
    rawOutput = when (Random.nextBoolean()) {
        true -> JsonElement.random()
        false -> null
    },
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    },
    error = InternalException.Error.random(),
    failedAt = Instant.random()
)

fun randomTaskScheduledEvent() = WorkflowEvent.TaskScheduled(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    }
)

fun randomWaitConfig() = WaitConfig(
    waitUntil = Clock.System.now() + Random.nextLong(100, 10000).milliseconds
)

fun randomWaitStartedEvent() = WorkflowEvent.WaitStarted(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
    config = randomWaitConfig()
)

fun randomTaskRetryScheduledEvent() = WorkflowEvent.TaskRetryScheduled(
    nodeStack = NodeStack.random(),
    nodePosition = NodePosition.random(),
    rawInput = JsonElement.random(),
    flowDirective = when (Random.nextBoolean()) {
        true -> randomFlowDirective()
        false -> null
    },
    retryAt = Clock.System.now() + Random.nextLong(100, 10000).milliseconds
)

fun randomRunWorkflowStartedEvent() = WorkflowEvent.RunWorkflowStarted(
    nodeStack = NodeStack.random(),
    rawInput = JsonElement.random(),
    config = randomRunWorkflowConfig()
)

fun randomForkStartedEvent() = WorkflowEvent.ForkStarted(
    nodeStack = NodeStack.random(),
    rawInput = JsonElement.random(),
)

fun randomForkBranchCompletedEvent() =
    WorkflowEvent.ForkBranchCompleted(
        nodeStack = NodeStack.random(),
        branchPosition = NodePosition.random(),
        branchOutput = JsonElement.random(),
        completedAt = Clock.System.now(),
    )

// Listen task random generators

fun randomListenStrategy(): ListenStrategy = ListenStrategy.entries.random()

fun randomListenAndReadAs(): ListenAndReadAs = ListenAndReadAs.entries.toTypedArray().random()

fun randomCorrelationDef() = CorrelationDef(
    from = "\${ .${String.random()} }",
    expect = when (Random.nextBoolean()) {
        true -> "\${ \$input.${String.random()} }"
        false -> null
    }
)

fun randomEventFilter() = EventFilter(
    type = when (Random.nextBoolean()) {
        true -> "com.example.${String.random()}"
        false -> null
    },
    source = when (Random.nextBoolean()) {
        true -> "https://example.com/${String.random()}"
        false -> null
    },
    subject = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
    id = when (Random.nextBoolean()) {
        true -> String.random()
        false -> null
    },
    datacontenttype = when (Random.nextBoolean()) {
        true -> "application/json"
        false -> null
    },
    dataschema = when (Random.nextBoolean()) {
        true -> "https://schema.example.com/${String.random()}"
        false -> null
    },
    time = when (Random.nextBoolean()) {
        true -> Clock.System.now().toString()
        false -> null
    },
    dataFilter = when (Random.nextBoolean()) {
        true -> ".${String.random()} == true"
        false -> null
    },
    correlations = when (Random.nextBoolean()) {
        true -> mapOf(String.random() to randomCorrelationDef())
        false -> null
    }
)

fun randomUntilCondition(): UntilCondition = when (Random.nextBoolean()) {
    true -> UntilCondition.Expression(". | length >= ${Random.nextInt(1, 10)}")
    false -> UntilCondition.Event(randomEventFilter())
}

fun randomListenConfig() = ListenConfig(
    strategy = randomListenStrategy(),
    filters = List(Random.nextInt(1, 4)) { randomEventFilter() },
    until = when (Random.nextBoolean()) {
        true -> randomUntilCondition()
        false -> null
    },
    readAs = randomListenAndReadAs(),
    timeoutAt = when (Random.nextBoolean()) {
        true -> Clock.System.now() + Random.nextLong(100, 10000).milliseconds
        false -> null
    },
    correlationContext = when (Random.nextBoolean()) {
        true -> buildJsonObject {
            put("input", JsonElement.random())
            put("context", buildJsonObject { put(String.random(), JsonElement.random()) })
        }

        false -> null
    }
)

fun randomListenStartedEvent() = WorkflowEvent.ListenStarted(
    nodeStack = NodeStack.random(),
    rawOutput = JsonElement.random(),
    config = randomListenConfig()
)
