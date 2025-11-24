# Node Tree Architecture

## Overview

Every workflow is an immutable tree of `Node<T>` objects. The tree is built once during parsing and cached. Runtime state is kept separate in `TaskStates`.

## Key Files

| File | Purpose |
|------|---------|
| `nodes/Node.kt` | Immutable node structure |
| `nodes/NodePosition.kt` | Path addressing system |
| `nodes/Token.kt` | Special path segment tokens |
| `nodes/RootTask.kt` | Synthetic root node |

---

## Node Structure

Location: `nodes/Node.kt`

```kotlin
data class Node<T : TaskBase>(
    val position: NodePosition,    // Unique path in tree
    val task: T,                   // Task definition from DSL
    val name: String,              // Task name
    val parent: Node<*>? = null    // Parent reference (null for root)
) {
    // Lazy-loaded children based on task type
    val children: List<Node<*>>? by lazy { ... }

    // Serialized task definition
    val definition: JsonObject by lazy { ... }

    // Position as string (e.g., "/do/0/taskName")
    val reference: String get() = position.toString()

    // True if this is an activity (leaf node that does work)
    fun isActivity(): Boolean

    // Generate Mermaid diagram for debugging
    fun toMermaidGraph(): String
}
```

### Children Resolution

Each task type defines how children are built:

```kotlin
val children: List<Node<*>>? by lazy {
    when (task) {
        is RootTask -> task.parseChildren(this)          // DO block
        is DoTask -> task.parseChildren(position, this)  // Child tasks
        is ForTask -> task.parseChildren(position, this) // DO block for iteration
        is TryTask -> task.parseChildren(position, this) // TRY + CATCH blocks
        is ForkTask -> task.parseChildren(position, this) // Branch nodes
        is ListenTask -> task.parseChildren(position, this)
        is CallAsyncAPI -> task.parseChildren(position, this)
        else -> null  // Leaf nodes
    }
}
```

---

## NodePosition

Location: `nodes/NodePosition.kt`

Encodes the path from root to any node. Used as keys in `TaskStates` map.

### Structure

```
/do/0/validateInput/do/1/callApi
  │  │     │        │  │    └── Task name
  │  │     │        │  └── Index in parent's do block
  │  │     │        └── Token.DO
  │  │     └── Task name
  │  └── Index (0-based)
  └── Token.DO
```

### Construction

```kotlin
// Start from root
val root = NodePosition.root  // ""

// Build path step by step
val position = NodePosition.root
    .addToken(Token.DO)      // "/do"
    .addIndex(0)             // "/do/0"
    .addName("taskName")     // "/do/0/taskName"
    .addToken(Token.DO)      // "/do/0/taskName/do"
    .addIndex(1)             // "/do/0/taskName/do/1"
```

### Navigation

```kotlin
// Get parent position
val parent: NodePosition? = position.parent

// Check if position starts with another
val isChild = position.startsWith(parentPosition)

// Get depth
val depth = position.depth
```

### Key Methods

```kotlin
class NodePosition {
    companion object {
        val root = NodePosition(emptyList())
    }

    fun addName(name: String): NodePosition
    fun addToken(token: Token): NodePosition
    fun addIndex(index: Int): NodePosition

    val parent: NodePosition?
    val depth: Int

    fun startsWith(other: NodePosition): Boolean
    override fun toString(): String  // e.g., "/do/0/taskName"
}
```

---

## Token Types

Location: `nodes/Token.kt`

Special path segments for control structures:

```kotlin
enum class Token {
    DO,           // Sequential do block
    TRY,          // Try block in TryTask
    CATCH,        // Catch block in TryTask
    FORK,         // Fork task marker
    BRANCHES,     // Parallel branches container
    FOREACH,      // Iteration loop
    WITH,         // Event subscription context
    SUBSCRIPTION  // Event subscription handler
}
```

### Usage Examples

| Position Pattern | Meaning |
|-----------------|---------|
| `/do/0` | First task in root do block |
| `/do/0/taskName/try/do/0` | First task inside try block |
| `/do/0/taskName/catch/do/0` | First task inside catch block |
| `/do/0/forkName/branches/0` | First branch of fork |
| `/do/0/forName/foreach/do/0` | First task in for loop body |

---

## RootTask

Location: `nodes/RootTask.kt`

Synthetic root node wrapping the workflow's do block:

```kotlin
data class RootTask(
    val document: Document,           // Workflow metadata
    val `do`: List<TaskItem>,         // Root task list
    val use: Use?                     // Shared definitions
) : TaskBase()
```

---

## Tree Navigation in Orchestrator

### Getting Nodes

```kotlin
// Get node map for workflow
val nodesMap = DefinitionCache.getNodesMap(workflow)

// Get specific node
val node = nodesMap[position]
    ?: throw IllegalStateException("Node not found: $position")

// Or use extension function
val node = workflow.getNode(position)
```

### Navigation Directions

The orchestrator navigates the tree in four ways:

```kotlin
enum class Direction {
    FROM_PARENT,  // Entering child from parent (going down)
    FROM_CHILD,   // Returning from child to parent (going up)
    FROM_SIBLING, // Moving to next sibling (going side)
    SKIPPING      // Jumping to specific node (goto)
}
```

### Navigation Logic

```kotlin
// Going DOWN: Enter first child
fun goDown(node: Node<*>): NodePosition? {
    return node.children?.firstOrNull()?.position
}

// Going SIDE: Move to next sibling
fun goSide(node: Node<*>, currentIndex: Int): NodePosition? {
    val parent = node.parent ?: return null
    val siblings = parent.children ?: return null
    return siblings.getOrNull(currentIndex + 1)?.position
}

// Going UP: Return to parent
fun goUp(node: Node<*>): NodePosition? {
    return node.parent?.position
}

// SKIP: Jump to named task (then directive)
fun skipTo(node: Node<*>, targetName: String): NodePosition? {
    val parent = node.parent ?: return null
    return parent.children?.find { it.name == targetName }?.position
}
```

---

## Common Patterns

```kotlin
// Find parent of specific type
fun <T : TaskBase> Node<*>.findAncestor(type: KClass<T>): Node<T>? {
    var current: Node<*>? = parent
    while (current != null) {
        if (type.isInstance(current.task)) return current as Node<T>
        current = current.parent
    }
    return null
}

// Walk all descendants
fun Node<*>.walkDescendants(action: (Node<*>) -> Unit) {
    children?.forEach { action(it); it.walkDescendants(action) }
}

// Check if activity (leaf that does work)
fun Node<*>.isActivity(): Boolean = task is CallHTTP || task is WaitTask || task is RunTask || ...

// Debug: print tree
fun Node<*>.printTree(indent: Int = 0) {
    println("${" ".repeat(indent)}${position}: ${task::class.simpleName}")
    children?.forEach { it.printTree(indent + 2) }
}

// Debug: generate Mermaid diagram
val diagram = rootNode.toMermaidGraph()
```

---

## Common Issues

| Issue | Check |
|-------|-------|
| Node not found | Position path matches tree structure |
| Wrong children | Task type handled in `children` property |
| Parent is null | Only root has null parent |
| Index out of bounds | Sibling count before navigation |
