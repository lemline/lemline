# [ADR-0011] Listen Task Correlation Matching

## Status

Accepted

## Context

The listen task in Serverless Workflow DSL allows workflows to wait for CloudEvents. When multiple workflow instances
are listening for the same event type, we need a way to route events to the correct instance. This is achieved through
**correlation** - matching event data against workflow-specific values.

The correlation mechanism uses two expressions:
- `from`: Extracts a value from the incoming CloudEvent data
- `expect`: Defines the expected value based on workflow context (`$input`, `$context`, etc.)

Example workflow definition:
```yaml
listen:
  to:
    one:
      with:
        type: order.shipped
      correlate:
        orderId:
          from: '${ .orderId }'           # Extract from event.data.orderId
          expect: '${ $input.orderId }'   # Compare against workflow input
```

The challenge is efficiently matching incoming events against potentially thousands of active listeners while
supporting the correlation semantics.

## Decision

We implement correlation matching with a **split evaluation strategy**:

1. **At listener creation time**: Evaluate `expect` expressions against workflow context, store the resulting values
2. **At event arrival time**: Evaluate `from` expressions against event data, query database with the resulting values

This approach pushes correlation filtering to the database level, avoiding loading all listeners into memory.

### Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         WORKFLOW DEFINITION                                  │
│                                                                             │
│  correlate:                                                                 │
│    orderId:                                                                 │
│      from: '${ .orderId }'           ──────┐                                │
│      expect: '${ $input.orderId }'   ──┐   │                                │
│                                        │   │                                │
└────────────────────────────────────────│───│────────────────────────────────┘
                                         │   │
                                         ▼   │
┌─────────────────────────────────────────────────────────────────────────────┐
│                      LISTENER CREATION                                       │
│                      (WorkflowEventHandler)                                  │
│                                                                             │
│  1. Get correlationContext from ListenConfig                                │
│     correlationContext = {input: {orderId: "123"}, context: {...}}          │
│                                                                             │
│  2. Evaluate expect expression against correlationContext                   │
│     '${ $input.orderId }' → "123"                                           │
│                                                                             │
│  3. Store in ListenerModel.correlationValues                                │
│     correlationValues = '{"orderId":"123"}'                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATABASE                                             │
│                         (lemline_listeners)                                  │
│                                                                             │
│  ┌─────────┬────────────────────┬─────────────────────────┐                 │
│  │ id      │ listen_definition  │ correlation_values      │                 │
│  ├─────────┼────────────────────┼─────────────────────────┤                 │
│  │ abc-123 │ def-456            │ {"orderId":"123"}       │  ← Instance A   │
│  │ abc-789 │ def-456            │ {"orderId":"456"}       │  ← Instance B   │
│  │ abc-000 │ def-456            │ NULL                    │  ← Mode 2       │
│  └─────────┴────────────────────┴─────────────────────────┘                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             │
┌────────────────────────────────────────────│────────────────────────────────┐
│                      EVENT ARRIVAL         │                                 │
│                      (DefinitionListenService)                               │
│                                            ▼                                 │
│  CloudEvent arrives:                                                        │
│    type: "order.shipped"                                                    │
│    data: {orderId: "123", ...}                                              │
│                                                                             │
│  1. Match filters (type, source, etc.)                                      │
│     ✓ type matches "order.shipped"                                          │
│                                                                             │
│  2. Get 'from' expression from workflow definition                          │
│     (via DefinitionCache + nodePosition + filterIndex)                      │
│     from: '${ .orderId }'                                                   │
│                                                                             │
│  3. Evaluate 'from' against event data                                      │
│     '${ .orderId }' with {orderId: "123"} → "123"                           │
│                                                                             │
│  4. Serialize with sorted keys                                              │
│     correlationJson = '{"orderId":"123"}'                                   │
│                                                                             │
│  5. Query database                                                          │
│     SELECT * FROM lemline_listeners                                         │
│     WHERE listen_definition_id = ?                                          │
│       AND (correlation_values IS NULL                                       │
│            OR correlation_values = '{"orderId":"123"}')                     │
│                                                                             │
│  Result: Instance A matches! (Instance B does not)                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Implementation Details

#### 1. Expression Sources

| Expression | Evaluated When | Evaluated Against | Stored In |
|------------|----------------|-------------------|-----------|
| `expect` | Listener creation | `ListenConfig.correlationContext` (workflow scope) | `ListenerModel.correlationValues` |
| `from` | Event arrival | CloudEvent data | Not stored (evaluated on-demand) |

