# Expression Evaluation

## Overview

Lemline uses JQ 1.6 for data transformation and condition evaluation. Expressions are evaluated within a hierarchical scope.

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

| Usage | DSL Field | Evaluation |
|-------|-----------|------------|
| Input transform | `input.from` | Direct JQ |
| Condition | `if` | Direct JQ → boolean |
| Output transform | `output.as` | Direct JQ |
| Export | `export.as` | Direct JQ → object |
| Iteration | `for.in`, `for.while` | Direct JQ |
| Switch | `switch.when` | Direct JQ → boolean |
| Error filter | `catch.when` | Direct JQ → boolean |
| String interpolation | Any string field | `${...}` syntax |

### Runtime vs Direct Expressions

```yaml
# Runtime expression (${} required)
endpoint: "https://api.example.com/users/${.userId}"

# Direct JQ (no ${} needed in from/as/when/in fields)
input:
  from: ".user | {name, email}"
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
| Expression not evaluated | Add `${}` wrapper or use in from/as/when field |
| Variable not found | Check scope chain provides variable |
| Type mismatch | Use `type` function to debug |
| Null values | Use `// default` operator |
| Array vs single | Use `first` or `[]` to clarify |
