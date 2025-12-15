# Feature: Workflow Instance Pause and Resume

## Overview

This feature enables users to pause running workflow instances and resume them later. Pausing a workflow stops its
execution at the next step boundary, preserving its state for later continuation. This is useful for:

- **Operational control**: Temporarily halt workflows during maintenance windows
- **Debugging**: Pause workflows to inspect state or fix issues
- **Resource management**: Pause non-critical workflows during high-load periods
- **Manual intervention**: Hold workflows pending human review or approval

## Business Requirements

### BR-1: Pause Workflow Instance

**As a** workflow operator
**I want to** pause a running workflow instance
**So that** its execution stops at the next step boundary and waits for my resume command

**Acceptance Criteria:**

1. When a pause command is issued for a workflow ID, all workers must be notified immediately
2. The workflow must stop processing at the next step boundary (not mid-task)
3. Any in-flight messages for the paused workflow must be preserved (parked)
4. The paused state must persist across worker restarts
5. Pausing an already-paused workflow should be idempotent (no error)

### BR-2: Resume Workflow Instance

**As a** workflow operator
**I want to** resume a paused workflow instance
**So that** it continues execution from where it was paused

**Acceptance Criteria:**

1. When a resume command is issued, all parked messages must be re-emitted in order
2. The workflow must continue from its exact saved state
3. All workers must be notified to remove the workflow from their pause list
4. The resume must be atomic - either all parked messages are restored or none
5. Resuming an already-running workflow should be idempotent (no error)
6. Resuming a non-existent or completed workflow should return an appropriate error

### BR-3: Query Paused Workflows

**As a** workflow operator
**I want to** list all currently paused workflow instances
**So that** I can monitor and manage paused workflows

**Acceptance Criteria:**

1. Must be able to list all paused workflow instances
2. Must include metadata: workflow ID, namespace, name, version, paused timestamp, reason
3. Must support filtering by namespace and/or workflow name

### BR-4: Pause with Reason

**As a** workflow operator
**I want to** provide a reason when pausing a workflow
**So that** other operators understand why it was paused

**Acceptance Criteria:**

1. The pause command must accept an optional reason string
2. The reason must be stored and retrievable when querying paused workflows
3. The reason should be included in any audit logs or lifecycle events

---

## Architecture

### Design Principles

The architecture follows Lemline's core philosophy: **"Last who spoke wins"** with scope-based specificity.

| Principle                   | Application                                                       |
|-----------------------------|-------------------------------------------------------------------|
| **Minimize database usage** | DB stores instructions (few rows) + parked messages only          |
| **Instruction-based model** | Each scope has ONE instruction (PAUSE or RESUME), not accumulated |
| **Specificity hierarchy**   | More specific scope wins; same specificity → most recent wins     |
| **Real-time coordination**  | Broadcast channel ensures immediate worker notification           |
| **Stateless workers**       | In-memory instruction cache rebuilt from DB on startup            |

### Core Concept: Pause Instructions

Instead of tracking "which workflows are paused", we store **instructions** that declare intent:

```kotlin
data class PauseInstruction(
    val id: IDV7,
    val scope: PauseScope,
    val filter: String?,          // Optional JQ expression refinement
    val type: InstructionType,    // PAUSE or RESUME
    val createdAt: Instant,
    val reason: String?,
    val createdBy: String?
) {
    enum class InstructionType { PAUSE, RESUME }
}
```

**Key rules:**

1. **One instruction per (scope, filter)** — new instruction replaces old at same scope+filter
2. **Scopes have a containment hierarchy** — more specific wins
3. **Filter refines a scope** — scope+filter is more specific than scope alone
4. **Same specificity → most recent wins** — "last who spoke is right"

### Scope Hierarchy

Scopes follow a natural containment hierarchy:

