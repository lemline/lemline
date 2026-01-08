# [ADR-0012] Listen Task CloudEvent Processing

## Status

Accepted

## Context

The `listen` task in Serverless Workflow DSL enables workflows to wait for CloudEvents before continuing execution. CloudEvent processing operates **independently** from workflow instance execution, using a separate messaging channel and processing pipeline.

When a CloudEvent arrives, the system must:

1. Match the event against **workflow definitions** to find relevant listen task configurations
2. Route the event to **active listener instances** waiting for it
3. Handle multiple consumption strategies (ONE, ANY, ALL, ANY+until)
4. Optionally process events through `foreach.do` branches
5. Ensure exactly-once semantics despite concurrent processing and retries
6. Scale to potentially millions of active listeners

This ADR describes the architecture for:
- CloudEvent ingestion on a **separate channel** (`cloudevents-in`)
- Event matching against cached workflow definitions
- Event storage and listener completion detection
- Optional sequential `foreach.do` processing
- Workflow resumption with accumulated event results

### Channel Separation

CloudEvent processing is **completely separate** from workflow command/event messages:

```
┌──────────────────────────────────────────────────────┐
│ COMMANDS CHANNEL (lemline-commands)                  │
│ Purpose: Workflow instance execution                 │
│ Messages:                                            │
│   • ResumeFromTask (foreach.do execution)            │
│   • ResumeWithCompletedTask (listen completion)      │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ EVENTS CHANNEL (lemline-events)                      │
│ Purpose: Workflow lifecycle events                   │
│ Messages:                                            │
│   • ListenStarted (create listener record)           │
│   • ListenForEachCompleted (event processed)         │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ CLOUDEVENTS CHANNEL (cloudevents-in) ⚡ SEPARATE!    │
│ Purpose: External event ingestion                    │
│ Messages:                                            │
│   • CloudEvent (any external event)                  │
│ Processing: Independent from workflow execution      │
└──────────────────────────────────────────────────────┘
```

This separation enables:
- **Independent scaling** of event ingestion vs workflow execution
- **Non-blocking ingestion** - CloudEvents don't wait for workflow processing
- **Parallel processing** - Multiple CloudEvents and workflows can process concurrently

### Feature Overview

The listen task supports four consumption strategies:

| Strategy | Description | Completion Condition |
|----------|-------------|---------------------|
| **ONE** | Wait for a single event matching one filter | First matching event |
| **ANY** | Wait for first event matching any of N filters | First matching event from any filter |
| **ANY + until(expression)** | Accumulate events until expression is true | Expression evaluates to true on accumulated array |
| **ANY + until(event)** | Accumulate events until termination event | Termination event type arrives |
| **ALL** | Wait for one event per filter | One event received for each of N filters |

Each strategy has different requirements for event storage and completion detection.

## Decision

We implement a **modular, event-driven architecture** in the `lemline-runner-listeners` feature module that:
- Processes CloudEvents on a separate channel
- Matches events against cached workflow definitions
- Stores events in database tables with outbox pattern
- Optionally processes events through `foreach.do` branches (FIFO sequential)
- Resumes workflows with accumulated results

### Module Architecture

```
lemline-runner-listeners/
├── CloudEventService         - CloudEvent serialization/transformation
├── ListenerService          - Listener lifecycle (start, foreach complete)
├── ListenerEventService     - CloudEvent matching and storage
├── DefinitionListenService  - Extract listen configs from definitions
├── ListenerRepository       - lemline_listeners table operations
├── ListenerEventRepository  - lemline_listener_events table operations
├── outbox/
│   ├── ListenerCompletionOutbox  - Resume workflows when complete
│   └── ListenerForeachOutbox     - Process foreach.do sequentially
└── cleaner/
    └── ListenerCleaner      - Cleanup completed listeners
```

**Integration points:**
- `CloudEventHandler` (in lemline-runner) delegates to `ListenerEventService`
- `WorkflowEventHandler` (in lemline-runner) delegates to `ListenerService`

