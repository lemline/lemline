# Plan: Listen Task Implementation

## Goal

Implement the `listen` task from the Serverless Workflow DSL v1.0 specification. This task pauses workflow execution
until one or more external CloudEvents are received that match defined conditions.

## Motivation

1. **Event-driven workflows**: Enable workflows to react to external events (IoT sensors, webhooks, service callbacks)
2. **Spec compliance**: Complete the Serverless Workflow DSL implementation
3. **Async coordination**: Support complex event correlation and multi-event patterns

## Specification Summary

The listen task supports three consumption strategies:

| Strategy        | Behavior                                    | Output       |
|-----------------|---------------------------------------------|--------------|
| `one`           | Wait for a single event matching the filter | Array of 1   |
| `any`           | Wait for first event matching any filter    | Array of 1   |
| `any` + `until` | **Accumulate** events until condition met   | Array of 0-N |
| `any: []`       | **Wildcard** - first event of any type      | Array of 1   |
| `all`           | Wait for one event per filter               | Array of N   |

### The `until` Clause (Accumulation Mode)

**Critical**: `until` enables **accumulation mode**, NOT early termination.

| Mode                  | Behavior                                                   |
|-----------------------|------------------------------------------------------------|
| `any` without `until` | Complete on first match, return 1 event                    |
| `any` with `until`    | **Accumulate** all matches until `until` condition is true |

The `until` condition can be:

1. **Expression**: Evaluated against the accumulated array (e.g., `. | any(.temperature > 38)`)
2. **Event filter**: Stop when this event arrives (e.g., `one: { with: { type: shift.ended } }`)

```yaml
# Accumulate temperature readings until one exceeds threshold
listen:
    to:
        any:
            -   with: { type: temperature }
        until: . | any(.temperature > 38)  # Expression on accumulated array

# Accumulate readings until shift ends
listen:
    to:
        any:
            -   with: { type: temperature }
            -   with: { type: bpm }
        until:
            one:
                with: { type: shift.ended }    # Event filter
```

### Additional Features

- **Event filters**: Match on CloudEvent attributes (`type`, `source`, `subject`, `id`, `datacontenttype`, `dataschema`,
  `time`, `data`)
- **Data filter**: JQ expression evaluated against event payload
- **Correlation**: Link events to workflow instances using shared context
- **Timeout**: Fail if conditions not met within duration
- **Read mode**: Control what's included in output (`data`, `envelope`, `raw`)
- **Foreach**: Process each event as it arrives with nested tasks

## Architecture Overview

### Three-Step Event Processing

When a CloudEvent arrives, we process it in three steps:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 1: Pre-filter workflow definitions (in-memory cache)                   │
│                                                                             │
│ For each cached definition's listen task, check if event matches filters:   │
│   - Literal with.type, with.subject, with.id, with.datacontenttype         │
│   - Literal with.source, with.dataschema, with.time, with.data             │
│   - Expression with.source, with.data, etc. (evaluate and check == true)   │
│                                                                             │
│ → Result: List of matching (namespace, name, version, position) tuples      │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 2: Build correlation hashes (for matching definitions)                 │
│                                                                             │
│ For each matching definition that has correlate.from:                       │
│   - Extract values from event.data using the `from` JQ expressions          │
│   - Build JSON hash with sorted keys                                        │
│                                                                             │
│ → Result: List of correlation hashes                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 3: Query listeners (SQL)                                               │
│                                                                             │
│ SELECT * FROM lemline_listeners                                             │
│ WHERE (namespace, name, version, position) IN ((?,?,?,?), ...)              │
│   AND (correlation_hash IS NULL OR correlation_hash IN (?...))              │
│ FOR UPDATE                                                                  │
│                                                                             │
│ → Result: Matching listener instances to process                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Expression Support by Property

| Property               | Expression Support | Evaluated In | Notes                                              |
|------------------------|--------------------|--------------|----------------------------------------------------|
| `with.type`            | ❌ No               | Step 1       | Exact string match only                            |
| `with.subject`         | ❌ No               | Step 1       | Exact string match only                            |
| `with.id`              | ❌ No               | Step 1       | Exact string match only                            |
| `with.datacontenttype` | ❌ No               | Step 1       | Exact string match only                            |
| `with.source`          | ✅ Yes              | Step 1       | Literal or expression evaluated against source     |
| `with.dataschema`      | ✅ Yes              | Step 1       | Literal or expression evaluated against dataschema |
| `with.time`            | ✅ Yes              | Step 1       | Literal or expression evaluated against timestamp  |
| `with.data`            | ✅ Yes              | Step 1       | Literal or expression evaluated against event.data |
| `correlate.from`       | ✅ Yes              | Step 2       | Extracts value from event.data                     |
| `correlate.expect`     | ✅ Yes              | At creation  | Pre-computed, stored as correlation_hash           |

**Key distinction for `correlate`**:

- `from`: Evaluated against CloudEvent payload (`.` = event data) — at event arrival (step 2)
- `expect`: Evaluated against **workflow context** (`$input`, `$context`, etc.) — at listener creation

### What's Cached vs. What's Stored

| Data                                    | Where                             | Why                                         |
|-----------------------------------------|-----------------------------------|---------------------------------------------|
| `with` filters (literals + expressions) | Definition cache                  | Same for all instances, evaluated in step 1 |
| `correlate.from` paths                  | Definition cache                  | Same for all instances, used in step 2      |
| `correlate.expect` result               | Listener row (`correlation_hash`) | Instance-specific value                     |
| Progress (received events)              | Listener row                      | Instance-specific state                     |

### Expression Evaluation Context Restrictions

**Important**: Filter expressions in `with` properties do **NOT** have access to workflow context:

| Expression Location | Context Available | NOT Available |
|---------------------|-------------------|---------------|
| `with.source` | `.` = source URI string | `$input`, `$context`, `$workflow`, `$task` |
| `with.dataschema` | `.` = dataschema URI string | `$input`, `$context`, `$workflow`, `$task` |
| `with.time` | `.` = timestamp string | `$input`, `$context`, `$workflow`, `$task` |
| `with.data` | `.` = event data payload | `$input`, `$context`, `$workflow`, `$task` |
| `correlate.from` | `.` = event data payload | `$input`, `$context`, `$workflow`, `$task` |
| `correlate.expect` | **Full workflow context** | - |

**Only `correlate.expect`** can access workflow-specific data. This is by design - filter expressions are evaluated
at event arrival time against the cached definition, not against a specific workflow instance.

### Handling Expressions in `with` Properties

When a `with` property contains an expression (detected by `${...}` syntax), it is evaluated in **step 1** against the
cached workflow definition, not stored on the listener.

**Detection:** Check if value starts with `${`

**Evaluation in step 1:**