```
┌─────────────────────────────────────────────────────────────────┐
│                      SCOPE HIERARCHY                            │
│                                                                 │
│  Global ⊃ Namespace ⊃ Name ⊃ Version  ⊃ Instance                │
│                                                                 │
│  Global < Namespace                                             │
│         < Namespace+Name                                        │
│         < Namespace+Name+Version                                │
│         < Instance                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

```kotlin
sealed class PauseScope {
    object Global : PauseScope()
    data class ByNamespace(val namespace: WorkflowNamespace) : PauseScope()
    data class ByName(val namespace: WorkflowNamespace, val name: WorkflowName) : PauseScope()
    data class ByVersion(val namespace: WorkflowNamespace, val name: WorkflowName, val version: WorkflowVersion) :
        PauseScope()
    data class ById(val workflowId: WorkflowId) : PauseScope()
    data class ByExpression(val expression: String) : PauseScope()
}
```

### Evaluation Logic

For any workflow, determine if it's paused:

```kotlin
fun isPaused(workflow: WorkflowInfo, context: JsonNode): Boolean {
    val instructions = instructionRepository.findAll()  // Small set, cached in memory

    // Find all instructions whose scope matches this workflow
    val matching = instructions.filter { instr ->
        instr.scope.matches(workflow) &&
            (instr.filter == null || evaluateJq(instr.filter, context) == true)
    }

    // Sort by specificity (desc), then by createdAt (desc)
    // Filter adds +0.5 to specificity
    val winner = matching.maxWithOrNull(
        compareBy(
            { it.scope.specificity + if (it.filter != null) 0.5 else 0.0 },
            { it.createdAt }
        )
    )

    return winner?.type == PAUSE
}
```

### Example Scenarios

**Scenario 1: Pause namespace, resume specific instance**

```
T1: PAUSE(Namespace("orders"), filter=null)
T2: RESUME(Instance("order-123"), filter=null)
```

| Workflow  | Matching Instructions  | Winner (most specific) | Result     |
|-----------|------------------------|------------------------|------------|
| order-123 | T1 (ns), T2 (instance) | T2 (specificity 3)     | NOT PAUSED |
| order-456 | T1 (ns)                | T1                     | PAUSED     |

**Scenario 2: Re-pause namespace after instance resume**

```
T1: PAUSE(Namespace("orders"), filter=null)
T2: RESUME(Instance("order-123"), filter=null)
T3: PAUSE(Namespace("orders"), filter=null)   -- replaces T1 (same scope)
```

| Workflow  | Matching Instructions  | Winner             | Result     |
|-----------|------------------------|--------------------|------------|
| order-123 | T3 (ns), T2 (instance) | T2 (instance > ns) | NOT PAUSED |
| order-456 | T3 (ns)                | T3                 | PAUSED     |

**Scenario 3: Filter-based exemption**

```
T1: PAUSE(Namespace("orders"), filter=null)
T2: RESUME(Namespace("orders"), filter=".input.priority == 'high'")
T3: PAUSE(Namespace("orders"), filter=null)   -- replaces T1
```

| Workflow      | Matching Instructions   | Winner              | Result     |
|---------------|-------------------------|---------------------|------------|
| high-priority | T3 (ns), T2 (ns+filter) | T2 (ns+filter > ns) | NOT PAUSED |
| low-priority  | T3 (ns)                 | T3                  | PAUSED     |

**Scenario 4: Override filter-based exemption**

```
T1: PAUSE(Namespace("orders"), filter=null)
T2: RESUME(Namespace("orders"), filter=".input.priority == 'high'")
T3: PAUSE(Namespace("orders"), filter=".input.priority == 'high'")  -- replaces T2
```

| Workflow      | Matching Instructions   | Winner              | Result |
|---------------|-------------------------|---------------------|--------|
| high-priority | T1 (ns), T3 (ns+filter) | T3 (ns+filter > ns) | PAUSED |
| low-priority  | T1 (ns)                 | T1                  | PAUSED |

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         PAUSE/RESUME ARCHITECTURE                        │
│                                                                          │
│                      PING-TO-SYNC + PERIODIC SYNC                        │
│                                                                          │
│   ┌─────────┐      ┌──────────────────┐                                  │
│   │  User   │─────►│  control-channel │  broadcast "SYNC" ping           │
│   │ (CLI/   │      └────────┬─────────┘                                  │
│   │  API)   │               │                                            │
│   └────┬────┘    ┌──────────┼──────────┐                                 │
│        │         ▼          ▼          ▼                                 │
│        │    ┌────────┐ ┌────────┐ ┌────────┐                             │
│        │    │Worker 1│ │Worker 2│ │Worker 3│                             │
│        │    └───┬────┘ └───┬────┘ └───┬────┘                             │
│        │        │          │          │                                  │
│        │        └──────────┼──────────┘                                  │
│        │                   │                                             │
│        │         ┌─────────┴─────────┐                                   │
│        │         │  On SYNC ping:    │                                   │
│        │         │  On startup:      │  ◄── Same code path               │
│        │         │  Every 30s:       │                                   │
│        │         └─────────┬─────────┘                                   │
│        │                   │                                             │
│        │                   ▼                                             │
│        │    ┌───────────────────────────────────────┐                    │
│        │    │  SELECT * FROM lemline_pause_instructions                  │
│        │    │  └─► instructionCache.replaceAll(results)                  │
│        │    └───────────────────────────────────────┘                    │
│        │                   │                                             │
│        ▼                   ▼                                             │
│  ┌───────────┐   ┌─────────────────────────────────┐                     │
│  │ Database  │   │  In-Memory InstructionCache     │                     │
│  │ (source   │   │                                 │                     │
│  │ of truth) │   │  Map<ScopeKey, PauseInstruction>│                     │
│  └───────────┘   └─────────────────────────────────┘                     │
│                            │                                             │
│                            ▼                                             │
│               ┌─────────────────────────────────┐                        │
│               │  isPaused(workflow, context)    │                        │
│               │                                 │                        │
│               │  1. Find matching instructions  │                        │
│               │  2. Sort by specificity (desc)  │                        │
│               │  3. Same specificity → newest   │                        │
│               │  4. Winner.type == PAUSE?       │                        │
│               └─────────────────────────────────┘                        │
│                            │                                             │
│                            ▼                                             │
│   ┌─────────────┐    ┌───────────────────────────────┐                   │
│   │ commands-in │───►│  WorkflowCommandHandler       │                   │
│   └─────────────┘    │                               │                   │
│                      │  if isPaused(workflow):       │                   │
│                      │    → INSERT into lemline_paused                   │
│                      │    → ACK message              │                   │
│                      │  else:                        │                   │
│                      │    → process normally         │                   │
│                      └───────────────────────────────┘                   │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐     │
│  │                         DATABASE                                │     │
│  │                                                                 │     │
│  │  ┌────────────────────────────┐    ┌────────────────────────┐   │     │
│  │  │ lemline_pause_instructions │    │ lemline_paused         │   │     │
│  │  │                            │    │                        │   │     │
│  │  │ id (PK)                    │◄───│ instruction_id (FK)    │   │     │
│  │  │ scope_type                 │    │ workflow_id            │   │     │
│  │  │ scope_value                │    │ message_payload        │   │     │
│  │  │ filter (nullable)          │    │ parked_at              │   │     │
│  │  │ instruction_type           │    │ message_order          │   │     │
│  │  │ created_at                 │    └────────────────────────┘   │     │
│  │  │ reason, created_by         │                                 │     │
│  │  │                            │    UNIQUE(scope_type,           │     │
│  │  │                            │           scope_value, filter)  │     │
│  │  └────────────────────────────┘                                 │     │
│  │                                                                 │     │
│  └─────────────────────────────────────────────────────────────────┘     │
│                                                                          │
│  SYNC TRIGGERS (all use same code path):                                 │
│    1. On startup: sync from DB                                           │
│    2. On SYNC ping: immediate sync from DB                               │
│    3. Every 30s: periodic sync as safety net                             │
│                                                                          │
│  KEY BEHAVIORS:                                                          │
│    • UPSERT on (scope_type, scope_value, filter) — replaces old          │
│    • One instruction per scope+filter combination                        │
│    • Specificity determines winner, not insertion order                  │
│    • RESUME instruction at scope X removes PAUSE effect at scope X       │
│    • Database is always source of truth                                  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Why This Design?

| Component                       | Purpose                    | Rationale                                                                |
|---------------------------------|----------------------------|--------------------------------------------------------------------------|
| **Instruction-based model**     | Clear semantics            | "Last who spoke wins" — no accumulating state, no epoch tracking         |
| **Scope hierarchy**             | Intuitive precedence       | Instance beats namespace, just like CSS specificity                      |
| **Filter as refinement**        | Flexible targeting         | Expression narrows a scope without creating ambiguous "expression scope" |
| **One instruction per scope**   | Minimal storage            | UPSERT replaces old; no history needed                                   |
| **Control channel (broadcast)** | Real-time notification     | All workers must know immediately when instructions change               |
| **In-memory cache**             | Fast evaluation            | Instructions are few; cache entire set                                   |
| **lemline_paused table**        | Store intercepted messages | Required for reliable resume; messages must not be lost                  |

### Control Channel Design

The control channel uses **broadcast semantics** - every worker receives every message:

**Kafka Configuration:**

```properties
mp.messaging.incoming.control-in.connector=smallrye-kafka
mp.messaging.incoming.control-in.topic=lemline-control
mp.messaging.incoming.control-in.group.id=control-${quarkus.uuid}  # Unique per instance
mp.messaging.incoming.control-in.auto.offset.reset=latest
```

**RabbitMQ Configuration:**

```properties
mp.messaging.incoming.control-in.connector=smallrye-rabbitmq
mp.messaging.incoming.control-in.exchange.name=lemline-control
mp.messaging.incoming.control-in.exchange.type=fanout
mp.messaging.incoming.control-in.queue.name=control-${quarkus.uuid}
mp.messaging.incoming.control-in.queue.exclusive=true
```

### Control Message Types

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONTROL MESSAGE TYPES                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ControlMessage.Instruction                                     │
│  ├── scope: PauseScope       (required)                         │
│  ├── filter: String?         (optional JQ expression)           │
│  ├── type: InstructionType   (PAUSE or RESUME)                  │
│  ├── reason: String?         (optional)                         │
│  └── createdBy: String?      (optional, e.g., "user@example")   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Filter Expression Context

When using a filter expression, the JQ is evaluated against:

```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "namespace": "production",
    "name": "order-processing",
    "version": "1.2.0",
    "input": {
        "customerId": "cust-123",
        "orderId": "ord-456",
        "priority": "high"
    }
}
```

**Expression Examples:**

| Use Case            | JQ Expression                                       |
|---------------------|-----------------------------------------------------|
| Input field match   | `.input.customerId == "vip-123"`                    |
| Priority filtering  | `.input.priority == "low"`                          |
| Region-based        | `.input.region \| startswith("eu-")`                |
| Regex match         | `.name \| test("^batch-job-.*$")`                   |
| Combined conditions | `.namespace == "prod" and .input.priority == "low"` |

---

## Data Model

### Entity: PauseInstruction

Represents a pause or resume instruction at a specific scope. Uses UPSERT semantics — one instruction per (scope_type,
scope_value, filter) combination.

| Field         | Type           | Constraints             | Description                                                                             |
|---------------|----------------|-------------------------|-----------------------------------------------------------------------------------------|
| `id`          | UUID           | PRIMARY KEY             | Unique instruction ID (IDV7)                                                            |
| `scope_type`  | VARCHAR(20)    | NOT NULL                | Scope type: `GLOBAL`, `BY_NAMESPACE`, `BY_NAME`, `BY_VERSION`, `BY_INSTANCE`, `BY_EXPR` |
| `scope_value` | VARCHAR(500)   |                         | Scope identifier (see below)                                                            |
| `instruction` | VARCHAR(10)    | NOT NULL                | `PAUSE` or `RESUME`                                                                     |
| `created_at`  | TIMESTAMPTZ(6) | NOT NULL, DEFAULT NOW() | When the instruction was created (used for ordering)                                    |

**Unique Constraint:** `UNIQUE(scope_type, scope_value)` — ensures one instruction per scope+filter.

**Scope Value Encoding:**

| `scope_type`   | `scope_value` format           | Example                                |
|----------------|--------------------------------|----------------------------------------|
| `GLOBAL`       | `NULL` or empty                | `NULL`                                 |
| `BY_NAMESPACE` | `{namespace}`                  | `orders`                               |
| `BY_NAME`      | `{namespace}/{name}`           | `orders/checkout`                      |
| `BY_VERSION`   | `{namespace}/{name}:{version}` | `orders/checkout:0.1.0`                |
| `BY_INSTANCE`  | `{workflowId}`                 | `550e8400-e29b-41d4-a716-446655440000` |
| `BY_EXPR`      | `{ expression }`               | `.orderId == 550e8400`                 |

### Entity: PausedMessage

Represents a workflow message that was intercepted when the workflow was determined to be paused.

| Field             | Type           | Constraints                               | Description                                   |
|-------------------|----------------|-------------------------------------------|-----------------------------------------------|
| `id`              | UUID           | PRIMARY KEY                               | Unique message ID (IDV7)                      |
| `pause_id`        | UUID           | NOT NULL, FK → lemline_pause_instructions | The pause instruction that caused parking     |
| `workflow_id`     | UUID           | NOT NULL                                  | The workflow instance this message belongs to |
| `message_payload` | TEXT           | NOT NULL                                  | Serialized InstanceMessage JSON               |
| `message_order`   | BIGINT         | NOT NULL                                  | Ordering for FIFO replay                      |
| `paused_at`       | TIMESTAMPTZ(6) | NOT NULL, DEFAULT NOW()                   | When the message was parked                   |

### Relationships

```
┌────────────────────────────────┐         ┌─────────────────────────┐
│  lemline_pause_instructions    │         │   lemline_paused        │
├────────────────────────────────┤         ├─────────────────────────┤
│ id (PK)                        │◄────────│ instruction_id (FK)     │
│ scope_type                     │    1:N  │ id (PK)                 │
│ scope_value                    │         │ workflow_id             │
│ filter                         │         │ message_payload         │
│ instruction_type               │         │ message_order           │
│ reason                         │         │ paused_at               │
│ created_by                     │         └─────────────────────────┘
│ created_at                     │
│                                │         ON DELETE CASCADE
│ UNIQUE(scope_type,             │
│        scope_value, filter)    │
└────────────────────────────────┘
```

### Indexes

| Table                        | Index                        | Columns                             | Purpose                           |
|------------------------------|------------------------------|-------------------------------------|-----------------------------------|
| `lemline_pause_instructions` | `uk_instruction_scope`       | `(scope_type, scope_value, filter)` | Enforce one instruction per scope |
| `lemline_pause_instructions` | `idx_instruction_scope_type` | `(scope_type)`                      | Fast lookup by scope type         |
| `lemline_paused`             | `idx_paused_instruction`     | `(instruction_id, message_order)`   | FIFO replay ordering              |
| `lemline_paused`             | `idx_paused_workflow`        | `(workflow_id)`                     | Query paused messages by workflow |

---

---

## Messaging Flow

### Channels

| Channel        | Direction            | Purpose                                    |
|----------------|----------------------|--------------------------------------------|
| `control-in`   | Incoming (broadcast) | Receive instruction updates from all nodes |
| `control-out`  | Outgoing             | Broadcast instruction changes              |
| `commands-in`  | Incoming             | Check pause state before processing        |
| `commands-out` | Outgoing             | Re-emit parked messages when unpaused      |

### Pause Instruction Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     PAUSE INSTRUCTION FLOW                              │
└─────────────────────────────────────────────────────────────────────────┘

STEP 1: User Issues Pause Command
═══════════════════════════════════════════════════════════════════════════
    CLI/API
        │
        ├─► Build PauseInstruction from CLI args:
        │   - scope: Instance(id) for: lemline instance pause <id>
        │   - scope: Namespace(ns) for: lemline instance pause -n <ns>
        │   - scope: Definition(ns,name,v) for: -n <ns> -w <name> -v <v>
        │   - filter: optional --filter '<jq expression>'
        │   - type: PAUSE
        │
        ├─► UPSERT INTO lemline_pause_instructions
        │   ON CONFLICT (scope_type, scope_value, filter) DO UPDATE
        │   SET instruction_type = 'PAUSE', created_at = NOW(), ...
        │
        └─► Emit ControlMessage.Instruction to control-out channel

STEP 2: Workers Receive Broadcast
═══════════════════════════════════════════════════════════════════════════
    ControlMessageHandler (all workers)
        │
        └─► instructionCache.upsert(instruction)
            │
            └─► Map<ScopeKey, PauseInstruction> updated
                (replaces any existing instruction at same scope+filter)

STEP 3: Subsequent Messages Are Evaluated
═══════════════════════════════════════════════════════════════════════════
    WorkflowCommandHandler
        │
        ├─► winningInstruction = instructionCache.evaluate(workflow, context)
        │   1. Find all instructions matching this workflow
        │   2. Evaluate filter expressions against workflow context
        │   3. Sort by specificity (desc), then createdAt (desc)
        │   4. Return most specific/recent instruction
        │
        └─► IF winningInstruction?.type == PAUSE:
            │   INSERT INTO lemline_paused (
            │       id = IDV7.random(),
            │       instruction_id = winningInstruction.id,
            │       workflow_id = message.workflowId,
            │       message_payload = message.toJson(),
            │       message_order = incrementing sequence
            │   )
            │
            └─► ACK the original message (remove from broker)
```