### Complete Workflow Lifecycle

#### Phase 1: Listen Task Initialization

```
┌─────────────────────────────────────────────────────┐
│ WORKFLOW EXECUTION (lemline-core)                   │
└─────────────────────────────────────────────────────┘
                    ↓
         Workflow hits listen task
                    ↓
      Processor throws ListenStarted event
                    ↓
┌─────────────────────────────────────────────────────┐
│ EVENTS CHANNEL → WorkflowEventHandler               │
└─────────────────────────────────────────────────────┘
                    ↓
      ListenerService.handleListenStarted()
                    ↓
         INSERT into lemline_listeners
                    ↓
      Stores: workflow identity, position,
              correlation, strategy, hasForeach,
              until config, instance state
```

The listener record contains all information needed to:
- Match incoming CloudEvents (workflow def + position + filters)
- Route events to specific instances (correlation values)
- Detect completion (strategy + filters_count)
- Resume workflow (serialized InstanceMessage)

#### Phase 2: CloudEvent Processing (⚡ SEPARATE PROCESS)

```
┌─────────────────────────────────────────────────────┐
│ CLOUDEVENTS CHANNEL → CloudEventHandler             │
└─────────────────────────────────────────────────────┘
                    ↓
   ListenerEventService.handleCloudEvent()
                    ↓
   ┌────────────────────────────────────────┐
   │ Step 1: Definition Matching (in-memory)│
   │ DefinitionListenService checks:        │
   │ • Which workflow defs listen for event │
   │ • Which workflow defs have until event │
   │ Returns: matching positions + filters  │
   └────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────┐
   │ Step 2: Instance Matching (database)   │
   │ Query lemline_listeners for:           │
   │ • completed_at IS NULL (still active)  │
   │ • Matching workflow def + position     │
   │ • Matching correlation values (if any) │
   └────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────┐
   │ Step 3: Dual Matching                  │
   │ Same CloudEvent can match:             │
   │ • Regular filters → accumulate event   │
   │ • Until filters → terminate listener   │
   └────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────┐
   │ Step 4: Event Storage                  │
   │ For each matching listener:            │
   │ INSERT into lemline_listener_events    │
   │ • listener_id (FK)                     │
   │ • event_id (CloudEvent ID)             │
   │ • filter_name (which filter matched)   │
   │ • event (serialized CloudEvent JSON)   │
   └────────────────────────────────────────┘
                    ↓
   ┌────────────────────────────────────────┐
   │ Step 5: Completion Check               │
   │ UPDATE lemline_listeners               │
   │ SET completed_at = NOW()               │
   │ IF strategy criteria met:              │
   │ • ONE/ANY: 1 event exists              │
   │ • ALL: all filters matched             │
   │ • ANY_UNTIL_EVENT: termination arrived │
   └────────────────────────────────────────┘
```

**Key points:**
- CloudEvent matching happens against **workflow definitions** (not instances)
- A single CloudEvent can match multiple listener instances
- A single CloudEvent can match both regular AND termination filters
- Event storage and completion check are atomic operations

#### Phase 3: Foreach Processing (OPTIONAL - if hasForeach=true)

```
┌─────────────────────────────────────────────────────┐
│ ListenerForeachOutbox (scheduled poll)              │
└─────────────────────────────────────────────────────┘
                    ↓
   markReadyForForeach() - per listener:
     SELECT oldest event (by event_index)
     WHERE outbox_completed_at IS NULL
       AND no event currently processing
     SET outbox_delayed_until = NOW()
                    ↓
              FIFO constraint:
        Only ONE event at a time per listener
                    ↓
   Process ready event:
     • Apply readAs transformation (DATA/ENVELOPE/RAW)
     • Create ResumeFromTask command
     • Position = listenPosition + "FOR" token  
     • Input = transformed event data
     • Send to COMMANDS CHANNEL
                    ↓
┌─────────────────────────────────────────────────────┐
│ WORKFLOW EXECUTION (foreach.do branch)              │
└─────────────────────────────────────────────────────┘
                    ↓
      Execute foreach.do tasks with event
                    ↓
      Returns ListenForEachCompleted event
                    ↓
┌─────────────────────────────────────────────────────┐
│ EVENTS CHANNEL → WorkflowEventHandler               │
└─────────────────────────────────────────────────────┘
                    ↓
   ListenerService.handleListenForEachCompleted()
                    ↓
   UPDATE lemline_listener_events
   SET outbox_completed_at = NOW(),
       output = <foreach result>
                    ↓
   Next event in FIFO becomes ready (automatic)
```