| Property      | Literal                 | Expression                             |
|---------------|-------------------------|----------------------------------------|
| `with.type`   | `event.type == value`   | N/A (type doesn't support expressions) |
| `with.source` | `event.source == value` | `evaluate(expr, event.source) == true` |
| `with.data`   | `event.data == value`   | `evaluate(expr, event.data) == true`   |
| ...           | ...                     | ...                                    |

**Example:**

```yaml
# In workflow definition
listen:
    to:
        one:
            with:
                type: order.shipped                              # Literal
                source: ${ . | startswith("https://shop.") }     # Expression
```

**Step 1 evaluation:**

```kotlin
// Check if this definition's listen task matches the event
fun matches(event: CloudEvent, entry: DefinitionListen): Boolean {
    // Literal type check
    if (entry.filterType != null && entry.filterType != event.type) {
        return false
    }

    // Expression source check
    if (entry.filterSourceExpr != null) {
        val result = evaluate(entry.filterSourceExpr, event.source)
        if (result != true) return false
    }

    // ... other filters
    return true
}
```

**Key point:** Filter expressions are stored in the **definition cache**, not on listener rows. This keeps the listener
table simple (only instance-specific data).

### Correlation Pattern Caching

Correlation keys (like `orderId`, `customerId`) are defined in **workflow definitions**, not per-instance:

```yaml
# Workflow definition (static)
listen:
    to:
        one:
            with:
                type: order.shipped
            correlate:
                orderId:
                    from: '${ .orderId }'      # ← Same path for all instances
                    expect: '${ $input.orderId }'  # ← Different VALUE per instance
```

**Key insight**: Workflow definitions define BOTH the `with` filters AND the `correlate` patterns together. We cache
them as a unit and use the definition's SQL-matchable filters to pre-filter which patterns apply to an incoming event.

**DefinitionListen (loaded from `lemline_definition_listens`):**

```kotlin
data class DefinitionListen(
    // Definition identity
    val namespace: String,
    val name: String,
    val version: String,
    val position: String,              // e.g., "do[0].waitForShipment"

    val strategy: ListenStrategy,

    // For 'all' strategy: which filter in the list (0-based index)
    // For 'one'/'any': always 0
    val filterIndex: Int,

    // Wildcard flag (for 'any: []')
    val isWildcard: Boolean,

    // Filters - literals (null = any/not specified)
    val filterType: String?,
    val filterSource: String?,
    val filterSubject: String?,
    val filterId: String?,
    val filterDataContentType: String?,
    val filterDataSchema: String?,
    val filterData: String?,           // Literal JSON value

    // Filters - expressions (null = not an expression)
    val filterSourceExpr: String?,
    val filterDataSchemaExpr: String?,
    val filterTimeExpr: String?,
    val filterDataExpr: String?,

    // Correlation patterns
    val correlationPattern: CorrelationPattern?,

    // Until condition (for accumulation mode)
    val untilExpression: String?,      // Expression on accumulated events
    val untilEventFilter: EventFilterConfig?  // Event that terminates accumulation
)

data class CorrelationPattern(
    val keys: List<String>,      // Sorted correlation keys: ["customerId", "orderId"]
    val paths: List<String>      // JQ paths to extract: [".customer.id", ".orderId"]
)

data class EventFilterConfig(
    val type: String?,
    val source: String?,
    val subject: String?,
    val id: String?,
    val datacontenttype: String?,
    val dataschema: String?,
    val dataExpr: String?        // JQ expression for data filter
)
```

### Wildcard Mode (`any: []`)

Empty filter list means "listen to any event regardless of type". This is stored and detected as follows:

**Storage in `lemline_definition_listens`:**
- `strategy = 'any'`
- `is_wildcard = TRUE`
- All filter columns (`filter_type`, `filter_source`, etc.) are `NULL`

**Detection in cache:**
```kotlin
data class DefinitionListen(
    // ...
    val isWildcard: Boolean,     // TRUE if any: [] (empty filter list)
    // ...
)

// In DefinitionListenCache
private fun matchesFilters(event: CloudEvent, entry: DefinitionListen): Boolean {
    // Wildcard matches ANY event
    if (entry.isWildcard) return true

    // Otherwise check filters...
}
```

**Example:**
```yaml
listen:
  to:
    any: []   # Wildcard - completes on first event of ANY type
```

This creates one row in `lemline_definition_listens` with `is_wildcard = TRUE` and all filters `NULL`.

**Cache with scheduled refresh:**

```kotlin
@ApplicationScoped
class DefinitionListenCache(
    private val definitionListenRepository: DefinitionListenRepository
) {
    // Simple list of all definition listens
    private val entries: AtomicReference<List<DefinitionListen>> = AtomicReference(emptyList())

    @PostConstruct
    fun init() {
        refresh()
    }

    /**
     * Scheduled refresh every 5 seconds.
     * Also called by DefinitionRepository after insert/update/delete for immediate consistency.
     */
    @Scheduled(every = "5s")
    fun refresh() {
        val all = runBlocking { definitionListenRepository.findAll() }
        entries.set(all)
        logger.debug { "Refreshed definition listen cache: ${all.size} entries" }
    }

    /**
     * Find all definition listens that match the incoming event.
     * Returns matches with metadata about what kind of match it was.
     */
    fun findMatching(event: CloudEvent): List<DefinitionListenMatch> {
        return entries.get().mapNotNull { entry -> matchEntry(event, entry) }
    }

    private fun matchEntry(event: CloudEvent, entry: DefinitionListen): DefinitionListenMatch? {
        // Check if event matches the UNTIL filter (for accumulation mode termination)
        if (entry.untilEventFilter != null && matchesEventFilter(event, entry.untilEventFilter)) {
            return DefinitionListenMatch(entry, MatchType.UNTIL_EVENT)
        }

        // Check if event matches the main filters
        if (matchesFilters(event, entry)) {
            return DefinitionListenMatch(entry, MatchType.MAIN_FILTER)
        }

        return null
    }

    private fun matchesFilters(event: CloudEvent, entry: DefinitionListen): Boolean {
        // Wildcard matches ANY event
        if (entry.isWildcard) return true

        // Check literal filters
        if (entry.filterType != null && entry.filterType != event.type) return false
        if (entry.filterSource != null && entry.filterSource != event.source.toString()) return false
        if (entry.filterSubject != null && entry.filterSubject != event.subject) return false
        // ... other literal checks

        // Check expression filters
        if (entry.filterSourceExpr != null) {
            if (!evaluateExpr(entry.filterSourceExpr, event.source.toString())) return false
        }
        if (entry.filterDataExpr != null) {
            if (!evaluateExpr(entry.filterDataExpr, event.data)) return false
        }
        // ... other expression checks

        return true
    }

    private fun matchesEventFilter(event: CloudEvent, filter: EventFilterConfig): Boolean {
        if (filter.type != null && filter.type != event.type) return false
        if (filter.source != null && filter.source != event.source.toString()) return false
        if (filter.subject != null && filter.subject != event.subject) return false
        if (filter.dataExpr != null && !evaluateExpr(filter.dataExpr, event.data)) return false
        return true
    }
}

/**
 * Result of matching an event against a definition listen.
 */
data class DefinitionListenMatch(
    val entry: DefinitionListen,
    val matchType: MatchType
)

enum class MatchType {
    MAIN_FILTER,   // Event matches one of the main filters
    UNTIL_EVENT    // Event matches the until termination filter
}
```

**Examples:**

| Definition | `with` filters                                  | Correlation `from`           |
|------------|-------------------------------------------------|------------------------------|
| A          | `type: order.shipped`                           | `.orderId`                   |
| B          | `source: https://shop.com`                      | `.orderId`                   |
| C          | `type: order.shipped, source: https://shop.com` | `.orderId`, `.customerId`    |
| D          | `any: []` (wildcard)                            | `.sessionId`                 |

**Event arrival flow:**

```
Event arrives: { type: "order.shipped", source: "https://shop.com", data: { orderId: "123", customerId: "456" } }

1. Filter cached configs against event:
   - Definition A: type matches ✓
   - Definition B: source matches ✓
   - Definition C: type AND source match ✓
   - Definition D: wildcard ✓

2. For each matching config, extract correlation values from event.data:
   - A: {"orderId":"123"}
   - B: {"orderId":"123"}
   - C: {"customerId":"456","orderId":"123"}
   - D: {"sessionId": null} → skip (missing value)

3. Single SQL query with definition keys + correlation hashes

4. Process matching listeners
```

**Why this works:**

- Simple list iteration is fast for expected scale (hundreds of definitions, not millions)
- All filtering happens in-memory before any database query
- Only patterns from definitions whose filters match the event are used
- Correlation hash building only happens for matching definitions

### Correlation Hash

To handle multiple correlation keys efficiently, we use **JSON with sorted keys**:

**Why JSON instead of delimited strings:**

- Handles special characters in values ("|", "=", quotes, unicode)
- Battle-tested serialization/deserialization
- Deterministic output when keys are sorted

**At listener creation:**

```kotlin
// correlate: { orderId: "ORD-123", customerId: "CUST-456" }
val hash = buildJsonObject {
    correlations.entries
        .sortedBy { it.key }
        .forEach { (key, value) -> put(key, value) }
}.toString()
// → {"customerId":"CUST-456","orderId":"ORD-123"}
```

**At event arrival:**

```kotlin
// For each cached pattern, extract values and build hash
val hashes = correlationPatterns.mapNotNull { (keys, paths) ->
    val values = paths.map { path -> extractPath(event.data, path) }
    if (values.all { it != null }) {
        buildJsonObject {
            keys.zip(values)
                .sortedBy { it.first }
                .forEach { (key, value) -> put(key, value!!) }
        }.toString()
    } else null
}
// → ["{\"orderId\":\"ORD-123\"}", "{\"customerId\":\"CUST-456\",\"orderId\":\"ORD-123\"}"]
```

**SQL query (step 3):**

```sql
SELECT *
FROM lemline_listeners
WHERE outbox_completed_at IS NULL
  AND (workflow_namespace, workflow_name, workflow_version, workflow_position) IN ((?, ?, ?, ?), . . .)
  AND (correlation_hash IS NULL OR correlation_hash IN (?, ?, ?))
    FOR UPDATE
```

### Performance

| Scenario                                  | Query Behavior                                       |
|-------------------------------------------|------------------------------------------------------|
| 100K listeners, same type, unique orderId | Returns ~1 row (indexed by definition + correlation) |
| 100K listeners, different definitions     | Returns ~1 row (indexed by definition identity)      |
| Many definitions match in step 1          | More tuples in IN clause, still fast with index      |

---

## Selected Solution: Definition-Based Filtering with Correlation Hash

### Overview

Event processing follows a clean separation:

- **Step 1**: Filter workflow definitions in-memory (all `with` filters evaluated here)
- **Step 2**: Build correlation hashes for matching definitions
- **Step 3**: Query listeners by definition identity + correlation hash

### Event Processing Flow

```
CloudEvent arrives: {
  source: "https://shop.example.com/orders",
  type: "com.example.order.shipped",
  data: { orderId: "ORD-123", customerId: "CUST-456" }
}
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 1: Pre-filter workflow definitions (in-memory cache)                   │
│                                                                             │
│ For each cached definition's listen task:                                   │
│   Definition A: with.type="com.example.order.shipped" → MATCHES             │
│   Definition B: with.type="com.example.payment.received" → NO MATCH         │
│   Definition C: with.source="${ . | startswith(...) }" → evaluate → MATCHES │
│                                                                             │
│ Result: [(ns1, name1, v1, pos1), (ns2, name2, v2, pos2)]                    │
└─────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 2: Build correlation hashes (for matching definitions only)            │
│                                                                             │
│ Definition A has correlate.from: [".orderId"]                               │
│   → Extract from event.data → {"orderId":"ORD-123"}                         │
│                                                                             │
│ Definition C has correlate.from: [".orderId", ".customerId"]                │
│   → Extract from event.data → {"customerId":"CUST-456","orderId":"ORD-123"} │
│                                                                             │
│ Result: ["{"orderId":"ORD-123"}", "{"customerId":"CUST-456",...}"]          │
└─────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 3: Query listeners (SQL)                                               │
│                                                                             │
│ SELECT * FROM lemline_listeners                                             │
│ WHERE outbox_completed_at IS NULL                                           │
│   AND (workflow_namespace, workflow_name, workflow_version, workflow_position)  │
│       IN (('ns1','name1','v1','pos1'), ('ns2','name2','v2','pos2'))         │
│   AND (correlation_hash IS NULL OR correlation_hash IN (                    │
│         '{"orderId":"ORD-123"}',                                            │
│         '{"customerId":"CUST-456","orderId":"ORD-123"}'                     │
│       ))                                                                    │
│ FOR UPDATE                                                                  │
│                                                                             │
│ Uses composite index → Returns exact matches in ~1-5ms                      │
└─────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 4: Process matching listeners                                          │
│                                                                             │
│ for (listener in results) {                                                 │
│     // No post-filtering needed - all filtering done in step 1              │
│     handleMatch(listener, event)                                            │
│ }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Concrete Example

**Workflow Definition**:

```yaml
do:
    -   waitForShipment:
            listen:
                to:
                    one:
                        with:
                            type: com.example.order.shipped
                        correlate:
                            orderId:
                                from: '${ .orderId }'
                                expect: '${ $input.orderId }'
```

**Step 1: Workflow registration**

Extract listen task configs and cache them:

```kotlin
// Parse workflow → find listen tasks → cache filters + correlation patterns
val definitionListen = DefinitionListen(
    namespace = "examples",
    name = "order-workflow",
    version = "1.0.0",
    position = "do[0].waitForShipment",
    filterType = "com.example.order.shipped",  // Literal
    filterSource = null,                        // Any source
    correlationPattern = CorrelationPattern(
        keys = listOf("orderId"),
        paths = listOf(".orderId")
    )
)
// Add to definition cache
```

**Step 2: Workflow instance reaches listen task**

Create listener with pre-computed correlation hash:

```kotlin
// Evaluate expect expression: ${ $input.orderId } → "ORD-123"
val correlationHash = """{"orderId":"ORD-123"}"""

// Insert listener (no filter columns - those are in definition cache)
INSERT INTO lemline_listeners(
    id, workflow_id, workflow_namespace, workflow_name, workflow_version, workflow_position,
    strategy, correlation_hash, ...
) VALUES(
    'listener-uuid', 'instance-uuid', 'examples', 'order-workflow', '1.0.0', 'do[0].waitForShipment',
    'one', '{"orderId":"ORD-123"}', ...
)
```

**Step 3: CloudEvent arrives**

```kotlin
// STEP 1: Pre-filter definitions (in-memory)
val matchingEntries = definitionListenCache.findMatching(event)
// matchingEntries = [DefinitionListen(ns="examples", name="order-workflow", ...)]

// STEP 2: Build correlation hashes for matching definitions
val hashes = matchingEntries
    .mapNotNull { it.correlationPattern }
    .map { pattern ->
        val values = pattern.paths.map { extractPath(event.data, it) }
        buildJsonObject {
            pattern.keys.zip(values)
                .sortedBy { it.first }
                .forEach { (key, value) -> put(key, value!!) }
        }.toString()
    }
// hashes = ["""{"orderId":"ORD-123"}"""]

// STEP 3: Query listeners
val listeners = listenerRepository.findMatching(
    definitionKeys = matchingEntries.map { (it.namespace, it.name, it.version, it.position) },
    correlationHashes = hashes
)
// Returns exactly the listener waiting for this order
```

### Performance Characteristics

| Scenario                                  | Query Time | JQ Evaluations               |
|-------------------------------------------|------------|------------------------------|
| 100K listeners, unique orderId            | ~2ms       | 0                            |
| 100K listeners, same type, no correlation | ~5ms       | 0                            |
| Listener with `data` filter               | ~2ms       | 1 (on matched listener only) |

### Why This Works

1. **Correlation patterns are static** per workflow definition → can be cached
2. **Correlation values are computed at listener creation** → stored as `correlation_hash`
3. **Event arrival builds the same hash format** → exact string match in SQL
4. **No JQ needed for matching** unless `with.data` filter exists

---

## Database Schema

### Table: `lemline_definition_listens`

A dedicated table for listen task configurations, managed by `DefinitionRepository`.

```sql
CREATE TABLE lemline_definition_listens
(
    -- Identity
    id                      UUID PRIMARY KEY,

    -- Definition reference
    definition_id           UUID         NOT NULL REFERENCES lemline_definitions (id) ON DELETE CASCADE,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,  -- e.g., "do[0].waitForShipment"

    -- Strategy
    strategy                VARCHAR(10)  NOT NULL,  -- 'one', 'any', 'all'

    -- For 'all' strategy: which filter in the list (0-based index)
    -- For 'one'/'any': always 0
    filter_index            INT          NOT NULL DEFAULT 0,

    -- Wildcard flag (for 'any: []')
    is_wildcard             BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Filters - literals (NULL = any/not specified)
    filter_type             VARCHAR(255),
    filter_source           VARCHAR(512),
    filter_subject          VARCHAR(255),
    filter_id               VARCHAR(255),
    filter_datacontenttype  VARCHAR(255),
    filter_dataschema       VARCHAR(512),
    filter_data             TEXT,                   -- Literal JSON value

    -- Filters - expressions (NULL = not an expression)
    filter_source_expr      TEXT,
    filter_dataschema_expr  TEXT,
    filter_time_expr        TEXT,
    filter_data_expr        TEXT,

    -- Correlation pattern (JSON: {"keys": ["orderId"], "paths": [".orderId"]})
    correlation_pattern     TEXT,

    -- Until condition (for accumulation mode)
    until_expression        TEXT,                   -- JQ expression on accumulated events
    until_event_filter      TEXT,                   -- JSON: event filter that terminates accumulation

    -- Timestamps
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Index for definition lookup (cascade deletes, updates)
CREATE INDEX idx_definition_listens_definition
    ON lemline_definition_listens (definition_id);
```

### Extracting Definition Listens from Workflow

For `all` strategy, each filter becomes a **separate row** with its `filter_index`:

```yaml
listen:
  to:
    all:
      - with:
          type: sensor.reading
          data: ${ .sensorId == "temp-1" }
      - with:
          type: sensor.reading
          data: ${ .sensorId == "humidity-1" }
```

Creates **two rows** in `lemline_definition_listens`:

| filter_index | filter_type | filter_data_expr |
|--------------|-------------|------------------|
| 0 | sensor.reading | `.sensorId == "temp-1"` |
| 1 | sensor.reading | `.sensorId == "humidity-1"` |

**Extraction logic:**
```kotlin
fun extractDefinitionListens(definition: WorkflowDefinition): List<DefinitionListen> {
    return definition.workflow.findAllListenTasks().flatMap { (position, listenTask) ->
        when (listenTask.strategy) {
            ListenStrategy.ONE -> listOf(extractSingleFilter(definition, position, listenTask, 0))
            ListenStrategy.ANY -> {
                if (listenTask.filters.isEmpty()) {
                    // Wildcard: any: []
                    listOf(extractWildcard(definition, position, listenTask))
                } else {
                    // Multiple filters, but any one can match - store each with index
                    listenTask.filters.mapIndexed { index, filter ->
                        extractSingleFilter(definition, position, listenTask, index, filter)
                    }
                }
            }
            ListenStrategy.ALL -> {
                // Each filter is a separate row with its index
                listenTask.filters.mapIndexed { index, filter ->
                    extractSingleFilter(definition, position, listenTask, index, filter)
                }
            }
        }
    }
}
```

### DefinitionRepository Updates

The `DefinitionRepository` manages `lemline_definition_listens` lifecycle:

```kotlin
@ApplicationScoped
class DefinitionRepository(
    private val pool: PgPool,
    private val definitionListenRepository: DefinitionListenRepository,
    private val definitionListenCache: DefinitionListenCache
) {
    /**
     * Insert a new workflow definition.
     * Extracts listen tasks and stores them in lemline_definition_listens.
     */
    suspend fun insert(definition: WorkflowDefinition): IDV7 {
        return pool.withTransaction { conn ->
            // 1. Insert definition
            val definitionId = insertDefinition(definition, conn)

            // 2. Extract and insert definition listens
            val listens = extractDefinitionListens(definition)
            definitionListenRepository.insertBatch(definitionId, listens, conn)

            // 3. Refresh cache
            definitionListenCache.refresh()

            definitionId
        }
    }

    /**
     * Update an existing workflow definition.
     * Replaces definition listens (delete old, insert new).
     */
    suspend fun update(definition: WorkflowDefinition): IDV7 {
        return pool.withTransaction { conn ->
            val definitionId = definition.id

            // 1. Update definition
            updateDefinition(definition, conn)

            // 2. Delete old definition listens
            definitionListenRepository.deleteByDefinitionId(definitionId, conn)

            // 3. Extract and insert new definition listens
            val listens = extractDefinitionListens(definition)
            definitionListenRepository.insertBatch(definitionId, listens, conn)

            // 4. Refresh cache
            definitionListenCache.refresh()

            definitionId
        }
    }

    /**
     * Delete a workflow definition.
     * Definition listens are deleted via ON DELETE CASCADE.
     */
    suspend fun delete(definitionId: IDV7) {
        pool.withTransaction { conn ->
            // Delete definition (definition_listens deleted via CASCADE)
            deleteDefinition(definitionId, conn)

            // Refresh cache
            definitionListenCache.refresh()
        }
    }

    /**
     * Extract listen tasks from workflow definition.
     */
    private fun extractDefinitionListens(definition: WorkflowDefinition): List<DefinitionListen> {
        return definition.workflow.findAllListenTasks().map { (position, listenTask) ->
            DefinitionListen(
                namespace = definition.namespace,
                name = definition.name,
                version = definition.version,
                position = position,
                strategy = listenTask.strategy,
                // ... extract filters, correlations, until
            )
        }
    }
}
```

### Cache Refresh Strategy

The cache uses a dual-refresh approach:

1. **Scheduled refresh**: Every 5 seconds (configurable via `lemline.definition-listens.refresh-interval`)
2. **Immediate refresh**: Called by `DefinitionRepository` after insert/update/delete

This ensures:
- Eventually consistent reads (max 5 second delay for external definition changes)
- Immediate consistency for local definition changes

**Populated at workflow registration:**

```kotlin
fun extractDefinitionListens(workflow: Workflow): List<DefinitionListen> {
    return workflow.findAllListenTasks().map { (position, listenTask) ->
        DefinitionListen(
            position = position,
            strategy = listenTask.strategy,
            filters = extractFilters(listenTask.to),
            correlations = extractCorrelations(listenTask.to),
            until = extractUntil(listenTask.to)
        )
    }
}

// Stored in lemline_definition_listens table
```

### Table: `lemline_listeners`

```sql
CREATE TABLE lemline_listeners
(
    -- Identity
    id                      UUID PRIMARY KEY,
    workflow_id             UUID         NOT NULL,           -- Instance ID (for resuming)
    workflow_namespace      VARCHAR(255) NOT NULL,           -- Definition identity (for step 3 filtering)
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_position       TEXT         NOT NULL,           -- Which listen task in the definition
    workflow_state          TEXT         NOT NULL,           -- Serialized workflow state for resumption

    -- Strategy
    strategy                VARCHAR(10)  NOT NULL,           -- 'one', 'any', 'all'

    -- Correlation hash (JSON with sorted keys: {"key1":"val1","key2":"val2"})
    -- Computed from correlate.expect at listener creation (instance-specific)
    correlation_hash        TEXT,                            -- NULL = no correlation required

    -- Progress tracking
    -- For 'all' strategy: JSON object keyed by filter index {"0": event0, "1": event1}
    -- For 'any' accumulation: JSON array [event0, event1, ...]
    -- For 'one'/'any' simple: NULL until completion
    conditions_total        INT          NOT NULL DEFAULT 1, -- Number of filters (for ALL completion check)
    received_events         TEXT,                            -- JSON (format depends on strategy)

    -- Event ID tracking for idempotency
    processed_event_ids     TEXT,                            -- JSON array of CloudEvent IDs already processed

    -- Until condition (accumulation mode) - only expression type stored here
    -- Event-based until is checked in step 1 against cached definition
    until_expression        TEXT,                            -- JQ expression evaluated against received_events
    is_accumulating         BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Correlation state for Mode 2 (first-sets-baseline)
    correlation_state       TEXT,                            -- Dynamic: {"key": "value"} set by first event

    -- Output configuration
    read_mode               VARCHAR(10)  NOT NULL DEFAULT 'data',

    -- Timeout
    timeout_at              TIMESTAMP,

    -- Outbox pattern
    outbox_scheduled_for    TIMESTAMP    NOT NULL,
    outbox_delayed_until    TIMESTAMP,
    outbox_attempt_count    INT          NOT NULL DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMP,
    outbox_failed_at        TIMESTAMP,

    -- Timestamps
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Primary matching index: definition + position + correlation (for step 3 query)
CREATE INDEX idx_listeners_match
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position,
                          correlation_hash) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for timeout processing
CREATE INDEX idx_listeners_timeout
    ON lemline_listeners (timeout_at) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL AND timeout_at IS NOT NULL;

-- Index for workflow instance lookup (e.g., cancel all listeners for an instance)
CREATE INDEX idx_listeners_workflow
    ON lemline_listeners (workflow_id) WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;
```

### Event Storage Format by Strategy

| Strategy | `received_events` Format | Completion Check |
|----------|--------------------------|------------------|
| `one` | `NULL` until match, then `[event]` | First match completes |
| `any` (simple) | `NULL` until match, then `[event]` | First match completes |
| `any` + `until` | JSON array: `[event0, event1, ...]` | Until condition met |
| `all` | JSON object: `{"0": event0, "1": event1}` | `size == conditions_total` |

**Why index-keyed object for `all`:**
- Prevents duplicate events for same condition (key already exists = skip)
- Self-documenting for debugging
- Easy completion check: `map.size == conditions_total`
- Output array reconstructed in filter order: `(0 until total).map { map[it.toString()] }`

## Data Structures

### ListenConfig (lemline-core)

```kotlin
@Serializable
data class ListenConfig(
    val strategy: ListenStrategy,
    val filters: List<EventFilter>,       // Empty list = wildcard (any: [])
    val until: UntilCondition?,           // Accumulation mode termination
    val readMode: ReadMode,
    val timeoutAt: Instant?,
    val correlationContext: JsonElement?
)

/**
 * Until condition for accumulation mode.
 * Can be either an expression evaluated against accumulated events,
 * or an event filter that terminates when matched.
 */
@Serializable
sealed class UntilCondition {
    /** Expression evaluated against accumulated events array (e.g., ". | any(.temp > 38)") */
    @Serializable
    @SerialName("expression")
    data class Expression(val expression: String) : UntilCondition()

    /** Event filter - stop when this event arrives */
    @Serializable
    @SerialName("event")
    data class Event(val filter: EventFilter) : UntilCondition()
}

@Serializable
enum class ListenStrategy { ONE, ANY, ALL }

@Serializable
enum class ReadMode { DATA, ENVELOPE, RAW }

/**
 * Extract content from CloudEvent based on read mode.
 */
fun extractContent(event: CloudEvent, readMode: ReadMode): JsonElement {
    return when (readMode) {
        ReadMode.DATA -> {
            // Extract only the data payload
            event.data?.toJsonElement() ?: JsonNull
        }
        ReadMode.ENVELOPE -> {
            // Include full CloudEvent envelope
            buildJsonObject {
                put("id", event.id)
                put("source", event.source.toString())
                put("type", event.type)
                event.subject?.let { put("subject", it) }
                event.time?.let { put("time", it.toString()) }
                event.dataContentType?.let { put("datacontenttype", it) }
                event.dataSchema?.let { put("dataschema", it.toString()) }
                put("data", event.data?.toJsonElement() ?: JsonNull)
                // Include extension attributes
                event.extensionNames.forEach { name ->
                    put(name, event.getExtension(name)?.toJsonElement() ?: JsonNull)
                }
            }
        }
        ReadMode.RAW -> {
            // Raw bytes as base64 (for binary data)
            // For JSON data, same as DATA
            val bytes = event.data?.toBytes()
            if (bytes != null && event.dataContentType != "application/json") {
                JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
            } else {
                event.data?.toJsonElement() ?: JsonNull
            }
        }
    }
}

@Serializable
data class EventFilter(
    // CloudEvent context attributes
    val type: String?,              // CloudEvent type (exact match or pattern)
    val source: String?,            // CloudEvent source URI
    val subject: String?,           // CloudEvent subject
    val id: String?,                // CloudEvent id
    val datacontenttype: String?,   // Content type (e.g., "application/json")
    val dataschema: String?,        // Data schema URI
    val time: String?,              // Event timestamp (expression)

    // Data filter
    val dataFilter: String?,        // JQ expression evaluated against event.data

    // Correlation
    val correlations: Map<String, CorrelationDef>?
)

@Serializable
data class CorrelationDef(
    val from: String,            // JQ expression to extract from event
    val expect: String?          // Optional: expected value expression
    // - If present: compare against this value
    // - If absent: first event sets baseline (see Correlation Modes)
)
```

### ListenStarted Event (lemline-core)

```kotlin
@Serializable
@SerialName("listenStarted")
data class ListenStarted(
    override val nodeStack: NodeStack,
    val rawOutput: JsonElement,
    val config: ListenConfig
) : Suspension() {
    @Transient
    override val nodePosition = nodeStack.lastPosition

    fun resumeCompleted(events: JsonArray) = WorkflowCommand.ResumeWithCompletedTask(
        nodeStack = nodeStack,
        rawOutput = events,
    )

    fun resumeFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
        nodeStack = nodeStack,
        error = error,
    )
}
```

### ListenerModel (lemline-runner)

```kotlin
@Serializable
data class ListenerModel(
    override val id: IDV7,
    override val instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,

    // Identity - for step 3 filtering
    val workflowId: IDV7,                    // Instance ID (for resuming)
    val workflowNamespace: String,           // Definition identity
    val workflowName: String,
    val workflowVersion: String,
    val workflowPosition: String,            // Which listen task in the definition

    val strategy: ListenStrategy,

    // Correlation - instance-specific (computed from correlate.expect)
    val correlationHash: String?,            // JSON with sorted keys: {"key":"val"}, NULL = no correlation

    // Progress tracking
    val conditionsTotal: Int,                // Number of filters (for ALL completion check)
    var receivedEvents: String?,             // JSON - format depends on strategy (see table above)
    var processedEventIds: String?,          // JSON array of CloudEvent IDs for idempotency

    // Until condition (accumulation mode)
    // Note: Event-based until is checked in step 1 against cached definition
    val untilExpression: String?,            // JQ expression evaluated against receivedEvents
    val isAccumulating: Boolean,             // true if any+until (accumulation mode)

    // Mode 2 correlation state
    var correlationState: String?,           // Dynamic: {"key": "value"} set by first event

    val readMode: ReadMode,
    val timeoutAt: Instant?,

    override val outboxScheduledFor: Instant,
) : OutboxModel() {

    /**
     * Check if an event has already been processed (idempotency).
     */
    fun hasProcessedEvent(eventId: String): Boolean {
        val ids = processedEventIds?.let { Json.parseToJsonElement(it).jsonArray } ?: return false
        return ids.any { it.jsonPrimitive.content == eventId }
    }

    /**
     * Add event ID to processed list.
     */
    fun addProcessedEventId(eventId: String): String {
        val ids = processedEventIds?.let { Json.parseToJsonElement(it).jsonArray.toMutableList() }
            ?: mutableListOf()
        ids.add(JsonPrimitive(eventId))
        return Json.encodeToString(JsonArray(ids))
    }
}
```

## Correlation Modes

The spec defines two correlation modes based on whether `expect` is provided:

### Mode 1: Expected Value (with `expect`)

Compare the extracted event value against a known expected value from workflow context.

```yaml
# Workflow knows orderId upfront - wait for that specific order's event
listen:
    to:
        one:
            with:
                type: order.shipped
            correlate:
                orderId:
                    from: '${ .orderId }'           # Extract from event
                    expect: '${ $input.orderId }'   # Compare against workflow input
```

**Behavior:**

- At listener creation: Evaluate `expect` against workflow context → store as `correlation_value`
- At event arrival: Extract value using `from` → compare against stored `correlation_value`
- **Indexable**: Yes - we know the expected value upfront

### Mode 2: First-Sets-Baseline (without `expect`)

The first matching event's extracted value becomes the baseline for subsequent events.

```yaml
# Don't know which room yet - but need BOTH readings from the SAME room
listen:
    to:
        all:
            -   with:
                    type: temperature
                correlate:
                    roomId:
                        from: '${ .roomid }'    # No expect!
            -   with:
                    type: humidity
                correlate:
                    roomId:
                        from: '${ .roomid }'    # Must match first event's roomid
```

**Behavior:**

1. First event (temperature) arrives with `roomid: "living-room"`
2. Extract `.roomid` → "living-room" → **set as baseline** for `roomId` correlation
3. Second event (humidity) must have `.roomid == "living-room"` to match
4. Events with different roomid are ignored (they belong to different workflow instances)

**Indexable**: Partially - after first event sets baseline, subsequent events can use index

### Implementation Implications

| Aspect                    | Mode 1 (with expect)       | Mode 2 (first-sets-baseline)          |
|---------------------------|----------------------------|---------------------------------------|
| Initial correlation value | Evaluated at creation      | NULL until first event                |
| Indexing in Phase 1       | Yes (known value)          | After first match only                |
| Database update           | None for correlation       | Must store baseline after first match |
| Use case                  | Known entity (order, user) | Unknown grouping (room, session)      |

### Database Schema Update for Mode 2

```sql
-- Track correlation state for first-sets-baseline mode
ALTER TABLE lemline_listeners
    ADD COLUMN correlation_state TEXT;
-- JSON object: { "roomId": "living-room" } (set after first match)
```

### Matching Logic Update

```kotlin
fun matchesCorrelation(
    event: CloudEvent,
    correlation: CorrelationDef,
    correlationContext: JsonElement?,   // Workflow context (for expect)
    correlationState: JsonElement?      // Dynamic state (for first-sets-baseline)
): CorrelationResult {
    val eventData = event.data?.toJsonElement() ?: JsonNull
    val extractedValue = expressionEvaluator.evaluate(correlation.from, eventData)

    return when {
        // Mode 1: Has expect - compare against context
        correlation.expect != null -> {
            val expectedValue = expressionEvaluator.evaluate(correlation.expect, correlationContext!!)
            if (extractedValue == expectedValue) CorrelationResult.MATCH
            else CorrelationResult.NO_MATCH
        }

        // Mode 2: No expect - check/set baseline
        correlationState == null -> {
            // First event - set baseline
            CorrelationResult.MATCH_AND_SET_BASELINE(extractedValue)
        }

        else -> {
            // Subsequent event - compare against baseline
            val baseline = correlationState.jsonObject[correlationKey]
            if (extractedValue == baseline) CorrelationResult.MATCH
            else CorrelationResult.NO_MATCH
        }
    }
}

sealed class CorrelationResult {
    object MATCH : CorrelationResult()
    object NO_MATCH : CorrelationResult()
    data class MATCH_AND_SET_BASELINE(val value: JsonElement) : CorrelationResult()
}
```

### Example: Room Sensor Workflow

```
Scenario: Accumulate temperature + humidity readings from same room
─────────────────────────────────────────────────────────────────────────────

Listener created with:
  strategy: ALL
  conditions: [temperature, humidity]
  correlations: { roomId: { from: ".roomid" } }  // No expect
  correlation_state: NULL

Event 1: { type: "temperature", data: { roomid: "bedroom", temp: 22 } }
  → Extract roomid: "bedroom"
  → correlation_state is NULL → first-sets-baseline
  → Store: correlation_state = { "roomId": "bedroom" }
  → Record condition 0 met
  → Still waiting for humidity

Event 2: { type: "humidity", data: { roomid: "kitchen", humidity: 45 } }
  → Extract roomid: "kitchen"
  → Compare against baseline: "kitchen" != "bedroom"
  → NO MATCH (different room - ignore this event)

Event 3: { type: "humidity", data: { roomid: "bedroom", humidity: 50 } }
  → Extract roomid: "bedroom"
  → Compare against baseline: "bedroom" == "bedroom"
  → MATCH! Record condition 1 met
  → All conditions satisfied → complete listener

Output: [
  { roomid: "bedroom", temp: 22 },
  { roomid: "bedroom", humidity: 50 }
]
─────────────────────────────────────────────────────────────────────────────
```

## Implementation Phases

Implementation is organized into independent phases that can be developed, tested, and verified separately.
Each phase has clear dependencies and deliverables.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DEPENDENCY GRAPH                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 1 ─────────────────────────────────┬─────────────────► Phase 7       │
│  (Core Types & Processor)                 │                   (Listen       │
│                                           │                    Handler)     │
│                                           │                       │         │
│  Phase 2 ──┬──────────────► Phase 3 ──────┼──► Phase 5 ──────────┤         │
│  (DB       │                (Def Listen   │    (Def Repo         │         │
│   Schema)  │                 Storage)     │     Integration)     │         │
│            │                    │         │                      │         │
│            │                    ▼         │                      ▼         │
│            │               Phase 4 ───────┼─────────────────► Phase 8      │
│            │               (Def Listen    │                   (CloudEvent  │
│            │                Cache)        │                    Processing) │
│            │                              │                      │         │
│            └──────────────► Phase 6 ──────┘                      │         │
│                             (Listener                            │         │
│                              Storage)                            │         │
│                                 │                                │         │
│                                 ├────────────────────────────────┤         │
│                                 │                                │         │
│                                 ▼                                ▼         │
│                            Phase 9 ◄───────────────────────► Phase 10      │
│                            (Timeout &                        (Config)      │
│                             Cleanup)                                       │
│                                 │                                │         │
│                                 └────────────┬───────────────────┘         │
│                                              ▼                             │
│                                         Phase 11                           │
│                                         (E2E Tests)                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Phase 1: Core Types & Processor (lemline-core)

**Dependencies**: None
**Deliverable**: Workflow can emit `ListenStarted` event when reaching a listen task

#### 1.1 Core Types
- [ ] Create `ListenConfig`, `EventFilter`, `CorrelationDef` in `ActivityConfigs.kt`
- [ ] Create `ListenStrategy` enum (`ONE`, `ANY`, `ALL`)
- [ ] Create `ReadMode` enum (`DATA`, `ENVELOPE`, `RAW`)
- [ ] Create `UntilCondition` sealed class (`Expression`, `Event`)
- [ ] Add `ListenStarted` event to `WorkflowState.kt`
- [ ] Register serialization for new types

#### 1.2 Listen Processor
- [ ] Create `ListenProcessor` extending `NodeProcessor<ListenTask, ListenState>`
- [ ] Implement `stateEnterFromParent()` to create initial state
- [ ] Implement `startedEvent()` to:
    - Parse listen configuration from DSL
    - Resolve expressions in filters (respecting context restrictions)
    - Calculate timeout timestamp
    - Build correlation context from workflow data
    - Return `ListenStarted` event
- [ ] Register processor in `Node.kt` factory

#### 1.3 Tests
- [ ] `ListenConfigTest` - serialization/deserialization
- [ ] `ListenProcessorTest`:
    - Emits `ListenStarted` for `one` strategy
    - Emits `ListenStarted` for `any` strategy (with and without `until`)
    - Emits `ListenStarted` for `all` strategy
    - Correctly parses `correlate.expect` against workflow context
    - Filter expressions evaluated correctly
    - Timeout calculated from duration

**Verification**: Run `./gradlew :lemline-core:test --tests "*Listen*"`

---

### Phase 2: Database Schema (lemline-runner)

**Dependencies**: None (can run in parallel with Phase 1)
**Deliverable**: Database tables exist for definition listens and listeners

#### 2.1 Definition Listens Table
- [ ] Create `V008__Create_definition_listens_table.sql` for PostgreSQL
- [ ] Create `V008__Create_definition_listens_table.sql` for MySQL
- [ ] Create `V008__Create_definition_listens_table.sql` for H2
- [ ] Include `filter_index` and `is_wildcard` columns
- [ ] Add `definition_id` foreign key with `ON DELETE CASCADE`
- [ ] Add index on `definition_id`

#### 2.2 Listeners Table
- [ ] Create `V009__Create_listeners_table.sql` for PostgreSQL
- [ ] Create `V009__Create_listeners_table.sql` for MySQL
- [ ] Create `V009__Create_listeners_table.sql` for H2
- [ ] Include `processed_event_ids` column for idempotency
- [ ] Add composite index on definition identity + correlation hash
- [ ] Add partial indexes excluding completed listeners
- [ ] Add timeout index

#### 2.3 Tests
- [ ] Verify migrations run successfully on all databases
- [ ] Verify foreign key cascade works (delete definition → deletes definition_listens)

**Verification**: Run `./gradlew :lemline-runner:test --tests "*Migration*"` or start app and check schema

---

### Phase 3: Definition Listen Storage (lemline-runner)

**Dependencies**: Phase 2
**Deliverable**: Can CRUD definition listen entries in database

#### 3.1 Model
- [ ] Create `DefinitionListenModel` in `models/`
- [ ] Include all fields from schema
- [ ] Add helper methods for JSON serialization of correlation patterns

#### 3.2 Repository
- [ ] Create `DefinitionListenRepository` with:
    - `suspend fun findAll(): List<DefinitionListenModel>`
    - `suspend fun findByDefinitionId(definitionId: IDV7): List<DefinitionListenModel>`
    - `suspend fun insertBatch(definitionId: IDV7, listens: List<DefinitionListenModel>)`
    - `suspend fun deleteByDefinitionId(definitionId: IDV7)`

#### 3.3 Tests
- [ ] `DefinitionListenRepositoryTest`:
    - Insert single definition listen
    - Insert batch of definition listens
    - Find all returns inserted entries
    - Find by definition ID filters correctly
    - Delete by definition ID removes all entries
    - Cascade delete works when definition is deleted

**Verification**: Run `./gradlew :lemline-runner:test --tests "*DefinitionListenRepository*"`

---

### Phase 4: Definition Listen Cache (lemline-runner)

**Dependencies**: Phase 3
**Deliverable**: Can match incoming CloudEvents against cached definition listens

#### 4.1 Cache Implementation
- [ ] Create `DefinitionListenCache`:
    - `entries: AtomicReference<List<DefinitionListenModel>>`
    - `@PostConstruct fun init()` - initial load
    - `@Scheduled(every = "5s") fun refresh()` - periodic refresh
    - `fun findMatching(event: CloudEvent): List<DefinitionListenMatch>`

#### 4.2 Matching Logic
- [ ] Implement `matchesFilters(event, entry)`:
    - Literal filter matching (type, source, subject, id, datacontenttype)
    - Expression filter matching (source, dataschema, time, data)
    - Wildcard detection (`isWildcard = true`)
- [ ] Implement `matchesEventFilter(event, filter)` for until event matching
- [ ] Return `DefinitionListenMatch` with `MatchType` (MAIN_FILTER or UNTIL_EVENT)

#### 4.3 Tests
- [ ] `DefinitionListenCacheTest`:
    - Cache loads entries on init
    - `findMatching()` with literal type filter
    - `findMatching()` with literal source filter
    - `findMatching()` with expression data filter (`${ .temperature > 38 }`)
    - `findMatching()` with wildcard (`any: []`)
    - `findMatching()` returns UNTIL_EVENT match type
    - `findMatching()` returns empty list when no match
    - Cache refresh picks up new entries
    - Multiple filters for same definition (all strategy)

**Verification**: Run `./gradlew :lemline-runner:test --tests "*DefinitionListenCache*"`

---

### Phase 5: Definition Repository Integration (lemline-runner)

**Dependencies**: Phase 3, Phase 4
**Deliverable**: Workflow definitions automatically populate `lemline_definition_listens`

#### 5.1 Definition Listen Extraction
- [ ] Create `DefinitionListenExtractor`:
    - `fun extract(definition: WorkflowDefinition): List<DefinitionListenModel>`
    - Traverse workflow tree to find listen tasks
    - For `all` strategy: create one row per filter with `filter_index`
    - For `any: []`: create one row with `is_wildcard = true`
    - Extract correlation patterns

#### 5.2 Repository Updates
- [ ] Update `DefinitionRepository.insert()`:
    - Extract definition listens after inserting definition
    - Call `definitionListenRepository.insertBatch()`
    - Call `definitionListenCache.refresh()`
- [ ] Update `DefinitionRepository.update()`:
    - Delete old definition listens
    - Extract and insert new definition listens
    - Refresh cache
- [ ] Update `DefinitionRepository.delete()`:
    - Definition listens deleted via CASCADE
    - Refresh cache

#### 5.3 Tests
- [ ] `DefinitionRepositoryListenTest`:
    - Insert definition with listen task → definition_listens created
    - Insert definition with `all` strategy → multiple rows with filter_index
    - Insert definition with `any: []` → row with is_wildcard=true
    - Update definition → old definition_listens replaced
    - Delete definition → definition_listens cascaded
    - Cache refreshed after each operation

**Verification**: Run `./gradlew :lemline-runner:test --tests "*DefinitionRepository*Listen*"`

---

### Phase 6: Listener Storage (lemline-runner)

**Dependencies**: Phase 2
**Deliverable**: Can CRUD listener entries in database

#### 6.1 Model
- [ ] Create `ListenerModel` in `models/` extending `OutboxModel`
- [ ] Include all fields from schema
- [ ] Add `hasProcessedEvent(eventId)` method
- [ ] Add `addProcessedEventId(eventId)` method

#### 6.2 Repository
- [ ] Create `ListenerRepository` extending `OutboxRepository<ListenerModel>`:
    - `suspend fun findByUUID(uuid: IDV7): ListenerModel?`
    - `suspend fun insert(listener: ListenerModel)`
    - `suspend fun findMatching(definitionKeys: List<DefinitionKey>, correlationHashes: List<String>): List<ListenerModel>`
    - `suspend fun findByIdForUpdate(id: IDV7, conn: SqlConnection): ListenerModel?`
    - `suspend fun updateProgress(id: IDV7, receivedEvents: String, processedEventIds: String, correlationState: String?, conn: SqlConnection)`
    - `suspend fun markCompleted(id: IDV7, conn: SqlConnection)`
    - `suspend fun findTimedOut(limit: Int): List<ListenerModel>`

#### 6.3 Tests
- [ ] `ListenerRepositoryTest`:
    - Insert and find by UUID
    - `findMatching` with definition keys only
    - `findMatching` with correlation hash
    - `findMatching` excludes completed listeners
    - `findByIdForUpdate` locks row
    - `updateProgress` updates received_events and processed_event_ids
    - `findTimedOut` returns listeners past timeout_at

**Verification**: Run `./gradlew :lemline-runner:test --tests "*ListenerRepository*"`

---

### Phase 7: Listen Event Handler (lemline-runner)

**Dependencies**: Phase 1, Phase 6
**Deliverable**: Workflow reaching listen task creates listener row in database

#### 7.1 Handler Implementation
- [ ] Add `handleListenStarted(event: ListenStarted)` to `WorkflowEventHandler`
- [ ] Compute correlation hash from `correlate.expect` values (if present)
- [ ] Create `ListenerModel` from event
- [ ] Insert listener into database

#### 7.2 Tests
- [ ] `ListenEventHandlerTest`:
    - `ListenStarted` creates listener row
    - Correlation hash computed correctly (JSON with sorted keys)
    - No correlation → `correlation_hash = NULL`
    - `conditions_total` set correctly for `all` strategy
    - `is_accumulating` set for `any` with `until`
    - `timeout_at` set from config

**Verification**: Run `./gradlew :lemline-runner:test --tests "*ListenEventHandler*"`

---

### Phase 8: CloudEvent Processing (lemline-runner)

**Dependencies**: Phase 4, Phase 6
**Deliverable**: CloudEvents can match and complete listeners

#### 8.1 Subscriber
- [ ] Create `CloudEventSubscriber` for `cloudevents-in` channel
- [ ] Parse incoming CloudEvents
- [ ] Delegate to `CloudEventHandler`

#### 8.2 Handler - Core Logic
- [ ] Create `CloudEventHandler`:
    - `suspend fun handleCloudEvent(event: CloudEvent)`
    - Step 1: `definitionListenCache.findMatching(event)`
    - Step 2: Build correlation hashes for matching definitions
    - Step 3: `listenerRepository.findMatching(definitionKeys, hashes)`

#### 8.3 Handler - Strategy Logic
- [ ] Implement `handleOneMatch()` - complete on first match
- [ ] Implement `handleAnyMatch()`:
    - Simple mode: complete on first match
    - Accumulation mode: add to array, check until condition
- [ ] Implement `handleAllMatch()`:
    - Index-keyed JSON object storage
    - Re-read after lock (race condition protection)
    - Complete when `map.size == conditions_total`

#### 8.4 Handler - Supporting Logic
- [ ] Implement `handleUntilEvent()` - complete with accumulated events (may be empty)
- [ ] Implement Mode 2 correlation (first-sets-baseline)
- [ ] Implement `extractContent(event, readMode)` for DATA/ENVELOPE/RAW
- [ ] Implement `completeListener()` - emit resume command

#### 8.5 Tests
- [ ] `CloudEventHandlerTest`:
    - Strategy=one completes on first match
    - Strategy=any completes on any match
    - Strategy=any with until expression (accumulation)
    - Strategy=any with until event (accumulation)
    - Strategy=all with index-keyed storage
    - Strategy=all with same type but different data filters
    - Until event arrives first → empty output
    - Correlation Mode 1 (with expect)
    - Correlation Mode 2 (first-sets-baseline)
    - Wildcard mode (`any: []`)
    - Duplicate event handling (same event ID)
    - Race condition: concurrent events for same listener
    - Read mode: data, envelope, raw

**Verification**: Run `./gradlew :lemline-runner:test --tests "*CloudEventHandler*"`

---

### Phase 9: Timeout & Cleanup (lemline-runner)

**Dependencies**: Phase 6
**Deliverable**: Timed out listeners fail, old listeners cleaned up

#### 9.1 Timeout Outbox
- [ ] Create `ListenerTimeoutOutbox` extending `AbstractOutbox<ListenerModel>`
- [ ] Implement `findEntitiesToProcess()` - query `timeout_at < NOW()`
- [ ] Implement `processEntity()`:
    - Create timeout error (type, status 408, title, detail)
    - Emit `ResumeWithFailedTask` command
    - Mark listener as failed

```kotlin
fun createTimeoutError(listener: ListenerModel): InternalException.Error {
    return InternalException.Error(
        type = "https://serverlessworkflow.io/errors/timeout",
        status = 408,
        title = "Listen Timeout",
        detail = "Listen task '${listener.workflowPosition}' timed out after waiting for events",
        instance = "/workflows/${listener.workflowNamespace}/${listener.workflowName}/${listener.workflowId}"
    )
}
```

#### 9.2 Cleaner
- [ ] Create `ListenerCleaner` extending `AbstractCleaner<ListenerModel>`
- [ ] Clean up completed/failed listeners after retention period

#### 9.3 Tests
- [ ] `ListenerTimeoutOutboxTest`:
    - Finds listeners past timeout
    - Emits correct error format
    - Marks listener as failed
- [ ] `ListenerCleanerTest`:
    - Removes old completed listeners
    - Removes old failed listeners
    - Keeps recent listeners

**Verification**: Run `./gradlew :lemline-runner:test --tests "*ListenerTimeout*" --tests "*ListenerCleaner*"`

---

### Phase 10: Configuration (lemline-runner)

**Dependencies**: None (can run in parallel)
**Deliverable**: CloudEvent channel and listener settings configurable

#### 10.1 Channel Configuration
- [ ] Add `cloudevents-topic` and `cloudevents-group-id` to Kafka config
- [ ] Add `cloudevents-exchange` and `cloudevents-queue` to RabbitMQ config
- [ ] Update `toQuarkusProperties()` to generate channel config

```yaml
lemline:
  messaging:
    kafka:
      cloudevents-topic: lemline-cloudevents
      cloudevents-group-id: lemline-cloudevents-group
    rabbitmq:
      cloudevents-exchange: lemline.cloudevents
      cloudevents-queue: lemline.cloudevents.queue
```

#### 10.2 Listener Configuration
- [ ] Add `lemline.listeners.timeout-check-interval` (default: 10s)
- [ ] Add `lemline.listeners.cleanup-retention` (default: 7d)
- [ ] Add `lemline.definition-listens.refresh-interval` (default: 5s)

#### 10.3 Tests
- [ ] `ListenerConfigurationTest`:
    - Default values applied
    - Custom values override defaults
    - Quarkus properties generated correctly

**Verification**: Run `./gradlew :lemline-runner:test --tests "*ListenerConfiguration*"`

---

### Phase 11: End-to-End Tests (lemline-runner)

**Dependencies**: All previous phases
**Deliverable**: Listen task works in complete workflows

#### 11.1 Workflow Tests
- [ ] Simple listen with `one` strategy
- [ ] Listen with `any` strategy (multiple event types)
- [ ] Listen with `all` strategy (wait for multiple events)
- [ ] Listen with correlation to specific workflow instance
- [ ] Listen with accumulation until expression threshold
- [ ] Listen with accumulation until termination event
- [ ] Listen timeout triggers workflow error handling
- [ ] Multiple workflow instances listening for same event type

#### 11.2 Integration Scenarios
- [ ] Emit task publishes to same channel as listen consumes
- [ ] Worker restart: listener state persisted and recovered
- [ ] High concurrency: multiple events for same listener

**Verification**: Run `./gradlew :lemline-runner:test --tests "*ListenE2E*"`

---

## Phase Summary

| Phase | Module | Dependencies | Key Deliverable |
|-------|--------|--------------|-----------------|
| 1 | lemline-core | None | `ListenStarted` event emission |
| 2 | lemline-runner | None | Database tables |
| 3 | lemline-runner | 2 | Definition listen CRUD |
| 4 | lemline-runner | 3 | Event-to-definition matching |
| 5 | lemline-runner | 3, 4 | Auto-populate on definition save |
| 6 | lemline-runner | 2 | Listener CRUD |
| 7 | lemline-runner | 1, 6 | Workflow creates listener |
| 8 | lemline-runner | 4, 6 | CloudEvent completes listener |
| 9 | lemline-runner | 6 | Timeout and cleanup |
| 10 | lemline-runner | None | Configuration |
| 11 | lemline-runner | All | End-to-end validation |

**Parallel execution possible:**
- Phase 1 and Phase 2 (different modules)
- Phase 3 and Phase 6 (both depend only on Phase 2)
- Phase 10 (independent)

## Concurrency Model: Optimistic Locking

### Why NOT `FOR UPDATE SKIP LOCKED`

Initially, we considered using `FOR UPDATE SKIP LOCKED` in Phase 1 to prevent multiple workers from
processing the same listener simultaneously. However, this causes **event loss**:

```
Problem scenario:
─────────────────────────────────────────────────────────────────────────────
t=0   Event E1 arrives (matches Listener L1, condition 0)
t=1   Worker A: Phase 1 query → finds L1 → locks L1
t=2   Event E2 arrives (matches Listener L1, condition 1)
t=3   Worker B: Phase 1 query → L1 is locked → SKIP LOCKED → misses L1!
t=4   Worker A: updates L1 with E1, releases lock
t=5   E2 is LOST - L1 never sees it
─────────────────────────────────────────────────────────────────────────────

For strategy=ALL: L1 will never complete (condition 1 never recorded)
For strategy=ANY: Might be acceptable, but still loses valid events
```

### Solution: Optimistic Concurrency Control

Instead, we use a **read-then-lock-then-check-then-update** pattern:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Step 1 + 2: Definition filtering + hash building (in-memory, no DB)         │
└─────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Step 3: SELECT (no lock) - Multiple workers can read same candidates        │
│                                                                             │
│   SELECT * FROM lemline_listeners                                           │
│   WHERE outbox_completed_at IS NULL                                         │
│     AND (workflow_namespace, workflow_name, workflow_version, workflow_position) IN (...)  │
│     AND (correlation_hash IS NULL OR correlation_hash IN (...))             │
│   -- NO locking here!                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 3: Lock-Check-Update (per matching listener, inside transaction)      │
│                                                                             │
│   BEGIN TRANSACTION                                                         │
│                                                                             │
│   SELECT * FROM lemline_listeners WHERE id = $1 FOR UPDATE                  │
│   -- Lock THIS SPECIFIC row (wait if locked, don't skip)                    │
│                                                                             │
│   -- Validate before update:                                                │
│   IF outbox_completed_at IS NOT NULL → already done, skip                   │
│   IF strategy='all' AND condition already met → skip                        │
│   IF event_id already in received_events → duplicate, skip                  │
│                                                                             │
│   -- All checks passed → update                                             │
│   UPDATE lemline_listeners SET ... WHERE id = $1                            │
│                                                                             │
│   COMMIT                                                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Implementation

```kotlin
suspend fun processListener(
    listener: ListenerModel,
    event: CloudEvent,
    matchingEntry: DefinitionListen  // From step 1
) {
    // We know which condition matched from step 1
    val matchIndex = matchingEntry.matchedConditionIndex

    // Lock-Check-Update
    listenerRepository.withTransaction { conn ->
        // Lock THIS SPECIFIC listener (wait if necessary)
        val locked = listenerRepository.findByIdForUpdate(listener.id, conn)

        // Check 1: Still active?
        if (locked == null || locked.outboxCompletedAt != null) {
            logger.debug { "Listener ${listener.id} already completed" }
            return@withTransaction
        }

        // Check 2: For ALL strategy - condition not already satisfied?
        if (locked.strategy == ListenStrategy.ALL) {
            val bit = 1L shl matchIndex
            if (locked.conditionsMet and bit != 0L) {
                logger.debug { "Condition $matchIndex already satisfied" }
                return@withTransaction
            }
        }

        // Check 3: Event not already processed? (idempotency)
        if (locked.hasProcessedEvent(event.id)) {
            logger.debug { "Event ${event.id} already processed" }
            return@withTransaction
        }

        // All checks passed - update
        handleMatch(locked, matchIndex, event, conn)
    }
}
```

### Concurrency Scenarios

| Scenario                                                          | What Happens                              | Result                  |
|-------------------------------------------------------------------|-------------------------------------------|-------------------------|
| Two workers, same event, same listener                            | First commits, second sees `completed`    | Correct (deduplicated)  |
| Two workers, different events, strategy=ONE                       | First commits, second sees `completed`    | Correct (first wins)    |
| Two workers, different events, strategy=ALL, different conditions | Both update different bitmap bits         | Correct (both recorded) |
| Two workers, different events, strategy=ALL, same condition       | First commits, second sees bit set        | Correct (deduplicated)  |
| Same event redelivered (broker retry)                             | Second sees event ID in `received_events` | Correct (idempotent)    |

### Lock Duration Analysis

The lock is held only during Phase 3 (single row, brief update):

```
Without optimization:
  Lock duration = Phase 1 + Phase 2 + Phase 3 = 5ms + 50ms + 2ms = 57ms ❌

With optimistic locking:
  Lock duration = Phase 3 only = 2ms ✓
```

Short lock duration means:

- Minimal contention even with high concurrency
- Workers rarely wait for each other
- Better throughput

---

## Query (Step 3)

### Main Query (all databases)

```sql
-- Query by definition identity + correlation hash
-- No filter columns - all filtering done in step 1 (in-memory cache)
SELECT *
FROM lemline_listeners
WHERE outbox_completed_at IS NULL
  AND outbox_failed_at IS NULL
  AND (workflow_namespace, workflow_name, workflow_version, workflow_position) IN (
                                                                                   (?, ?, ?, ?), -- Definition keys from step 1
                                                                                   (?, ?, ?, ?)
    )
  AND (correlation_hash IS NULL OR correlation_hash IN (?, ?)) -- Hashes from step 2
    FOR UPDATE
```

### No Post-Filter Needed

All `with` filter evaluation (including expressions) happens in **step 1** against the definition cache.
The SQL query in step 3 only needs to match:

- Definition identity (namespace, name, version, position)
- Correlation hash (instance-specific)

### Mode 2 Correlation (First-Sets-Baseline)

For listeners without `expect`, the first event sets the baseline:

```kotlin
/**
 * Handle Mode 2 correlation where first event sets the baseline.
 * Only applies when listener.correlationState is null but correlations are defined.
 */
fun handleMode2Correlation(
    listener: ListenerModel,
    event: CloudEvent,
    conn: SqlConnection
): Boolean {
    // If no correlation state and listener uses Mode 2, set baseline
    if (listener.correlationState == null && listener.hasMode2Correlations()) {
        val baseline = extractCorrelationsFromEvent(event, listener.correlationPaths)
        listenerRepository.updateCorrelationState(listener.id, baseline, conn)
        return true
    }

    // If correlation state exists, verify event matches
    if (listener.correlationState != null) {
        val eventValues = extractCorrelationsFromEvent(event, listener.correlationPaths)
        return eventValues == Json.parseToJsonElement(listener.correlationState)
    }

    return true
}
```

## Strategy Logic

### ONE Strategy

```kotlin
fun handleOneMatch(listener: ListenerModel, event: CloudEvent, conn: SqlConnection) {
    val content = extractContent(event, listener.readMode)
    completeListener(listener, JsonArray(listOf(content)), conn)
}
```

### ANY Strategy

The `any` strategy has two modes:

1. **Simple mode** (no `until`): Complete on first match
2. **Accumulation mode** (with `until`): Collect events until condition met

```kotlin
fun handleAnyMatch(
    listener: ListenerModel,
    event: CloudEvent,
    matchResult: FilterMatchResult,
    conn: SqlConnection
) {
    val content = extractContent(event, listener.readMode)

    if (!listener.isAccumulating) {
        // Simple mode: first match completes
        completeListener(listener, JsonArray(listOf(content)), conn)
        return
    }

    // Accumulation mode: add event to collected array
    val newEvents = appendToJsonArray(listener.receivedEvents, content)
    val eventsArray = parseJsonArray(newEvents)

    // Check until condition
    val shouldComplete = when (listener.untilType) {
        UntilType.EXPRESSION -> {
            // Evaluate expression against accumulated events
            val result = expressionEvaluator.evaluate(listener.untilExpression!!, eventsArray)
            result is JsonPrimitive && result.booleanOrNull == true
        }
        UntilType.EVENT -> {
            // Check if this event matches the until filter (handled separately)
            false  // Until event matching is checked before main filters
        }
        null -> false
    }

    if (shouldComplete) {
        // Until condition met - complete with accumulated events
        listener.receivedEvents = newEvents
        completeListener(listener, eventsArray, conn)
    } else {
        // Continue accumulating
        listenerRepository.updateProgress(listener.id, 0L, newEvents, null, conn)
    }
}

/**
 * Handle until event (for accumulation mode with until: { one: { with: ... } })
 * Called when an event matches the until filter, BEFORE checking main filters.
 */
fun handleUntilEvent(listener: ListenerModel, conn: SqlConnection) {
    // Until event received - complete with accumulated events (excluding until event)
    val eventsArray = listener.receivedEvents?.let { parseJsonArray(it) } ?: JsonArray(emptyList())
    completeListener(listener, eventsArray, conn)
}
```

### Wildcard Mode (`any: []`)

Empty filter list means accept any event. This is detected in **step 1** against the definition cache:

```kotlin
// In cache
fun isWildcard(entry: DefinitionListen): Boolean {
    return entry.strategy == ListenStrategy.ANY &&
        entry.filterType == null &&
        entry.filterSource == null &&
        entry.filterSubject == null &&
        entry.filterData == null
    // ... all filters are null
}

// In step 1: wildcard definitions always match
fun findMatching(event: CloudEvent): List<DefinitionListen> {
    return entries.get().filter { entry ->
        isWildcard(entry) || matchesFilters(event, entry)
    }
}
```

### ALL Strategy

Uses **index-keyed JSON object** for tracking which conditions have been satisfied:

```kotlin
fun handleAllMatch(
    listener: ListenerModel,
    filterIndex: Int,              // Which filter matched (from DefinitionListen.filterIndex)
    matchResult: FilterMatchResult,  // Contains baselines to set
    event: CloudEvent,
    conn: SqlConnection
) {
    val key = filterIndex.toString()
    val eventsMap = listener.receivedEvents?.let {
        Json.parseToJsonElement(it).jsonObject
    } ?: JsonObject(emptyMap())

    // Check if this condition already satisfied
    if (key in eventsMap) {
        logger.debug { "Condition $filterIndex already satisfied for listener ${listener.id}" }
        return
    }

    // Add event to map
    val content = extractContent(event, listener.readMode)
    val newMap = JsonObject(eventsMap + (key to content))
    val newEventsJson = Json.encodeToString(newMap)

    // Update correlation state if Mode 2 baselines were set
    val newCorrelationState = if (matchResult.baselinesToSet.isNotEmpty()) {
        val current = listener.correlationState?.let { Json.parseToJsonElement(it).jsonObject }
            ?: JsonObject(emptyMap())
        val merged = JsonObject(current + matchResult.baselinesToSet)
        Json.encodeToString(merged)
    } else {
        listener.correlationState
    }

    // Track processed event ID
    val newProcessedIds = listener.addProcessedEventId(event.id)

    if (newMap.size == listener.conditionsTotal) {
        // All conditions satisfied - reconstruct array in filter order
        val orderedEvents = (0 until listener.conditionsTotal)
            .map { idx -> newMap[idx.toString()]!! }

        listener.receivedEvents = newEventsJson
        listener.correlationState = newCorrelationState
        listener.processedEventIds = newProcessedIds
        completeListener(listener, JsonArray(orderedEvents), conn)
    } else {
        // Update progress, continue waiting
        listenerRepository.updateProgress(
            listener.id,
            newEventsJson,
            newProcessedIds,
            newCorrelationState,
            conn
        )
    }
}
```

**Example flow:**
```
Listener created: conditionsTotal=3, receivedEvents=null

Event matches filter 1:
  eventsMap = {} (empty)
  key "1" not in map → add it
  newMap = {"1": event1}
  size=1 < 3 → continue waiting

Event matches filter 0:
  eventsMap = {"1": event1}
  key "0" not in map → add it
  newMap = {"0": event0, "1": event1}
  size=2 < 3 → continue waiting

Event matches filter 2:
  eventsMap = {"0": event0, "1": event1}
  key "2" not in map → add it
  newMap = {"0": event0, "1": event1, "2": event2}
  size=3 == 3 → COMPLETE!
  Output: [event0, event1, event2]  // Reconstructed in order
```

## Foreach Processing

The `foreach` property allows executing nested tasks for each event **as it arrives**:

```yaml
listen:
    to:
        any:
            -   with: { type: temperature }
        until: . | any(.temperature > 38)
    foreach:
        item: event
        at: index      # optional
        do:
            -   logReading:
                    call: http
                    with:
                        method: POST
                        endpoint: https://api.example.com/readings
                        body: ${ $event }
```

### Implementation Approach

Foreach processing adds complexity because nested tasks execute **during** event collection, not after:

```
Timeline with foreach:
─────────────────────────────────────────────────────────────────────────────
t=0   Listen starts
t=2   Event 1 arrives (temp=37)
        → Execute foreach.do with $event = event1
        → Add to accumulated array
t=5   Event 2 arrives (temp=36)
        → Execute foreach.do with $event = event2
        → Add to accumulated array
t=7   Event 3 arrives (temp=39)
        → Execute foreach.do with $event = event3
        → Add to accumulated array
        → Until expression (. | any(.temp > 38)) = true
        → Complete listener
─────────────────────────────────────────────────────────────────────────────
```

### Design Options

| Option                  | Description                                                | Complexity                     |
|-------------------------|------------------------------------------------------------|--------------------------------|
| **A: Inline execution** | Execute nested tasks synchronously before accumulating     | High - blocks event processing |
| **B: Fire-and-forget**  | Spawn child workflow for each event, continue accumulating | Medium - loose coupling        |
| **C: Deferred**         | Store events, execute foreach after completion             | Low - but doesn't match spec   |

**Recommended: Option B (Fire-and-forget)**

```kotlin
fun handleEventWithForeach(
    listener: ListenerModel,
    event: CloudEvent,
    conn: SqlConnection
) {
    // 1. If foreach defined, spawn child workflow for this event
    if (listener.foreachConfig != null) {
        val childInput = buildForeachInput(event, listener)
        workflowStarter.startChild(
            definition = listener.foreachConfig.doTasks,
            input = childInput,
            // Fire-and-forget: don't wait for completion
        )
    }

    // 2. Continue with normal accumulation logic
    handleAnyMatch(listener, event, matchResult, conn)
}
```

### Scope for Phase 1

> **Note**: `foreach` adds significant complexity. Consider deferring to Phase 2:
>
> **Phase 1**: Implement listen without foreach
> **Phase 2**: Add foreach support with fire-and-forget semantics

The core listen functionality (strategies, correlation, until) provides value without foreach.

## Edge Cases

### 1. Race Conditions

Multiple events arriving simultaneously for the same listener:

- Phase 1 (read) has no locks - all workers see the listener
- Phase 3 uses `FOR UPDATE` (wait, not skip) on the specific listener ID
- Check-before-update ensures correct handling (see Concurrency Model section)

### 2. Until Event vs Main Filters

When `until` is an event filter, it's checked in step 1 and handled before main filters:

```kotlin
// In CloudEventHandler
fun handleMatch(match: DefinitionListenMatch, listener: ListenerModel, event: CloudEvent, conn: SqlConnection) {
    when (match.matchType) {
        MatchType.UNTIL_EVENT -> {
            // Until event received - complete with accumulated events (excluding until event)
            // Output may be empty if no events were accumulated yet
            handleUntilEvent(listener, conn)
        }
        MatchType.MAIN_FILTER -> {
            // Normal event processing based on strategy
            when (listener.strategy) {
                ListenStrategy.ONE -> handleOneMatch(listener, event, conn)
                ListenStrategy.ANY -> handleAnyMatch(listener, event, match, conn)
                ListenStrategy.ALL -> handleAllMatch(listener, match.entry.filterIndex, match, event, conn)
            }
        }
    }
}
```

### 3. Empty Output for Accumulation Mode

If the until event arrives **before** any main events are accumulated, output is an empty array:

```yaml
listen:
  to:
    any:
      - with: { type: temperature }
    until:
      one:
        with: { type: shift.ended }
```

```
Timeline:
t=0   Listen starts (receivedEvents = null)
t=1   shift.ended event arrives
      → Matches until filter
      → Complete with empty array: []

Output: []  # Valid - no temperature events were received before shift ended
```

This is correct behavior per the spec. The workflow should handle empty output appropriately.

### 4. Duplicate Events

Same event ID arriving twice:

- Track received event IDs in `processed_event_ids` column
- Skip if already processed (checked in lock-check-update phase)

```kotlin
// In processListener()
if (locked.hasProcessedEvent(event.id)) {
    logger.debug { "Event ${event.id} already processed by listener ${listener.id}" }
    return@withTransaction
}
```

### 5. Worker Restart

Listeners survive restarts because:

- State persisted in database
- Optimistic locking with check-before-update prevents duplicate processing
- Event ID tracking ensures idempotency across restarts

### 6. High Listener Count

Performance optimizations:

- GIN indexes on source/type arrays
- Partial indexes excluding completed listeners
- Consider bloom filters for in-memory pre-filtering

## File Changes Summary

**New files (lemline-core):**

- `processors/ListenProcessor.kt`
- `states/ListenState.kt`
- Updates to `ActivityConfigs.kt`

**New files (lemline-runner):**

- `models/DefinitionListenModel.kt`
- `models/ListenerModel.kt`
- `repositories/DefinitionListenRepository.kt`
- `repositories/ListenerRepository.kt`
- `cache/DefinitionListenCache.kt`
- `messaging/cloudevents/CloudEventSubscriber.kt`
- `messaging/cloudevents/CloudEventHandler.kt`
- `outbox/ListenerTimeoutOutbox.kt`
- `cleaner/ListenerCleaner.kt`
- `db/migration/*/V008__Create_definition_listens_table.sql`
- `db/migration/*/V009__Create_listeners_table.sql`

**Modified files:**

- `WorkflowState.kt` - add ListenStarted
- `Node.kt` - register ListenProcessor
- `WorkflowEventHandler.kt` - add handleListenStarted
- `DefinitionRepository.kt` - manage definition listens lifecycle (insert/update/delete)
- `LemlineConfiguration.kt` - add config options

## Success Criteria

1. All existing tests pass
2. Listen task works with:
    - Strategy: one, any, all
    - Event filters: source, type, subject, data
    - Correlation matching
    - Until condition
    - Timeout handling
3. Performance: Phase 1 query uses indexes efficiently
4. Durability: Listeners survive worker restarts
5. Documentation updated

## Future Enhancements

1. **Foreach processing**: Process each event with a sub-workflow
2. **Wildcard patterns**: Support `*` and `?` in source/type patterns
3. **Event replay**: Ability to replay missed events
4. **Metrics**: Listener count, match rate, average wait time