### Resume Instruction Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     RESUME INSTRUCTION FLOW                             │
└─────────────────────────────────────────────────────────────────────────┘

STEP 1: User Issues Resume Command
═══════════════════════════════════════════════════════════════════════════
    CLI/API
        │
        ├─► Build PauseInstruction from CLI args:
        │   - scope: same syntax as pause
        │   - filter: same syntax as pause
        │   - type: RESUME
        │
        ├─► UPSERT INTO lemline_pause_instructions
        │   ON CONFLICT (scope_type, scope_value, filter) DO UPDATE
        │   SET instruction_type = 'RESUME', created_at = NOW(), ...
        │
        ├─► Replay parked messages that are now unpaused:
        │   FOR EACH workflow with parked messages:
        │       IF instructionCache.evaluate(workflow, context)?.type != PAUSE:
        │           Re-emit parked messages to commands-out (ordered)
        │           DELETE from lemline_paused
        │
        └─► Emit ControlMessage.Instruction to control-out channel

STEP 2: Workers Receive Broadcast
═══════════════════════════════════════════════════════════════════════════
    ControlMessageHandler (all workers)
        │
        └─► instructionCache.upsert(instruction)
            │
            └─► Instruction at scope+filter now has type=RESUME
                (affects future isPaused evaluations)