**Critical constraint:** Only ONE event processes at a time per listener (FIFO sequential). This ensures:
- Event processing order matches insertion order
- No race conditions between foreach iterations
- Deterministic workflow behavior

#### Phase 4: Listener Completion

```
┌─────────────────────────────────────────────────────┐
│ ListenerCompletionOutbox (scheduled poll)           │
└─────────────────────────────────────────────────────┘
                    ↓
   Pre-processing: batchMarkReady()
     • ONE/ANY: Check 1 event exists
     • ALL: Check COUNT(DISTINCT filter_name) >= filters_count
     • ANY_UNTIL_EXPR: Evaluate expression on completed events
     • ANY_UNTIL_EVENT: Already marked by CloudEventHandler
                    ↓
   Find ready listeners:
     WHERE completed_at IS NOT NULL
       AND (hasForeach=false 
            OR all events have outbox_completed_at)
       AND outbox_delayed_until IS NULL
                    ↓
   SET outbox_delayed_until = NOW()
                    ↓
   Process listener:
     • Aggregate foreach outputs from events
     • Apply readAs transformation
     • Create ResumeWithCompletedTask command
     • Output = array of event data/outputs
     • Send to COMMANDS CHANNEL
                    ↓
┌─────────────────────────────────────────────────────┐
│ WORKFLOW EXECUTION (resume after listen)            │
└─────────────────────────────────────────────────────┘
                    ↓
   Mark: outbox_completed_at, cleanup_after
```

### Two Independent Outbox Processors

The listen feature uses **two separate outbox processors** with distinct responsibilities:

| Aspect | ListenerForeachOutbox | ListenerCompletionOutbox |
|--------|----------------------|--------------------------|
| **Table** | `lemline_listener_events` | `lemline_listeners` |
| **Purpose** | Process events one-by-one through foreach.do | Resume workflow with all results |
| **Triggers** | Event inserted AND listener.hasForeach=true | listener.completed_at set AND all foreach done |
| **Constraint** | FIFO: One event at a time per listener | All events must be processed first |
| **Sends** | `ResumeFromTask` (execute foreach branch) | `ResumeWithCompletedTask` (resume workflow) |
| **Channel** | Commands (workflow execution) | Commands (workflow execution) |
| **Idempotency** | Message ID = `listenerId-foreach-eventId-resume` | Message ID = `listenerId-listen-complete` |

**Critical Flow:**
```
CloudEvent arrives → Stored in listener_events
                ↓
IF hasForeach=true:
    ListenerForeachOutbox → Process FIFO → Mark completed
                ↓
WHEN all events processed (or no foreach):
    ListenerCompletionOutbox → Resume workflow
```

### Database Schema

#### lemline_listeners - Active listener state

