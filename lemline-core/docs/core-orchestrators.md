# Orchestrators

## Overview

Orchestrators execute workflows by processing commands and returning events. Two implementations for different use cases.

## Key Files

| File | Purpose |
|------|---------|
| `orchestrator/StepByStepOrchestrator.kt` | Production: one step at a time |
| `orchestrator/FullOrchestrator.kt` | Testing: run to completion |
| `orchestrator/WorkflowState.kt` | Commands and events |

---

## StepByStepOrchestrator

Executes **one step at a time**, returning control after each task.

### Key Methods

```kotlin
class StepByStepOrchestrator {
    fun initCmd(workflow, input, workflowId): WorkflowCommand.ResumeFromTask
    suspend fun runByTask(workflow, command: WorkflowCommand): WorkflowEvent
    suspend fun runByActivity(workflow, command: WorkflowCommand): WorkflowEvent
}
```

### Execution Flow

```
WorkflowCommand
    │
    ▼
resumeFromTask()
  - Check if condition
  - Transform input
  - Call processor.getNextStepInfo()
    │
    ├── AsyncTaskException → WaitStarted/ForkStarted/RunWorkflowStarted
    │
    └── NextStepInfo → completeTask()
                         - Transform output
                         - Export to context
                         - Navigate to next
                             │
                             └── TaskScheduled/WorkflowCompleted
```

### Returned Events

| Event | Next Action |
|-------|-------------|
| `TaskScheduled` | Send `ResumeFromTask` |
| `WaitStarted` | Schedule wake-up → `ResumeWithCompletedTask` |
| `RetryScheduled` | Schedule retry → `ResumeFromTask` |
| `RunWorkflowStarted` | Execute child → send result back |
| `ForkStarted` | Execute branches → collect results |
| `BranchCompleted` | Aggregate results |
| `BranchFailed` | Handle based on compete mode |
| `WorkflowCompleted` | Done |
| `WorkflowFailed` | Error |

---

## FullOrchestrator

Executes workflows **to completion**, handling all async internally.

```kotlin
class FullOrchestrator(
    private val activityRunner: ActivityRunner,
    private val definitionLoader: DefinitionLoader
) {
    suspend fun start(workflow, input, workflowId): JsonElement
    suspend fun resume(workflow, command): JsonElement
}
```

### Event Handling

```kotlin
private suspend fun handle(event: WorkflowEvent): JsonElement = when (event) {
    is TaskScheduled -> {
        val output = activityRunner.run(event.node, event.transformedInput)
        resume(workflow, ResumeWithCompletedTask(output))
    }
    is WaitStarted -> {
        delay(event.config.waitUntil - now)
        resume(workflow, ResumeWithCompletedTask(event.transformedInput))
    }
    is ForkStarted -> {
        val result = if (event.compete) executeCompete(branches) else executeCooperative(branches)
        resume(workflow, ResumeWithCompletedTask(result))
    }
    is WorkflowCompleted -> event.output
    is WorkflowFailed -> throw event.error
}
```

---

## Choosing an Orchestrator

| Use Case | Orchestrator |
|----------|--------------|
| Production runtime | StepByStepOrchestrator |
| Unit tests | FullOrchestrator |
| CLI tools | FullOrchestrator |
| Horizontal scaling | StepByStepOrchestrator |

---

## Usage Examples

### StepByStepOrchestrator

```kotlin
val orchestrator = StepByStepOrchestrator()
val command = orchestrator.initCmd(workflow, input, workflowId)

var event = orchestrator.runByTask(workflow, command)
while (event !is WorkflowCompleted && event !is WorkflowFailed) {
    event = when (event) {
        is TaskScheduled -> {
            val output = executeActivity(event)
            orchestrator.runByTask(workflow, ResumeWithCompletedTask(output, event))
        }
        is WaitStarted -> {
            scheduleWakeUp(event.config.waitUntil, event)
            break
        }
        // ... handle other events
    }
}
```

### FullOrchestrator

```kotlin
val orchestrator = FullOrchestrator(activityRunner, definitionLoader)
val result = orchestrator.start(workflow, input)
```

---

## Extending

### Custom Activity Runner

```kotlin
class CustomActivityRunner : ActivityRunner {
    override suspend fun run(node: Node<*>, input: JsonElement): JsonElement = when (node.task) {
        is CallHTTP -> executeHttp(node.task, input)
        is RunShell -> executeShell(node.task, input)
        else -> throw UnsupportedOperationException()
    }
}
```

### Custom Definition Loader

```kotlin
class DatabaseDefinitionLoader(private val repo: WorkflowRepository) : DefinitionLoader {
    override suspend fun load(config: RunWorkflowConfig): Workflow {
        return DefinitionCache.parse(repo.findByNameAndVersion(config.name, config.version))
    }
}
```

---

## Debugging

```kotlin
class TracingOrchestrator(private val delegate: StepByStepOrchestrator) {
    suspend fun runByTask(workflow, command): WorkflowEvent {
        logger.debug { "Command: $command" }
        val event = delegate.runByTask(workflow, command)
        logger.debug { "Event: $event" }
        return event
    }
}
```

### Common Issues

| Issue | Check |
|-------|-------|
| Infinite loop | FlowDirective handling |
| State lost | TaskStates passed correctly |
| Wrong event | Processor's NextStepInfo |
| Activity not executed | `isActivity()` returns true |