STEP 3: Workflow Continues
═══════════════════════════════════════════════════════════════════════════
    Re-emitted messages processed normally by WorkflowCommandHandler
    (isPaused now returns false for these workflows)
```

### Worker Startup Recovery

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       STARTUP RECOVERY                                  │
└─────────────────────────────────────────────────────────────────────────┘

    Worker starts
        │
        ├─► SELECT * FROM lemline_pause_instructions
        │
        └─► instructionCache.loadAll(instructions)
            │
            └─► Map<ScopeKey, PauseInstruction> populated

    Worker now has correct in-memory state for isPaused evaluation
```

---

## Lifecycle Events

The pause/resume feature should emit lifecycle events for observability:

| Event Type                  | When Emitted                           | Payload                                                |
|-----------------------------|----------------------------------------|--------------------------------------------------------|
| `pause.filter.created`      | After pause filter is created          | filterId, filterType, filter details, reason, pausedBy |
| `pause.filter.removed`      | After pause filter is removed (resume) | filterId, filterType, parkedMessageCount               |
| `workflow.message.parked`   | When a message is parked               | workflowId, filterId, messageOrder                     |
| `workflow.message.replayed` | When a parked message is replayed      | workflowId, filterId, messageOrder                     |

---

## User Interface

### CLI Commands