```sql
CREATE TABLE lemline_listeners (
    id UUID PRIMARY KEY,
    
    -- Workflow Definition Reference (for CloudEvent matching)
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name      VARCHAR(255) NOT NULL,
    workflow_version   VARCHAR(255) NOT NULL,
    workflow_position  TEXT         NOT NULL,
    
    -- Instance Reference (for workflow resumption)
    instance_message   TEXT         NOT NULL,  -- Serialized InstanceMessage<ListenStarted>
    workflow_id        UUID         NOT NULL,
    
    -- Configuration (extracted from cached workflow definition)
    strategy           VARCHAR(20)  NOT NULL,  -- ONE/ANY/ANY_UNTIL_EXPR/ANY_UNTIL_EVENT/ALL
    filters_count      INT,                     -- Number of filters (for ALL strategy)
    has_foreach        BOOLEAN      NOT NULL DEFAULT FALSE,
    has_until          BOOLEAN      NOT NULL DEFAULT FALSE,
    until_expression   TEXT,                    -- JQ expression (if until is expression-based)
    read_as            VARCHAR(10)  NOT NULL DEFAULT 'DATA',  -- DATA/ENVELOPE/RAW
    
    -- Correlation (for instance-specific event routing)
    correlation_values TEXT,                    -- JSON: {"orderId":"123"}
    
    -- Completion Tracking
    timeout_at         TIMESTAMP,
    completed_at       TIMESTAMP,               -- Listener stopped collecting events
    
    -- Outbox Pattern (for ListenerCompletionOutbox)
    outbox_scheduled_for   TIMESTAMP NOT NULL,
    outbox_delayed_until   TIMESTAMP,           -- NULL=not ready, NOT NULL=ready
    outbox_completed_at    TIMESTAMP,
    outbox_attempt_count   INT NOT NULL DEFAULT 0,
    outbox_error_class     VARCHAR(255),
    outbox_error_message   VARCHAR(500),
    outbox_error_stacktrace TEXT,
    
    -- Cleanup
    cleanup_after      TIMESTAMP,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index for CloudEvent matching (active listeners only)
CREATE INDEX idx_listeners_workflow_position
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, workflow_position)
    WHERE completed_at IS NULL;

-- Index for correlation-based matching
CREATE INDEX idx_listeners_correlation
    ON lemline_listeners (workflow_namespace, workflow_name, workflow_version, 
                          workflow_position, correlation_values)
    WHERE completed_at IS NULL;

-- Index for outbox processing
CREATE INDEX idx_listeners_ready
    ON lemline_listeners (outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND completed_at IS NOT NULL;
```

#### lemline_listener_events - Accumulated events

```sql
CREATE TABLE lemline_listener_events (
    -- Event Identity
    listener_id   UUID         NOT NULL,  -- FK to lemline_listeners
    event_id      VARCHAR(255) NOT NULL,  -- CloudEvent ID (for idempotency)
    filter_name   VARCHAR(255) NOT NULL,  -- Which filter matched
    
    -- Event Data
    event         TEXT         NOT NULL,  -- Serialized CloudEvent JSON
    output        TEXT,                    -- Foreach.do output (set by ListenForEachCompleted)
    
    -- Ordering (for FIFO foreach processing)
    event_index      INT          NOT NULL,  -- Insertion order per listener
    
    -- Outbox Pattern (for ListenerForeachOutbox)
    outbox_scheduled_for   TIMESTAMP NOT NULL,
    outbox_delayed_until   TIMESTAMP,       -- NULL=pending, NOT NULL=ready
    outbox_completed_at    TIMESTAMP,       -- Foreach.do completed
    outbox_attempt_count   INT NOT NULL DEFAULT 0,
    outbox_error_class     VARCHAR(255),
    outbox_error_message   VARCHAR(500),
    outbox_error_stacktrace TEXT,
    
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    
    PRIMARY KEY (listener_id, event_id),
    
    -- Constraints
    UNIQUE (listener_id, filter_name),      -- One event per filter (ALL strategy)
    FOREIGN KEY (listener_id) REFERENCES lemline_listeners(id) ON DELETE CASCADE
);

-- Index for foreach outbox processing (FIFO head per listener)
CREATE INDEX idx_listener_events_foreach
    ON lemline_listener_events (listener_id, event_index)
    WHERE outbox_completed_at IS NULL;
```

### Strategy-Specific Processing

#### ONE Strategy

**Behavior:**
- Completes on first matching event
- Stores single event in `listener_events`
- Marks `completed_at` immediately