The `from` expressions are retrieved from the workflow definition at event arrival time via:
- `DefinitionCache.getWorkflow()` → Get cached workflow
- `workflow.getNode(nodePosition)` → Navigate to listen task
- `filter.correlate.additionalProperties` → Get correlation definitions

#### 2. Database Query Strategy

```sql
SELECT * FROM lemline_listeners
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND listen_definition_id = ?
  AND (correlation_values IS NULL OR correlation_values = ?)
```

The `correlation_values IS NULL` clause handles **Mode 2** (first-sets-baseline) where the first event
establishes the correlation value.

#### 3. JSON Serialization

Correlation values are serialized with **sorted keys** to ensure consistent string comparison:

```kotlin
fun serializeCorrelationValues(values: Map<String, String>): String {
    val sortedEntries = values.entries.sortedBy { it.key }
    return buildJsonObject {
        for ((key, value) in sortedEntries) {
            put(key, value)
        }
    }.let { Json.encodeToString(it) }
}
```

This ensures `{"a":"1","b":"2"}` always equals `{"a":"1","b":"2"}` regardless of insertion order.

### Components Modified

| Component | Responsibility |
|-----------|----------------|
| `WorkflowEventHandler.handleListenStarted()` | Evaluates `expect` expressions, stores correlation values |
| `WorkflowEventHandler.calculateCorrelationValues()` | Helper to evaluate expect against correlationContext |
| `DefinitionListenService.findMatchingListeners()` | Evaluates `from` expressions, queries with correlation |
| `DefinitionListenService.getEventFilterFromWorkflow()` | Retrieves EventFilter from cached workflow definition |
| `DefinitionListenService.extractCorrelationValues()` | Evaluates `from` expressions against event data |
| `ListenerRepository.findByListenDefinitionIdAndCorrelation()` | Database query with correlation matching |

## Consequences

### Positive

- **Efficient**: Correlation filtering happens at database level, not in-memory
- **Scalable**: Can handle many listeners without loading all into memory
- **Single Source of Truth**: `from` expressions come from workflow definition, not duplicated in filter table
- **Consistent**: Sorted JSON keys ensure reliable string comparison
- **Flexible**: Supports both Mode 1 (expect defined) and Mode 2 (first-sets-baseline)

### Negative

- **Requires Exact JSON Match**: Database string comparison requires identical JSON serialization
- **No Partial Matching**: Cannot query "orderId starts with X" - only exact equality
- **Workflow Must Be Cached**: `from` expressions require the workflow to be in DefinitionCache

### Neutral

- **correlations Column**: `DefinitionListenFilterModel.correlations` still stores the full correlation
  definition (`{from, expect}`) but is not used for matching - serves as documentation/debugging aid

## Alternatives Considered

### 1. Store Both Expressions, Evaluate Both at Event Arrival

Store both `from` and `expect` expressions in `DefinitionListenFilterModel.correlations`, then evaluate both
when an event arrives.

**Rejected because:**
- Requires access to workflow context (`$input`, `$context`) at event arrival time
- Workflow context is not available in `DefinitionListenService`
- Would require passing full workflow state through the event routing layer

### 2. In-Memory Filtering

Load all listeners for a definition, then filter by correlation in application code.

**Rejected because:**
- Does not scale with many listeners
- Loads unnecessary data from database
- Memory pressure under high load

### 3. Database JSON Functions

Use database-specific JSON operators (PostgreSQL `@>`, MySQL `JSON_CONTAINS`) for partial matching.

**Rejected because:**
- Not portable across databases (PostgreSQL, MySQL, H2)
- More complex query construction
- Exact equality is sufficient for correlation use case

### 4. Normalized Correlation Table

Store correlations in a separate table with key-value pairs for indexed lookups.

**Rejected because:**
- Adds complexity (additional table, joins)
- Multiple keys require multiple conditions
- Current approach with JSON string is simpler and sufficient

## References

- [Serverless Workflow Specification - Event Correlation](https://github.com/serverlessworkflow/specification/blob/main/specification.md#event-correlation)
- [ADR-0003 Messaging Architecture](./0003-messaging-architecture.md)
- [Listen Task Documentation](../listen-task.md)
- `lemline-runner/src/main/kotlin/com/lemline/runner/definitions/DefinitionListenService.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/ListenerRepository.kt`