```bash
# Pause by workflow ID (scope: Instance)
lemline instance pause <workflow-id>
lemline instance pause <workflow-id> --reason "maintenance window"

# Pause by namespace (scope: Namespace)
lemline instance pause -n production
lemline instance pause -n production --reason "maintenance"

# Pause by definition (scope: Definition)
lemline instance pause -n production -w order-processing -v 1.0.0

# Pause with filter expression (refines the scope)
lemline instance pause -n production --filter '.input.priority == "low"'
lemline instance pause -n staging --filter '.input.debug == true'

# Global pause with filter (scope: Global + filter)
lemline instance pause --global --filter '.input.priority == "low"'

# Resume (uses same scope syntax - creates RESUME instruction)
lemline instance resume <workflow-id>
lemline instance resume -n production
lemline instance resume -n production -w order-processing -v 1.0.0
lemline instance resume -n production --filter '.input.priority == "high"'

# List active instructions
lemline instance instructions list
lemline instance instructions list -n production
lemline instance instructions list --type PAUSE  # only PAUSE instructions

# List paused workflows (shows parked message counts)
lemline instance list --paused
lemline instance list --paused -n production

# Delete an instruction (removes it entirely, not the same as RESUME)
lemline instance instructions delete <instruction-id>
```

**Note on RESUME vs DELETE:**