**Database Operations:**
```
1. INSERT into listener_events (event)
2. UPDATE listeners SET completed_at = NOW()
   WHERE id = ? AND completed_at IS NULL
```

**Output:** Array with one event `[event1]`

#### ANY Strategy

**Behavior:**
- Completes on first event from any filter
- Multiple filters, first to match wins
- Stores single event in `listener_events`

**Database Operations:**
```
1. INSERT into listener_events (event, filter_name)
2. UPDATE listeners SET completed_at = NOW()
   WHERE id = ? AND completed_at IS NULL
```

**Output:** Array with one event `[eventFromFilter2]`

#### ALL Strategy

**Behavior:**
- Requires one event per filter
- UNIQUE constraint on `(listener_id, filter_name)` ensures one per filter
- Completes when all filters matched

**Database Operations:**
```
1. INSERT into listener_events (event, filter_name)
   ON CONFLICT (listener_id, filter_name) DO NOTHING
2. Check completion:
   SELECT COUNT(DISTINCT filter_name) FROM listener_events
   WHERE listener_id = ?
3. IF count >= filters_count:
   UPDATE listeners SET completed_at = NOW()
```

**Output:** Array with N events `[filter0Event, filter1Event, filter2Event]`

#### ANY + until(expression) Strategy

**Behavior:**
- Accumulates all matching events
- Evaluates JQ expression against completed events array
- Completes when expression returns true

**Database Operations:**
```
1. INSERT into listener_events (event, event_id)
   ON CONFLICT (listener_id, event_id) DO NOTHING
2. On outbox poll:
   SELECT events FROM listener_events
   WHERE listener_id = ? AND outbox_completed_at IS NOT NULL
   (Only completed foreach events for accuracy)
3. Evaluate: JQExpression.eval(eventsArray, untilExpression)
4. IF result == true:
   UPDATE listeners SET completed_at = NOW()
```

**Output:** Array with 0 to N events `[event1, event2, ..., eventN]`

**Note:** Expression only evaluated against completed events (foreach processing done).

#### ANY + until(event) Strategy

**Behavior:**
- Accumulates all matching events
- Completes when termination event arrives
- Termination event NOT included in output

**Database Operations:**
```
1. Accumulation:
   INSERT into listener_events (event, event_id)
   ON CONFLICT (listener_id, event_id) DO NOTHING

2. Termination (CloudEventHandler):
   UPDATE listeners SET completed_at = NOW()
   WHERE id = ? AND completed_at IS NULL
   (Termination event triggers completion immediately)
```

**Output:** Array with 0 to N events (excludes termination) `[event1, event2, event3]`

### Correlation: Instance-Specific Event Routing

**Problem:**
Without correlation, ALL workflow instances listening for `order.shipped` receive EVERY `order.shipped` event (broadcast semantics).

**Solution:**
The `correlate` property creates instance-specific filtering based on workflow context:

```yaml
listen:
  to:
    one:
      with:
        type: order.shipped
      correlate:
        orderId:
          from: '${ .orderId }'         # Extract from CloudEvent data
          expect: '${ $input.orderId }' # Match against workflow input
```

#### How Correlation Works

**1. Listener Creation** (at listen task start):

```
ListenerService.handleListenStarted()
↓
calculateCorrelationValues(config)
↓
Evaluates: expect expressions against workflow context
Example: $input.orderId = "order-123"
         $context.customerId = "C1"
↓
Stores: {"customerId":"C1","orderId":"order-123"} 
        in listener.correlation_values
        (sorted keys for consistent matching)
```

**2. CloudEvent Matching** (when event arrives):

```
ListenerEventService.handleCloudEvent(event)
↓
For each filter with correlate:
  Extract: from expression against CloudEvent data
  Example: event.data.orderId = "order-456"
           event.data.customerId = "C2"
↓
Build: {"customerId":"C2","orderId":"order-456"}
       (sorted keys to match storage format)
↓
Query: SELECT * FROM lemline_listeners
       WHERE correlation_values = '{"customerId":"C2","orderId":"order-456"}'
         AND completed_at IS NULL
↓
Result: Only listeners with matching correlation receive event
```

