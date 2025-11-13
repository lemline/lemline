# Execution Tests

This directory contains tests for workflow execution orchestrators, organized into two distinct test suites:

## 📁 Directory Structure

```
execution/
├── complete/          # Tests for CompleteOrchestrator
│   ├── README.md
│   ├── CompleteOrchestratorTest.kt
│   ├── CallHttpExecutionTest.kt
│   ├── RunShellExecutionTest.kt
│   ├── RunScriptExecutionTest.kt
│   ├── RunWorkflowExecutionTest.kt
│   ├── WaitExecutionTest.kt
│   ├── DoTaskExecutionTest.kt
│   ├── SetTaskExecutionTest.kt
│   ├── ForTaskExecutionTest.kt
│   ├── SwitchTaskExecutionTest.kt
│   ├── IfConditionExecutionTest.kt
│   ├── TryTaskExecutionTest.kt
│   └── ExportContextExecutionTest.kt
│
└── pausable/          # Tests for PausableOrchestrator
    ├── README.md
    └── PausableOrchestratorTest.kt
```

## 🎯 Purpose

### Complete Orchestrator Tests (`complete/`)

Tests for **CompleteOrchestrator** which executes workflows from start to finish:

- Activities execute fully (real HTTP calls, shell commands, scripts)
- Delays actually wait using coroutines
- Sub-workflows execute inline recursively
- Returns final workflow output

### Pausable Orchestrator Tests (`pausable/`)

Tests for **PausableOrchestrator** which pauses at specific boundaries:

- Pauses after activities complete
- Pauses when delays are needed (instead of waiting)
- Pauses before sub-workflows
- Returns `PausableResult` indicating pause reason

## 🚀 Running Tests

```bash
# Run all execution tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.*"

# Run only complete orchestrator tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.continuous.*"

# Run only pausable orchestrator tests
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.byActivity.*"

# Run specific test file
./gradlew :lemline-core:test --tests "com.lemline.core.orchestrator.continuous.CallHttpExecutionTest"
```

## 📊 Key Differences

| Aspect          | CompleteOrchestrator | PausableOrchestrator  |
|-----------------|----------------------|-----------------------|
| **Location**    | `complete/`          | `pausable/`           |
| **Return Type** | `JsonElement`        | `PausableResult`      |
| **Execution**   | Runs to completion   | Pauses at boundaries  |
| **Use Case**    | Synchronous testing  | Distributed execution |
| **Test Count**  | ~100+ tests          | ~13 tests             |

## 📝 Notes

- Tests are isolated by folder to avoid confusion
- Each folder has its own README with specific details
- All tests use proper package declarations matching their location
- Tests can be run independently or together