- `resume` creates a RESUME instruction at the scope, which wins over less-specific PAUSE instructions
- `instructions delete` removes the instruction entirely, falling back to other matching instructions

### API Endpoints (if REST API enabled)

```
# Create/update instruction (UPSERT semantics)
POST /api/v1/instructions
  Body: {
    "scope": { "type": "NAMESPACE", "value": "production" },
    "filter": ".input.priority == 'low'",  // optional
    "instructionType": "PAUSE",
    "reason": "optional reason"
  }
  Response: 200 OK (upserted)

# List active instructions
GET /api/v1/instructions
  Query: scopeType, instructionType (optional filters)
  Response: [{ id, scopeType, scopeValue, filter, instructionType, createdAt, reason }]

# Delete an instruction
DELETE /api/v1/instructions/{id}
  Response: 204 No Content | 404 Not Found

# Get paused workflows with parked message counts
GET /api/v1/workflows/paused
  Query: namespace, name (optional)
  Response: [{ workflowId, namespace, name, version, parkedCount, pausedAt }]

# Check if a specific workflow is paused
GET /api/v1/workflows/{id}/pause-status
  Response: {
    "isPaused": true,
    "winningInstruction": { id, scopeType, scopeValue, filter, instructionType }
  }
```

---

## Implementation Files