#### Expression Context - Critical Distinction

The key difference between filters and correlation:

| Expression Location | Evaluated Against | Available Variables |
|---------------------|-------------------|---------------------|
| `with.data` filter | CloudEvent data | `.` (event data only) |
| `correlate.from` | CloudEvent data | `.` (event data only) |
| `correlate.expect` | **Workflow context** | `$input`, `$context`, `$workflow`, `$task` |

**Only `correlate.expect`** can access workflow-specific data to filter events.

#### Complete Correlation Example

```yaml
do:
  - waitForShipment:
      listen:
        to:
          one:
            with:
              type: order.shipped
              data: ${ .orderId != null }  # Filter: event must have orderId
            correlate:
              orderId:
                from: '${ .orderId }'           # Extract from event
                expect: '${ $input.orderId }'   # Match workflow input
              customerId:
                from: '${ .customerId }'        # Extract from event
                expect: '${ $context.customerId }' # Match workflow context
```

**Execution:**

```
Instance A: input.orderId="123", context.customerId="C1"
  → correlation_values = {"customerId":"C1","orderId":"123"}

Instance B: input.orderId="456", context.customerId="C2"
  → correlation_values = {"customerId":"C2","orderId":"456"}

Event arrives: data = {orderId:"456", customerId:"C2"}
  → extracted correlation = {"customerId":"C2","orderId":"456"}
  → Matches only Instance B ✓
  → Instance A ignored ✓
```

#### Auto-Correlation (No expect)

When `expect` is omitted, the first event's value becomes the correlation:

```yaml
correlate:
  sessionId:
    from: '${ .sessionId }'
    # No expect - first event's sessionId becomes the correlation value
```

**Use case:** Workflow doesn't know correlation value upfront but needs subsequent events to match the first.

**Behavior:**

```
First event arrives: sessionId = "sess-789"
  → UPDATE listener SET correlation_values = '{"sessionId":"sess-789"}'

Second event arrives: sessionId = "sess-789"
  → Matches (correlation now set)

Third event arrives: sessionId = "sess-999"
  → Does not match (different sessionId)
```

This is particularly useful for ALL strategy where correlation is established by the first filter match.

#### Database Schema for Correlation

```sql
-- Stored as sorted JSON string for exact matching
correlation_values TEXT

-- Example values:
'{"customerId":"C1","orderId":"123"}'  -- Sorted keys alphabetically

-- Query patterns:
WHERE correlation_values = ?           -- Exact match (most common)
  OR correlation_values IS NULL        -- No correlation = match all instances
```

**Why sorted keys?** Ensures consistent string comparison regardless of expression evaluation order.

### Sequence Diagrams

#### Diagram 1: Simple ONE Strategy (no foreach)

```
CloudEvent  CloudEventHandler  ListenerEventService  DB  ListenerCompletionOutbox  Commands
    |              |                    |              |            |                  |
    |---event----->|                    |              |            |                  |
    |              |---handleEvent----->|              |            |                  |
    |              |                    |--INSERT event|            |                  |
    |              |                    |--UPDATE completed_at      |                  |
    |              |<---ok--------------|              |            |                  |
    |<---ack-------|                    |              |            |                  |
    |              |                    |              |            |                  |
    [time passes - outbox polls]                      |            |                  |
    |              |                    |              |<--poll-----|                  |
    |              |                    |              |--listeners>|                  |
    |              |                    |              |            |--ResumeWithCompletedTask->|
    |              |                    |              |            |                  |
    |              |                    |              |            |<--ack------------|
    |              |                    |              |<-mark done-|                  |
```

#### Diagram 2: ANY + until(event) with foreach

