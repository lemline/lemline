# CompleteOrchestrator Tests

Tests for **CompleteOrchestrator** - the workflow orchestrator that executes workflows from start to finish without pausing.

## What is CompleteOrchestrator?

`CompleteOrchestrator` executes workflows synchronously and completely:
- ✅ **Activities execute fully**: Real HTTP calls, shell commands, scripts run to completion
- ✅ **Delays actually wait**: Uses `kotlinx.coroutines.delay()` to wait the specified duration
- ✅ **Sub-workflows execute inline**: Child workflows run recursively (await=true) or fire-and-forget (await=false)
- ✅ **Returns final output**: Returns `JsonElement` with the workflow's final output
- ✅ **Single-node deployment**: Ideal for testing and single-machine execution

## Test Files

### Core Orchestrator Tests
- **`CompleteOrchestratorTest.kt`** (17 tests) - Comprehensive orchestrator behavior tests

### Activity Tests
- **`CallHttpExecutionTest.kt`** - HTTP/REST API calls (GET, POST, PUT, DELETE)
- **`RunShellExecutionTest.kt`** - Shell command execution
- **`RunScriptExecutionTest.kt`** - Script execution (JavaScript, Python)
- **`RunWorkflowExecutionTest.kt`** - Sub-workflow execution (await & fire-and-forget)

### Control Flow Tests
- **`DoTaskExecutionTest.kt`** - Sequential task execution
- **`ForTaskExecutionTest.kt`** - Loop execution
- **`SwitchTaskExecutionTest.kt`** - Switch/case branching
- **`IfConditionExecutionTest.kt`** - Conditional execution

### State Management Tests
- **`SetTaskExecutionTest.kt`** - Variable assignment and data flow
- **`ExportContextExecutionTest.kt`** - Context export and $WORKFLOW access

### Error Handling Tests
- **`TryTaskExecutionTest.kt`** - Try/catch/retry error handling

### Timing Tests
- **`WaitExecutionTest.kt`** - Wait/delay execution (actually waits)

## Running Tests

```bash
# Run all complete orchestrator tests
./gradlew :lemline-core:test --tests "com.lemline.core.execution.complete.*"

# Run specific test category
./gradlew :lemline-core:test --tests "com.lemline.core.execution.complete.CallHttpExecutionTest"
./gradlew :lemline-core:test --tests "com.lemline.core.execution.complete.RunWorkflowExecutionTest"

# Run comprehensive orchestrator tests only
./gradlew :lemline-core:test --tests "com.lemline.core.execution.complete.CompleteOrchestratorTest"

# Run specific test
./gradlew :lemline-core:test --tests "com.lemline.core.execution.complete.CompleteOrchestratorTest.should execute HTTP call activity completely"
```

## Test Coverage

Total: **100+ tests** covering:
- ✅ All task types (Do, Set, For, Switch, If, Try, Raise)
- ✅ All activity types (HTTP, Shell, Script)
- ✅ Sub-workflow execution (both await modes)
- ✅ Error handling (try/catch/retry)
- ✅ Control flow (loops, conditionals, branching)
- ✅ Data flow and transformations
- ✅ State management and context
- ✅ Wait/delay behavior

## Dependencies

### External Services
- **HTTP tests**: JSONPlaceholder API (https://jsonplaceholder.typicode.com)
  - May occasionally fail due to network issues
  - Tests are marked as such in comments

### Runtime Requirements
- **Shell tests**: Require standard Unix shell commands (echo, etc.)
- **Script tests**: Require JavaScript and/or Python engines
  - May be disabled in some CI environments

### Sub-workflow Tests
- Use `DefinitionCache` to register child workflows
- Include `@AfterEach` cleanup to clear cache

## Key Characteristics

| Aspect | Behavior |
|--------|----------|
| **Execution Mode** | Synchronous, complete |
| **Return Type** | `JsonElement` (final output) |
| **Activities** | Execute to completion |
| **Delays** | Actually wait (blocking) |
| **Sub-workflows** | Execute inline recursively |
| **State** | Accumulated throughout execution |
| **Use Case** | Testing, single-node deployment |

## Comparison with PausableOrchestrator

For tests that verify **pause behavior**, see `../pausable/README.md`.

| Feature | CompleteOrchestrator | PausableOrchestrator |
|---------|---------------------|---------------------|
| Waits on delays | ✅ Yes | ❌ No (pauses) |
| Executes sub-workflows inline | ✅ Yes | ❌ No (pauses) |
| Returns on activity | ❌ No (continues) | ✅ Yes (pauses) |
| Best for | Testing, sync execution | Distributed execution |