### lemline-runner (infrastructure)

| File                                            | Type     | Description                                           |
|-------------------------------------------------|----------|-------------------------------------------------------|
| `messaging/control/ControlMessage.kt`           | **NEW**  | Sealed class for instruction broadcast messages       |
| `messaging/control/PauseScope.kt`               | **NEW**  | Sealed class: Global, Namespace, Definition, Instance |
| `messaging/control/PauseInstruction.kt`         | **NEW**  | Data class with scope, filter, type (PAUSE/RESUME)    |
| `messaging/control/ControlMessageEmitter.kt`    | **NEW**  | Emit control messages to broadcast channel            |
| `messaging/control/ControlMessageSubscriber.kt` | **NEW**  | Subscribe to control channel (broadcast)              |
| `messaging/control/InstructionCache.kt`         | **NEW**  | In-memory cache with isPaused evaluation logic        |
| `messaging/control/InstructionCacheRecovery.kt` | **NEW**  | Startup recovery from database                        |
| `models/PauseInstructionModel.kt`               | **NEW**  | Entity for lemline_pause_instructions table           |
| `models/PausedModel.kt`                         | **NEW**  | Entity for lemline_paused table (parked messages)     |
| `repositories/PauseInstructionRepository.kt`    | **NEW**  | Repository for instruction UPSERT/query               |
| `repositories/PausedRepository.kt`              | **NEW**  | Repository for parked messages                        |
| `messaging/commands/WorkflowCommandHandler.kt`  | Modified | Add isPaused() check before processing                |
| `cli/instances/InstancePauseCommand.kt`         | **NEW**  | CLI command for pause (creates PAUSE instruction)     |
| `cli/instances/InstanceResumeCommand.kt`        | **NEW**  | CLI command for resume (creates RESUME instruction)   |
| `cli/instances/InstanceInstructionsCommand.kt`  | **NEW**  | CLI command to list/delete instructions               |
| `cli/instances/InstanceCommand.kt`              | Modified | Register new subcommands                              |
| `config/LemlineConfiguration.kt`                | Modified | Add control channel configuration                     |
| `config/LemlineConfigSource.kt`                 | Modified | Generate control channel properties                   |
| `resources/db/migration/postgresql/V010__*.sql` | **NEW**  | PostgreSQL migration                                  |
| `resources/db/migration/mysql/V010__*.sql`      | **NEW**  | MySQL migration                                       |
| `resources/db/migration/h2/V010__*.sql`         | **NEW**  | H2 migration                                          |

### Tests

| File                                       | Description                                 |
|--------------------------------------------|---------------------------------------------|
| `tests/pause/PauseResumeTest.kt`           | End-to-end pause/resume for all scope types |
| `tests/pause/InstructionCacheTest.kt`      | Unit tests for specificity evaluation logic |
| `tests/pause/PauseScopeTest.kt`            | Unit tests for scope matching               |
| `tests/pause/SpecificityTest.kt`           | Tests for scope+filter specificity ordering |
| `tests/pause/PauseRecoveryTest.kt`         | Startup recovery scenarios                  |
| `tests/pause/ConcurrentInstructionTest.kt` | Race condition handling                     |
| `tests/pause/FilterExpressionTest.kt`      | JQ filter expression evaluation tests       |

---

## Configuration

### New Configuration Properties

```properties
# Control channel settings
lemline.messaging.control.enabled=true
# Kafka-specific (when messaging.type=kafka)
lemline.messaging.kafka.control-topic=lemline-control
# RabbitMQ-specific (when messaging.type=rabbitmq)
lemline.messaging.rabbitmq.control-exchange=lemline-control
```

---

## Edge Cases and Error Handling

### Race Conditions

| Scenario                                        | Handling                                                           |
|-------------------------------------------------|--------------------------------------------------------------------|
| Instruction arrives while message is processing | Message completes, next message uses new instruction state         |
| Multiple instructions change rapidly            | UPSERT semantics + timestamp ordering ensures eventual consistency |
| Broadcast received before DB write completes    | DB is source of truth; periodic sync corrects any discrepancies    |
| Message arrives between DB insert and broadcast | Startup recovery ensures consistency                               |

