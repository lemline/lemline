# Expression Evaluation

## Overview

Lemline uses JQ 1.6 for data transformation and condition evaluation. Expressions are evaluated within a hierarchical scope.

Workflow secrets declared under `use.secrets` are resolved from same-named environment variables by the runner for each command. They are injected as `$secrets` in the root scope and are available to all workflow expressions, including task definitions and output expressions. A missing declared secret faults the workflow with an `authorization` error (HTTP status 403). Resolved secret values are not included in the serialized root state; a workflow expression can still explicitly copy one into task data, output, logs, or a call request. Authors should limit where they reference secrets.

## Key Files

| File | Purpose |
|------|---------|
| `expressions/JQExpression.kt` | JQ evaluator |
| `orchestrator/context/scope.kt` | Scope type |
| `orchestrator/context/TaskContext.kt` | Task context |

---

## Scope Variables

| Variable | Source | Description |
|----------|--------|-------------|
| `$workflow.id` | RootState | Workflow instance ID |
| `$workflow.input` | RootState | Original input |
| `$workflow.startedAt` | RootState | Start timestamp |
| `$context` | RootState | Exported data |
| `$task.name` | TaskContext | Task name |
| `$task.reference` | TaskContext | Position reference |
| `$input` | TaskContext | Transformed input |
| `$output` | TaskContext | Raw output (in output phase) |
| `$item` | ForState | Current iteration item |
| `$index` | ForState | Current iteration index |
| `$error` | TryState | Caught error (in catch) |

---

## Expression Usage

| Usage | DSL Field | Result |
|-------|-----------|------------|
| Input transform | `input.from` | Transformed input |
| Condition | `if` | Boolean |
| Output transform | `output.as` | Transformed output |
| Export | `export.as` | Object |
| Iteration | `for.in`, `while` | Array, boolean |
| Switch | `switch.when` | Boolean |
| Error filter | `catch.when` | Boolean |
| String interpolation | Expression-bearing string fields | Evaluated value |

The DSL defaults to strict evaluation: runtime expressions must use `${ ... }`, including `for.in`, `while`, and `switch.when`. Lemline still accepts bare JQ in some other fields as a legacy behavior, but workflow definitions should use the delimiters for portability.

### Runtime Expressions

```yaml
# Runtime expression (${} required)
endpoint: "https://api.example.com/users/${.userId}"

# Input transformation
input:
  from: '${ .user | {name, email} }'
```

---

## JQExpression API

```kotlin
object JQExpression : ExpressionEvaluator {
    // Evaluate with forced direct evaluation
    fun eval(data: JsonElement, expr: String, scope: Scope, force: Boolean): JsonElement

    // Boolean evaluation
    fun evalBoolean(data: JsonElement, expr: String, scope: Scope): Boolean

    // List evaluation
    fun evalList(data: JsonElement, expr: String, scope: Scope): List<JsonElement>
}
```

---

## Common JQ Patterns

```jq
.fieldName                          # Select field
.parent.child                       # Nested field
.items[0]                           # Array element
{name: .user.name, id: .user.id}    # Object construction
.items | map(.name)                 # Map over array
.items | map(select(.active))       # Filter array
if .x then .a else .b end           # Conditional
.items | length                     # Array length
.name // "default"                  # Default value
"Hello \(.name)"                    # String interpolation
.items | first                      # First element
.items | sort_by(.name)             # Sort array
```

---

## Scope Building

```kotlin
fun buildFullScope(
    taskStates: TaskStates,
    position: NodePosition,
    taskContext: TaskContext,
    node: Node<*>
): Scope {
    val rootState = taskStates[NodePosition.root] as RootState
    var scope = rootState.scope

    // Walk up merging state scopes
    var current: NodePosition? = position
    while (current != null && current != NodePosition.root) {
        taskStates[current]?.let { scope = scope.merge(it.scope) }
        current = current.parent
    }

    return scope.merge(taskContext.toScope(node))
}
```

---

## TaskContext

```kotlin
data class TaskContext(
    val startedAt: Instant,
    val rawInput: JsonElement?,
    val transformedInput: JsonElement?,
    val rawOutput: JsonElement?,
    val transformedOutput: JsonElement?
) {
    fun toScope(node: Node<*>): Scope = buildJsonObject {
        put("task", buildJsonObject {
            put("name", node.name)
            put("reference", node.reference)
        })
        transformedInput?.let { put("input", it) }
        rawOutput?.let { put("output", it) }
    }
}
```

---

## Helper Functions

```kotlin
// Transform input
fun inputFrom(rawInput: JsonElement, from: String?, scope: Scope): JsonElement =
    from?.let { JQExpression.eval(rawInput, it, scope, force = true) } ?: rawInput

// Check condition
fun shouldExecute(input: JsonElement, ifExpr: String?, scope: Scope): Boolean =
    ifExpr?.let { JQExpression.evalBoolean(input, it, scope) } ?: true

// Transform output
fun outputAs(rawOutput: JsonElement, asExpr: String?, scope: Scope): JsonElement =
    asExpr?.let { JQExpression.eval(rawOutput, it, scope, force = true) } ?: rawOutput

// Export to context
fun exportAs(output: JsonElement, asExpr: String?, scope: Scope): JsonObject? =
    asExpr?.let { JQExpression.eval(output, it, scope, force = true) as? JsonObject }
```

---

## Testing

```kotlin
@Test
fun `should evaluate object construction`() {
    val input = JsonObject(mapOf("user" to JsonObject(mapOf(
        "firstName" to JsonPrimitive("John"),
        "lastName" to JsonPrimitive("Doe")
    ))))

    val result = JQExpression.eval(
        input, "{name: \"\\(.user.firstName) \\(.user.lastName)\"}",
        JsonObject(mapOf()), force = true
    )

    assertEquals("John Doe", result.jsonObject["name"]?.jsonPrimitive?.content)
}
```

---

## Common Issues

| Issue | Solution |
|-------|----------|
| Expression not evaluated | Wrap the runtime expression in `${ ... }` |
| Variable not found | Check scope chain provides variable |
| Type mismatch | Use `type` function to debug |
| Null values | Use `// default` operator |
| Array vs single | Use `first` or `[]` to clarify |
