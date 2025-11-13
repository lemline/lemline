# Orchestrator Tests

This directory contains tests for the unified **WorkflowOrchestrator** with different execution modes.

## 📁 Directory Structure

```
orchestrator/
├── bases/             # Shared base classes for all test suites
│   ├── AbstractExecutionTest.kt
│   ├── AbstractOrchestratorTest.kt
│   ├── CallHttpExecutionTest.kt
│   ├── DoTaskExecutionTest.kt
│   ├── ExportContextExecutionTest.kt
│   ├── ForTaskExecutionTest.kt
│   ├── IfConditionExecutionTest.kt
│   ├── RunScriptExecutionTest.kt
│   ├── RunShellExecutionTest.kt
│   ├── RunWorkflowExecutionTest.kt
│   ├── SetTaskExecutionTest.kt
│   ├── SwitchTaskExecutionTest.kt
│   ├── TryTaskExecutionTest.kt
│   └── WaitExecutionTest.kt
│
├── continuous/        # Tests for ExecutionMode.CONTINUOUS
│   ├── ContinuousOrchestratorTest.kt
│   ├── ContinuousCallHttpExecutionTest.kt
│   ├── ContinuousRunShellExecutionTest.kt
│   ├── ContinuousRunScriptExecutionTest.kt
│   ├── ContinuousRunWorkflowExecutionTest.kt
│   ├── ContinuousWaitExecutionTest.kt
│   ├── ContinuousDoTaskExecutionTest.kt
│   ├── ContinuousSetTaskExecutionTest.kt
│   ├── ContinuousForTaskExecutionTest.kt
│   ├── ContinuousSwitchTaskExecutionTest.kt
│   ├── ContinuousIfConditionExecutionTest.kt
│   ├── ContinuousTryTaskExecutionTest.kt
│   └── ContinuousExportContextExecutionTest.kt
│
├── byActivity/        # Tests for ExecutionMode.BY_ACTIVITY
│   ├── ByActivityOrchestratorTest.kt
│   ├── ByActivityCallHttpExecutionTest.kt
│   ├── ByActivityRunShellExecutionTest.kt
│   ├── ByActivityRunScriptExecutionTest.kt
│   ├── ByActivityRunWorkflowExecutionTest.kt
│   ├── ByActivityWaitExecutionTest.kt
│   ├── ByActivityDoTaskExecutionTest.kt
│   ├── ByActivitySetTaskExecutionTest.kt
│   ├── ByActivityForTaskExecutionTest.kt
│   ├── ByActivitySwitchTaskExecutionTest.kt
│   ├── ByActivityIfConditionExecutionTest.kt
│   ├── ByActivityTryTaskExecutionTest.kt
│   └── ByActivityExportContextExecutionTest.kt
│
└── byTask/            # Tests for ExecutionMode.BY_TASK
    ├── ByTaskOrchestratorTest.kt
    ├── ByTaskCallHttpExecutionTest.kt
    ├── ByTaskRunShellExecutionTest.kt
    ├── ByTaskRunScriptExecutionTest.kt
    ├── ByTaskRunWorkflowExecutionTest.kt
    ├── ByTaskWaitExecutionTest.kt
    ├── ByTaskDoTaskExecutionTest.kt
    ├── ByTaskSetTaskExecutionTest.kt
    ├── ByTaskForTaskExecutionTest.kt
    ├── ByTaskSwitchTaskExecutionTest.kt
    ├── ByTaskIfConditionExecutionTest.kt
    ├── ByTaskTryTaskExecutionTest.kt
    └── ByTaskExportContextExecutionTest.kt
```

## 🎯 Purpose

All tests use the unified **WorkflowOrchestrator** with different **ExecutionMode** settings:

### Continuous Mode Tests (`continuous/`)

Tests for **ExecutionMode.CONTINUOUS** which executes workflows from start to finish:

- Activities execute fully (real HTTP calls, shell commands, scripts)
- Delays actually wait using coroutines
- Sub-workflows execute inline recursively
- Returns final workflow output in `WorkflowResult.Completed`

### By Activity Mode Tests (`byActivity/`)

Tests for **ExecutionMode.BY_ACTIVITY** which pauses at activity boundaries:

- Pauses after activities complete
- Pauses when delays are needed (instead of waiting)
- Pauses before sub-workflows start
- Returns `WorkflowResult.Paused` with pause reason

### By Task Mode Tests (`byTask/`)

Tests for **ExecutionMode.BY_TASK** which pauses at every task step:

- Pauses after each individual task completes
- Provides finest-grained control over workflow execution
- Useful for debugging and step-by-step execution
- Returns `WorkflowResult.Paused` with current position

## 🚀 Running Tests

```bash
# Run all orchestrator tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.*"

# Run only continuous mode tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.continuous.*"

# Run only by-activity mode tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.byActivity.*"

# Run only by-task mode tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.byTask.*"

# Run specific test file
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.continuous.ContinuousCallHttpExecutionTest"
```

## 📊 Key Differences

| Aspect              | CONTINUOUS           | BY_ACTIVITY           | BY_TASK               |
|---------------------|----------------------|-----------------------|-----------------------|
| **Location**        | `continuous/`        | `byActivity/`         | `byTask/`             |
| **Execution Mode**  | `CONTINUOUS`         | `BY_ACTIVITY`         | `BY_TASK`             |
| **Return Type**     | `WorkflowResult.Completed` | `WorkflowResult.Paused` | `WorkflowResult.Paused` |
| **Pauses**          | Never                | At activity boundaries | After every task      |
| **Granularity**     | Full workflow        | Activity-level        | Task-level            |
| **Use Case**        | Complete execution   | Distributed activities| Step-by-step debugging|
| **Test Count**      | ~13 test files       | ~13 test files        | ~13 test files        |

## 📝 Notes

- All tests use the unified **WorkflowOrchestrator** with different `ExecutionMode` configurations
- The `bases/` folder contains abstract base classes shared across all test suites
- Tests are isolated by execution mode to clearly demonstrate different behaviors
- Each test suite validates the same workflow features but with different pause semantics
- Tests can be run independently or together