```
CloudEvent  CloudEventHandler  DB  ListenerForeachOutbox  Workflow  ListenerCompletionOutbox
    |              |            |            |               |              |
    |---event1---->|            |            |               |              |
    |              |--INSERT--->|            |               |              |
    |<---ack-------|            |            |               |              |
    |              |            |<--poll-----|               |              |
    |              |            |--event1--->|               |              |
    |              |            |            |--ResumeFromTask->             |
    |              |            |            |            (foreach.do)       |
    |              |            |            |<--ForEachCompleted--          |
    |              |            |<-mark done-|               |              |
    |              |            |            |               |              |
    |---event2---->|            |            |               |              |
    |              |--INSERT--->|            |               |              |
    |<---ack-------|            |            |               |              |
    |              |            |<--poll-----|               |              |
    |              |            |--event2--->|               |              |
    |              |            |            |--ResumeFromTask->             |
    |              |            |            |<--ForEachCompleted--          |
    |              |            |<-mark done-|               |              |
    |              |            |            |               |              |
    |-termination->|            |            |               |              |
    |              |-INSERT+--->|            |               |              |
    |              | completed_at            |               |              |
    |<---ack-------|            |            |               |              |
    |              |            |            |               |<--poll-------|
    |              |            |            |               | (all foreach done)
    |              |            |            |               |<-aggregate---|
    |              |            |            |               |--ResumeWithCompletedTask->
    |              |            |            |               |              |
```

**Key observations:**
- Events processed sequentially (FIFO) through foreach
- Each foreach completes before next starts
- Termination event marks listener completed
- ListenerCompletionOutbox waits for all foreach to finish

#### Diagram 3: ALL Strategy

```
CloudEvent  CloudEventHandler  DB  ListenerCompletionOutbox  Commands
    |              |            |            |                  |
    |--filter0---->|            |            |                  |
    |              |-INSERT---->|            |                  |
    |              | filter=0   |            |                  |
    |<---ack-------|            |            |                  |
    |              |            |            |                  |
    |--filter1---->|            |            |                  |
    |              |-INSERT---->|            |                  |
    |              | filter=1   |            |                  |
    |              |-CHECK count|            |                  |
    |              | (2 >= 2)   |            |                  |
    |              |-UPDATE---->|            |                  |
    |              | completed_at            |                  |
    |<---ack-------|            |            |                  |
    |              |            |<--poll-----|                  |
    |              |            |--listeners>|                  |
    |              |            |            |--ResumeWithCompletedTask->|
```

**Key observations:**
- Two different events required (one per filter)
- Completion check after each insert
- Listener completes when all filters matched

### ReadAs Transformation

The `readAs` property controls how CloudEvent data is extracted before delivery to the workflow:

| Value | Description | Output Content |
|-------|-------------|----------------|
| `data` (default) | Extract event payload only | `event.getData()` |
| `envelope` | Include full CloudEvent | `{ type, source, id, data, ... }` |
| `raw` | Raw event bytes | Base64-encoded data |

**Applied at:**
- `ListenerForeachOutbox`: Before sending to foreach.do
- `ListenerCompletionOutbox`: Before aggregating final output

**Example:**
```yaml
listen:
  to:
    one:
      with:
        type: order.created
  read: envelope  # Include full CloudEvent metadata
```

**Implementation:** Handled by `CloudEventService.parseStringAsData()`

### Idempotency Guarantees

Idempotency is achieved through three layers:

#### 1. Database Constraints

```sql
-- No duplicate CloudEvents per listener
UNIQUE (listener_id, event_id)

-- One event per filter (ALL strategy)
UNIQUE (listener_id, filter_name)

-- WHERE guards on updates
WHERE completed_at IS NULL  -- Not already completed
```

#### 2. Outbox Pattern

```kotlin
// Idempotent message IDs derived from entity IDs
val messageId = entity.id.derive("-listen-complete")
instanceEmitter.send(resumeMessage, messageId)
```

- Message broker deduplicates based on message ID
- Workflow processors handle duplicate commands gracefully

#### 3. FIFO Foreach Processing