### Error Scenarios

| Scenario                                 | Behavior                                              |
|------------------------------------------|-------------------------------------------------------|
| Instance scope for non-existent workflow | Warning logged; instruction created (may never match) |
| Invalid JQ filter expression             | Validation error at instruction creation time         |
| Database unavailable during pause/resume | Fail the command                                      |
| Broker unavailable during replay         | Fail the resume; parked messages remain               |

### Consistency Guarantees

| Guarantee              | Mechanism                                                  |
|------------------------|------------------------------------------------------------|
| No message loss        | Parked messages stored in DB before ACK                    |
| Order preservation     | `message_order` column ensures FIFO replay                 |
| Crash recovery         | DB is source of truth; InstructionCache rebuilt on startup |
| Instruction uniqueness | Unique constraint on (scope_type, scope_value, filter)     |

### Specificity Rules

When multiple instructions match a workflow, the winner is determined by:

1. **Scope specificity** (higher wins):
    - Instance (3) > Definition (2) > Namespace (1) > Global (0)

2. **Filter presence** (+0.5 specificity):
    - Scope with filter > same scope without filter

3. **Timestamp** (tie-breaker):
    - Most recent wins at same specificity level

**Example precedence (highest to lowest):**

1. `Instance("order-123") + filter` — specificity 3.5
2. `Instance("order-123")` — specificity 3.0
3. `Definition("orders/checkout/1.0") + filter` — specificity 2.5
4. `Definition("orders/checkout/1.0")` — specificity 2.0
5. `Namespace("orders") + filter` — specificity 1.5
6. `Namespace("orders")` — specificity 1.0
7. `Global + filter` — specificity 0.5
8. `Global` — specificity 0.0

---

## Testing Checklist

- [ ] Unit tests for InstructionCache (specificity ordering, filter evaluation)
- [ ] Unit tests for PauseScope sealed class (all variants, matching logic)
- [ ] Unit tests for ControlMessage serialization/deserialization
- [ ] Unit tests for JQ filter expression evaluation
- [ ] Unit tests for specificity calculation (scope + filter combinations)
- [ ] Integration test: pause by Instance scope, verify messages parked
- [ ] Integration test: pause by Namespace scope, verify all workflows paused
- [ ] Integration test: pause by Definition scope, verify matching workflows paused
- [ ] Integration test: pause with filter, verify expression evaluation
- [ ] Integration test: resume creates RESUME instruction, verify replay
- [ ] Integration test: specificity precedence (Instance > Namespace, filter > no-filter)
- [ ] Integration test: "last who spoke wins" at same specificity
- [ ] Integration test: UPSERT replaces existing instruction at same scope+filter
- [ ] Integration test: worker restart recovery
- [ ] Integration test: concurrent instruction changes
- [ ] Test with PostgreSQL
- [ ] Test with MySQL
- [ ] Test with H2
- [ ] Test with Kafka control channel
- [ ] Test with RabbitMQ control channel
- [ ] CLI command tests (pause, resume, instructions list/delete)

---

## Open Questions

None - the architecture has been discussed and validated during the design conversation.

---

## Summary

The pause/resume feature uses an **instruction-based model** with **scope hierarchy** and **filter refinement**:

| Concept                   | Description                                                          |
|---------------------------|----------------------------------------------------------------------|
| **Instruction**           | A PAUSE or RESUME declaration at a specific scope (+optional filter) |
| **Scope hierarchy**       | Instance > Definition > Namespace > Global (more specific wins)      |
| **Filter refinement**     | JQ expression that narrows a scope (+0.5 specificity)                |
| **UPSERT semantics**      | One instruction per (scope, filter) — new replaces old               |
| **"Last who spoke wins"** | At same specificity, most recent instruction wins                    |

**Key behaviors:**

- `pause -n orders` → creates `PAUSE(Namespace("orders"))`
- `resume <id>` → creates `RESUME(Instance("<id>"))` which wins over namespace pause
- `pause -n orders` again → replaces old instruction, but instance RESUME still wins (more specific)
- `pause -n orders --filter '.priority == "high"'` → wins over plain namespace pause for matching workflows

---

## References

- [ADR-0003: Messaging Architecture](../adr/0003-messaging-architecture.md)
- [SmallRye Reactive Messaging - Broadcast](https://smallrye.io/smallrye-reactive-messaging/)
- Design discussion: Instruction-based model with scope hierarchy and "last who spoke wins" semantics