```sql
-- Only one event processing at a time per listener
WHERE outbox_delayed_until IS NULL     -- Not marked ready yet
  AND outbox_completed_at IS NULL       -- Not completed
  AND NOT EXISTS (                       -- No other event processing
      SELECT 1 FROM listener_events e2
      WHERE e2.listener_id = listener_id
        AND e2.outbox_delayed_until IS NOT NULL
        AND e2.outbox_completed_at IS NULL
  )
```

**Result:** Duplicate event processing produces identical state.

## Consequences

### Positive

✅ **Independent Scaling**: CloudEvent ingestion scales separately from workflow execution  
✅ **Modular Design**: Feature encapsulated in lemline-runner-listeners module  
✅ **Non-Blocking Ingestion**: CloudEvents don't wait for workflow processing  
✅ **Ordered Foreach**: FIFO guarantees foreach execution order per listener  
✅ **Clean Separation**: Three distinct channels for different concerns  
✅ **Flexible Correlation**: Instance-specific event routing with workflow context

### Negative

❌ **Complex State Machine**: Two outbox processors with interdependencies  
❌ **Sequential Foreach**: One event at a time per listener (intentional trade-off)  
❌ **Eventual Consistency**: Completion not immediate (polling intervals)  
❌ **Memory for Expression Eval**: ANY_UNTIL_EXPR requires loading completed events  
❌ **Definition Matching Cost**: Every CloudEvent checked against all workflow definitions

### Neutral

⚖️ **Two Tables**: Separation of listener state vs event accumulation  
⚖️ **Multiple Queries**: Definition matching + instance matching per CloudEvent  
⚖️ **Dual Matching**: Same CloudEvent can match regular + termination filters  
⚖️ **Correlation Evaluation**: Expect expressions evaluated at listener creation

## Alternatives Considered

### 1. Single Outbox Processor

Process both foreach and completion in one outbox.

**Rejected because:**
- Mixes concerns (event processing vs workflow resumption)
- Harder to reason about FIFO constraint
- Polling optimization requires different strategies

### 2. Parallel Foreach Processing

Allow multiple events to process through foreach.do simultaneously.

**Rejected because:**
- Breaks event ordering guarantees
- Complicates completion detection
- Workflow behavior becomes non-deterministic

### 3. In-Memory Event Matching

Load all listeners into memory for event matching.

**Rejected because:**
- Memory explosion with millions of listeners
- Out-of-memory crashes under high load
- No benefit for database-based persistence

### 4. CloudEvents in Workflow Channel

Route CloudEvents through commands-in channel.

**Rejected because:**
- Couples event ingestion to workflow execution
- Workflow processing delays affect event ingestion
- Cannot scale independently

## References

**Module: lemline-runner-listeners**
- `ListenerService.kt` - Listener lifecycle operations
- `ListenerEventService.kt` - CloudEvent matching and storage
- `CloudEventService.kt` - CloudEvent serialization/transformation
- `DefinitionListenService.kt` - Extract listen configs from definitions
- `ListenerRepository.kt` - Listener database operations
- `ListenerEventRepository.kt` - Event database operations
- `outbox/ListenerCompletionOutbox.kt` - Resume workflows
- `outbox/ListenerForeachOutbox.kt` - Process foreach.do
- `cleaner/ListenerCleaner.kt` - Cleanup completed listeners

**Module: lemline-runner**
- `messaging/cloudevents/CloudEventHandler.kt` - CloudEvent message handler
- `messaging/cloudevents/CloudEventSubscriber.kt` - CloudEvent subscriber
- `messaging/events/WorkflowEventHandler.kt` - Workflow event dispatcher

**Module: lemline-core**
- `processors/ListenProcessor.kt` - Listen task processor
- `workflows/WorkflowCache.kt` - Cached workflow definitions

**Documentation:**
- [Listen Task Documentation](../listen-task.md)
- [Serverless Workflow Specification - Listen Task](https://serverlessworkflow.io)
- [ADR-0003 Messaging Architecture](./0003-messaging-architecture.md)
- [lemline-runner-listeners README](../../lemline-runner-listeners/README.md)
