# Feature: Listen Task Foreach Support

## Overview

Implement the `foreach` feature for the listen task in the runner module. The `foreach` property allows processing
each CloudEvent as it arrives, executing child tasks for each event in a synchronous loop.

Per the Serverless Workflow specification:
> "When `foreach` is set, the configured operations for events must complete before moving on to the next one.
> As a result, consumed events should be stored in a First-In-First-Out (FIFO) queue while awaiting iteration."

## Listen Task Output Behavior

### Key Specification Points

From the Serverless Workflow DSL specification:

> "A listen task produces a sequentially ordered array of all the events it has consumed,
> and potentially transformed using `foreach.output.as`."

> "Events consumed by an `until` clause should not be included in the task's output.
> These events are used solely to determine when the until condition has been met."

### Output Without Foreach

The listen task outputs an **array of all consumed events**:

```
Input to listen task: (workflow input)
Output: [event1, event2, event3, ...]
```

- Events are in arrival order (FIFO)
- Events matching `until` termination conditions are **excluded**
- The output format depends on `listen.read` setting (data, envelope, or raw)

### Output With Foreach

When `foreach` is present, the listen task outputs the **array of foreach.do results** (optionally transformed):

```
Output: [foreach_result1, foreach_result2, foreach_result3, ...]
       or with output.as:
       [transform(foreach_result1), transform(foreach_result2), transform(foreach_result3), ...]
```

**Important**: With foreach, the output is the **results of foreach.do tasks**, NOT the raw events.

### Foreach.do Input/Output Behavior

| Property                | Description                                                                    |
|-------------------------|--------------------------------------------------------------------------------|
| **Input to foreach.do** | The current event being processed                                              |
| **Current event**       | Also available via scope variable `$item` (or custom name from `foreach.item`) |
| **Current index**       | Available via scope variable `$index` (or custom name from `foreach.at`)       |
| **foreach.output.as**   | Optional transformation applied to each foreach.do result                      |
| **Listen task output**  | Array of foreach.do results (optionally transformed by `output.as`)            |

**Key points**:

- The **input** to foreach.do is the **event itself** (format defined by `listen.read` setting)
- The event is also accessible via scope variable `$item` (for convenience in expressions)
- The **output** of foreach.do contributes to the listen task's output array

#### Example: Processing Events with foreach.do

```yaml
do:
    -   listenAndProcess:
            listen:
                to:
                    any:
                        -   with:
                                type: com.example.Order
                    until: '${ (.length) >= 3 }'  # Stop after 3 orders
            foreach:
                item: order
                at: i
                do:
                    # Process each order - the result becomes part of the output
                    -   notifyWarehouse:
                            call: http
                            with:
                                method: post
                                endpoint: https://warehouse.example.com/orders
                                body:
                                    orderId: '${ .id }'  # Input is the event, so .id accesses event.id
                output:
                    as: '${ { id: .id, warehouseResponse: . } }'  # Transform foreach.do result
```

---

## Implementation Plan

### Architecture Overview

The foreach feature requires **sequential processing** of CloudEvents - each event's `foreach.do` tasks must complete
before processing the next event. This creates a fundamental architectural challenge: the current listen task
implementation completes the listener in one shot when all events are collected, but foreach requires iterative
processing with workflow resumption between each event.

#### Key Insight: Outbox-Based Event Processing

The solution uses the existing outbox pattern with the `lemline_listener_events` table:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                       Listen Task WITH foreach                                  │
│                                                                                 │
│  Strategy ONE/ANY (without until):                                              │
│    Currently: Complete listener → Resume workflow with single event             │
│    With foreach: Complete listener → Resume foreach.do with event               │
│                  → On completion → Resume workflow (no more events)             │
│                                                                                 │
│  Strategy ALL / ANY+until:                                                      │
│    Currently: Accumulate events in lemline_listener_events                      │
│               → On completion criteria → Resume workflow with ALL events        │
│    With foreach: Use lemline_listener_events as FIFO queue                      │
│               → Process ONE event at a time via outbox                          │
│               → On foreach.do completion → Check for next event or terminate    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Strategy-Specific Approach

The implementation varies by listen strategy to minimize changes and complexity:

#### ONE/ANY (without until): Minimal Changes

For strategies that complete on a single event, the existing flow is **mostly preserved**:

- `CloudEventHandler` behavior: **UNCHANGED**
- `lemline_listeners` outbox: **REUSED** (just routes differently based on foreach)
- `lemline_listener_events`: **NOT USED** for these strategies

The only change is in `ListenerCompletionOutbox`:

- Without foreach: Send `ResumeWithCompletedTask` (current behavior)
- With foreach: Send `ResumeFromTask` to execute `foreach.do`, then complete on `ListenForEachCompleted`

#### ALL / ANY+until: Dual-Outbox Design

For strategies that accumulate multiple events, we need a second outbox to process events
one-at-a-time through `foreach.do`:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    DUAL OUTBOX ARCHITECTURE (ALL / ANY+until only)              │
│                                                                                 │
│  lemline_listeners (existing outbox)                                            │
│  └─► ListenerCompletionOutbox                                                   │
│      ├─ Without foreach: Resume workflow with all accumulated events            │
│      └─ With foreach: Resume workflow with aggregated foreach.do outputs        │
│                                                                                 │
│  lemline_listener_events (NEW outbox - only for foreach)                        │
│  └─► ListenerEventOutbox (NEW)                                                  │
│      └─ Process ONE event at a time through foreach.do                          │
│         FIFO order via created_at                                               │
│         On foreach.do completion → Store output → Check next or complete        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

This separation means:

- **ONE/ANY**: Simple, low overhead - uses existing outbox
- **ALL/ANY+until**: New outbox only when foreach is enabled AND multiple events expected

### New Event Types for Foreach Processing

Add new workflow events to handle foreach iteration lifecycle:

```kotlin
// In WorkflowState.kt

/**
 * Event emitted when a foreach iteration completes successfully.
 * Triggers processing of the next queued event or listener completion.
 */
@Serializable
@SerialName("listenForEachCompleted")
data class ListenForEachCompleted(
    override val nodeStack: NodeStack,
    val iterationOutput: JsonElement,  // Output from foreach.do tasks
    val iterationIndex: Int            // Which iteration just completed (0-based)
) : WorkflowEvent()

/**
 * Event emitted when a foreach iteration fails.
 * Based on listen task error handling, may retry, skip, or fail the listener.
 */
@Serializable
@SerialName("listenForEachFailed")
data class ListenForEachFailed(
    override val nodeStack: NodeStack,
    val error: InternalException.Error,
    val iterationIndex: Int
) : WorkflowEvent()
```

### Database Schema Changes

#### Modify `lemline_listener_events` Table

Add outbox columns to enable event-by-event processing:

```sql
ALTER TABLE lemline_listener_events ADD COLUMN (
    -- Outbox fields for foreach processing
    outbox_scheduled_for    TIMESTAMPTZ(6),  -- NULL = not a foreach listener
    outbox_delayed_until    TIMESTAMPTZ(6),  -- NULL = waiting, NOT NULL = ready to process
    outbox_attempt_count    INT DEFAULT 0,
    outbox_error_class      TEXT,
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMPTZ(6),  -- Set when foreach.do completes for this event
    outbox_failed_at        TIMESTAMPTZ(6),

    -- Foreach iteration tracking
    iteration_index         INT,             -- 0-based index of this event in processing order
    iteration_output        TEXT             -- Output from foreach.do for this event (JSON)
);

-- Index for foreach outbox processing
CREATE INDEX idx_listener_events_outbox
    ON lemline_listener_events (outbox_delayed_until) WHERE outbox_scheduled_for IS NOT NULL
      AND outbox_completed_at IS NULL
      AND outbox_failed_at IS NULL;
```

#### Modify `lemline_listeners` Table

Add foreach state tracking:

```sql
ALTER TABLE lemline_listeners ADD COLUMN (
    -- Foreach configuration (extracted from workflow definition for efficiency)
    has_foreach             BOOLEAN DEFAULT FALSE,

    -- Foreach processing state
    foreach_current_index   INT DEFAULT 0,        -- Index of event currently being processed
    foreach_processing      BOOLEAN DEFAULT FALSE, -- TRUE when foreach.do is running

    -- Completion flag (set by CloudEventHandler when criteria met)
    -- This decouples completion detection (CloudEventHandler) from completion handling (WorkflowEventHandler)
    listener_completed      BOOLEAN DEFAULT FALSE
);
```

The `listener_completed` flag is set by `CloudEventHandler` when:

- **ALL**: `COUNT(matched_events) >= filters_count`
- **ANY+until(expr)**: Expression evaluates to true on accumulated events
- **ANY+until(event)**: Termination event is received

This allows `WorkflowEventHandler.handleListenForEachCompleted()` to simply check the flag
rather than re-implementing completion logic (which would be impossible for ANY+until(event)
since the termination event context is not available).

### Processing Flow: Foreach with ALL / ANY+until

This is the primary use case since events accumulate in `lemline_listener_events`.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    FOREACH PROCESSING FLOW (ALL / ANY+until)                     │
└─────────────────────────────────────────────────────────────────────────────────┘

STEP 1: CloudEvent Arrives
═══════════════════════════════════════════════════════════════════════════════════
    CloudEventHandler.handleCloudEvent(event)
        │
        ├─► Check if listener has foreach enabled
        │
        └─► ATOMIC OPERATION (single transaction):
            │
            ├─► INSERT event into lemline_listener_events
            │   WITH outbox_scheduled_for = null  ← Queued, not ready yet
            │
            └─► IF listener.foreach_processing = FALSE:
                │   SET listener.foreach_processing = TRUE  ← Set BEFORE outbox picks up
                │   SET event.outbox_scheduled_for = NOW() 
                │   SET event.outbox_delayed_until = NOW()  ← Trigger outbox for THIS event

    The atomic check-and-set prevents race conditions:
    - Only ONE event gets outbox_delayed_until = NOW() at a time
    - The flag is set in the SAME transaction as the insert

STEP 2: ListenerEventOutbox Processes Event
═══════════════════════════════════════════════════════════════════════════════════
    ListenerEventOutbox.process(event)
        │
        ├─► (foreach_processing already TRUE from Step 1)
        │
        ├─► Create ResumeFromTask command for foreach.do
        │   Input: the event data
        │   Scope: $item = event, $index = iteration_index
        │
        └─► Send command to workflow channel

STEP 3: Workflow Executes foreach.do Tasks
═══════════════════════════════════════════════════════════════════════════════════
    WorkflowCommandHandler processes foreach.do tasks
        │
        └─► On completion: Emit ListenForEachCompleted event
            On failure: Emit ListenForEachFailed event

STEP 4: WorkflowEventHandler Handles Foreach Completion
═══════════════════════════════════════════════════════════════════════════════════
    WorkflowEventHandler.handleListenForEachCompleted(event)
        │
        ├─► Update lemline_listener_events:
        │   SET outbox_completed_at = NOW()
        │   SET iteration_output = event.iterationOutput
        │
        ├─► Update listener:
        │   SET foreach_current_index = foreach_current_index + 1
        │
        ├─► Check for next pending event:
        │   SELECT FROM lemline_listener_events
        │   WHERE listener_id = ?
        │     AND outbox_completed_at IS NULL
        │   ORDER BY created_at ASC
        │   LIMIT 1
        │
        ├─► IF next event exists:
        │   │   SET next_event.outbox_scheduled_for = NOW()
        │   │   SET next_event.outbox_delayed_until = NOW()
        │   │   (triggers ListenerEventOutbox for next event)
        │   │
        │   └─► Done (wait for next iteration)
        │
        └─► ELSE (no more pending events):
                │
                ├─► Check listener.listener_completed flag
                │   (This flag was set by CloudEventHandler when completion criteria were met)
                │
                ├─► IF listener_completed = TRUE:
                │   │   Aggregate all iteration_outputs into array
                │   │   SET listener.event = JSON array
                │   │   SET listener.outbox_delayed_until = NOW()
                │   │   SET listener.foreach_processing = FALSE
                │   │   (triggers ListenerCompletionOutbox)
                │   │
                │   └─► Done (listener will complete)
                │
                └─► ELSE (waiting for more events):
                        SET listener.foreach_processing = FALSE
                        (wait for next CloudEvent)

NOTE: The `listener_completed` flag is set by CloudEventHandler when:
  - ALL: COUNT of matched filters >= filters_count
  - ANY+until(expr): Expression evaluates to true
  - ANY+until(event): Termination event received

This avoids re-implementing completion logic in WorkflowEventHandler,
and handles the ANY+until(event) case where we don't have termination event context.
```

### Processing Flow: Foreach with ONE/ANY (without until)

For ONE/ANY strategies, the current behavior is **mostly unchanged**. The only difference
is what happens after the listener is marked ready for completion:

- **Without foreach**: `ListenerCompletionOutbox` sends `ResumeWithCompletedTask` to complete the listen task
- **With foreach**: `ListenerCompletionOutbox` sends `ResumeFromTask` to execute `foreach.do`, then completes

This approach:

- Keeps the existing `CloudEventHandler` logic unchanged for ONE/ANY
- Reuses the existing `lemline_listeners` outbox (no need for `lemline_listener_events` outbox)
- Only modifies `ListenerCompletionOutbox` to check for foreach

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    FOREACH PROCESSING FLOW (ONE/ANY)                             │
│                                                                                  │
│  Uses EXISTING flow with minimal changes                                         │
└─────────────────────────────────────────────────────────────────────────────────┘

STEP 1: CloudEvent Arrives (UNCHANGED)
═══════════════════════════════════════════════════════════════════════════════════
    CloudEventHandler.handleCloudEvent(event)
        │
        └─► Same as current: UPDATE listener with event, set outbox_delayed_until = NOW()

STEP 2: ListenerCompletionOutbox Processes (MODIFIED)
═══════════════════════════════════════════════════════════════════════════════════
    ListenerCompletionOutbox.process(entity)
        │
        ├─► Check if listener has foreach enabled (from workflow definition cache)
        │
        ├─► IF NO foreach (current behavior):
        │   │   Create ResumeWithCompletedTask with event array
        │   └─► Send to workflow channel → Listen task completes
        │
        └─► IF foreach enabled (NEW behavior):
            │   Create ResumeFromTask targeting foreach.do position
            │   Input = single event
            │   Scope = $item = event, $index = listener.foreach_current_index
            └─► Send to workflow channel → foreach.do executes

STEP 3: Foreach.do Completes (NEW - only for foreach)
═══════════════════════════════════════════════════════════════════════════════════
    Workflow executes foreach.do tasks
        │
        └─► On completion: Emit ListenForEachCompleted event
            (Since ONE/ANY has only 1 event, this is the final iteration)

STEP 4: Listen Task Completes (NEW - only for foreach)
═══════════════════════════════════════════════════════════════════════════════════
    WorkflowEventHandler.handleListenForEachCompleted(event)
        │
        ├─► Since ONE/ANY strategy: no more events to process
        │
        └─► Create ResumeWithCompletedTask with [iteration_output]
            Send to workflow channel → Listen task completes
```

**Key Insight**: For ONE/ANY, we don't need the `lemline_listener_events` table or its outbox
at all. The single event is already stored in `lemline_listeners.event`. We just need to:

1. Route to `foreach.do` instead of completing directly
2. Handle the `ListenForEachCompleted` event to finalize

### Concurrency Control: Single Event at a Time

The specification requires events to be processed **sequentially**:

> "When `foreach` is set, the configured operations for events must complete before moving on to the next one."

#### Mechanism: Atomic Flag + Conditional Insert

The `foreach_processing` flag is set **atomically during event insertion**, not by the outbox.
This prevents race conditions at the source:

1. **CloudEventHandler** inserts event AND sets flag in same transaction
2. **Only events with `outbox_delayed_until IS NOT NULL`** are picked up by outbox
3. **New events while processing** get `outbox_delayed_until = NULL` (queued)

```sql
-- In ListenerEventOutbox query (simple - no flag check needed):
SELECT e.*
FROM lemline_listener_events e
WHERE e.outbox_delayed_until <= NOW()
  AND e.outbox_completed_at IS NULL
  AND e.outbox_failed_at IS NULL
ORDER BY e.created_at ASC
    FOR UPDATE SKIP LOCKED;
```

The outbox doesn't need to check `foreach_processing` because:

- The flag controls which events get `outbox_delayed_until` set during INSERT
- Events for a busy listener are inserted with `outbox_delayed_until = NULL`
- Only when `ListenForEachCompleted` triggers the next event does it get `outbox_delayed_until = NOW()`

This ensures:

1. Only ONE event per listener has `outbox_delayed_until` set at a time
2. Events are processed in FIFO order (`ORDER BY created_at`)
3. Multiple workers don't conflict (`FOR UPDATE SKIP LOCKED`)

### Event Storage During foreach.do Execution

While `foreach.do` is executing, new CloudEvents may arrive. The atomic insert handles this:

```sql
-- CloudEventHandler: Atomic INSERT + conditional flag update
-- This runs in a single transaction to prevent race conditions

WITH inserted_event AS (
INSERT
INTO lemline_listener_events (id, listener_id, event, created_at, outbox_scheduled_for)
SELECT gen_random_uuid(), l.id, $event_json, NOW(), NOW()
FROM lemline_listeners l
WHERE l.id = $listener_id
  AND l.has_foreach = TRUE RETURNING id, listener_id
),
trigger_if_idle AS (
-- Only set outbox_delayed_until if no event is currently processing
UPDATE lemline_listener_events e
SET outbox_delayed_until = NOW()
FROM inserted_event ie
    JOIN lemline_listeners l
ON l.id = ie.listener_id
WHERE e.id = ie.id
  AND l.foreach_processing = FALSE
    RETURNING e.listener_id
    )
-- Set the processing flag if we triggered an event
UPDATE lemline_listeners
SET foreach_processing = TRUE
WHERE id IN (SELECT listener_id FROM trigger_if_idle);
```

**Result**:

- If `foreach_processing = FALSE`: Event gets `outbox_delayed_until = NOW()`, flag set to `TRUE`
- If `foreach_processing = TRUE`: Event gets `outbox_delayed_until = NULL` (queued)

When current foreach.do completes, the handler checks for queued events and triggers the next one.

### ListenerEventOutbox Implementation

```kotlin
/**
 * Outbox processor for foreach event processing.
 *
 * Processes individual CloudEvents from the lemline_listener_events queue
 * for listeners with foreach enabled. Ensures sequential FIFO processing.
 *
 * ## How it works
 *
 * 1. Query for events with outbox_delayed_until <= NOW()
 *    (foreach_processing flag already set by CloudEventHandler during insert)
 * 2. Send ResumeFromTask command to execute foreach.do with the event
 * 3. On foreach.do completion (via ListenForEachCompleted event):
 *    - Mark event outbox_completed_at
 *    - Check for next event → trigger it and keep foreach_processing = TRUE
 *    - Or complete listener and set foreach_processing = FALSE
 *
 * ## FIFO Ordering
 *
 * Events are processed in order of arrival (created_at ASC).
 * Only one event per listener is processed at a time.
 *
 * ## Concurrency Safety
 *
 * The foreach_processing flag is set atomically by CloudEventHandler
 * when inserting the first event. This outbox does NOT set the flag -
 * it's already TRUE when we pick up the event.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerEventOutbox : AbstractOutbox<ListenerEventModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    override lateinit var outboxRepository: ListenerEventRepository

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    override suspend fun process(entity: ListenerEventModel) {
        // 1. Load listener to get workflow state (foreach_processing already TRUE)
        val listener = listenerRepository.findById(entity.listenerId)
            ?: throw IllegalStateException("Listener ${entity.listenerId} not found")

        // 2. Create foreach.do resume command
        val eventData = Json.parseToJsonElement(entity.event)
        val resumeCommand = createForeachResumeCommand(
            listener = listener,
            event = eventData,
            iterationIndex = entity.iterationIndex ?: 0
        )

        // 3. Send to workflow channel
        val messageId = entity.id.derive("-foreach-event")
        instanceEmitter.send(resumeCommand, messageId)

        logger.info {
            "Foreach event ${entity.id} sent for processing, " +
                "listener ${entity.listenerId}, iteration ${entity.iterationIndex}"
        }
    }

    private fun createForeachResumeCommand(
        listener: ListenerModel,
        event: JsonElement,
        iterationIndex: Int
    ): InstanceMessage<WorkflowCommand> {
        // Build scope with $item and $index variables
        // Resume at foreach.do position within the listen task
        // Implementation details...
    }
}
```

### WorkflowEventHandler Extensions

```kotlin
// In WorkflowEventHandler.kt

suspend fun handleListenForEachCompleted(message: InstanceMessage<ListenForEachCompleted>) {
    val event = message.workflowState
    val listenerId = extractListenerId(event.nodeStack)

    // 1. Mark current event as completed and store its output
    listenerEventRepository.markCompleted(
        listenerId = listenerId,
        iterationIndex = event.iterationIndex,
        output = Json.encodeToString(event.iterationOutput)
    )

    // 2. Increment iteration index
    listenerRepository.incrementForeachIndex(listenerId)

    // 3. Check for next pending event
    val nextEvent = listenerEventRepository.findNextPending(listenerId)

    if (nextEvent != null) {
        // 4a. Trigger next event processing (keep foreach_processing = TRUE)
        listenerEventRepository.markReadyForProcessing(nextEvent.id)
    } else {
        // 4b. No more pending events - check completion flag
        val listener = listenerRepository.findById(listenerId)!!

        if (listener.listenerCompleted) {
            // Completion criteria already met (flag set by CloudEventHandler)
            // Aggregate all foreach outputs
            val outputs = listenerEventRepository.getAllOutputs(listenerId)
            val outputArray = JsonArray(outputs.map { Json.parseToJsonElement(it) })

            // Mark listener for completion via existing outbox
            listenerRepository.markReadyForCompletion(
                id = listenerId,
                event = Json.encodeToString(outputArray)
            )
        }

        // Reset processing flag (whether completing or waiting for more events)
        listenerRepository.setForeachProcessing(listenerId, processing = false)
    }
}

suspend fun handleListenForEachFailed(message: InstanceMessage<ListenForEachFailed>) {
    val event = message.workflowState
    val listenerId = extractListenerId(event.nodeStack)

    // Mark event as failed
    listenerEventRepository.markFailed(
        listenerId = listenerId,
        iterationIndex = event.iterationIndex,
        error = event.error
    )

    // Based on listen task configuration, either:
    // - Retry the event (re-trigger via outbox)
    // - Skip and continue to next event
    // - Fail the entire listener

    // Reset processing flag
    listenerRepository.setForeachProcessing(listenerId, processing = false)
}
```

### State Machine Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         LISTENER STATE MACHINE (with foreach)                    │
└─────────────────────────────────────────────────────────────────────────────────┘

                            ┌─────────────────┐
                            │   LISTENING     │
                            │                 │
                            │ foreach_proc=F  │
                            │ events pending  │
                            └────────┬────────┘
                                     │
                          CloudEvent arrives
                          (first event or processing=FALSE)
                                     │
                                     ▼
                            ┌─────────────────┐
                            │  PROCESSING     │
                            │                 │
                            │ foreach_proc=T  │
                            │ foreach.do runs │
                            └────────┬────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    │                                  │
             foreach.do                          foreach.do
             completes                            fails
                    │                                  │
                    ▼                                  ▼
           ┌─────────────────┐               ┌─────────────────┐
           │ EVENT_COMPLETED │               │  EVENT_FAILED   │
           │                 │               │                 │
           │ Store output    │               │ Based on config │
           │ Check next      │               │ retry/skip/fail │
           └────────┬────────┘               └────────┬────────┘
                    │                                  │
      ┌─────────────┴─────────────┐                    │
      │                           │                    │
   More events                No more events           │
   pending                    + criteria met           │
      │                           │                    │
      ▼                           ▼                    │
┌─────────────┐           ┌─────────────────┐          │
│  LISTENING  │           │   COMPLETING    │          │
│             │──────────►│                 │◄─────────┘
│ (loop back) │           │ Aggregate all   │     (if fail listener)
└─────────────┘           │ iteration outputs│
                          │ Resume workflow │
                          └─────────────────┘
```

### Key Implementation Components

#### Core Components (All Strategies)

| Component                  | Purpose                                        | New/Modified |
|----------------------------|------------------------------------------------|--------------|
| `ListenForEachCompleted`   | Event when foreach.do iteration completes      | **NEW**      |
| `ListenForEachFailed`      | Event when foreach.do iteration fails          | **NEW**      |
| `ListenerCompletionOutbox` | Route to foreach.do OR complete (based on def) | Modified     |
| `WorkflowEventHandler`     | Handle new ListenForEach* events               | Modified     |

#### Additional Components (ALL / ANY+until with foreach only)

| Component                 | Purpose                                            | New/Modified |
|---------------------------|----------------------------------------------------|--------------|
| `ListenerEventOutbox`     | Process events from queue for foreach.do execution | **NEW**      |
| `ListenerEventModel`      | Add outbox columns + iteration tracking            | Modified     |
| `ListenerEventRepository` | Add outbox query methods                           | Modified     |
| `ListenerModel`           | Add `has_foreach`, `foreach_processing` columns    | Modified     |
| `ListenerRepository`      | Add foreach state management methods               | Modified     |
| `CloudEventHandler`       | Populate outbox fields when foreach enabled        | Modified     |
| V9 Database migrations    | Add new columns to both tables                     | **NEW**      |

#### Summary by Strategy

| Strategy            | CloudEventHandler | ListenerCompletionOutbox | ListenerEventOutbox | DB Changes   |
|---------------------|-------------------|--------------------------|---------------------|--------------|
| ONE (no foreach)    | Unchanged         | Unchanged                | Not used            | None         |
| ONE (foreach)       | Unchanged         | Modified (route to do)   | Not used            | None         |
| ANY (no foreach)    | Unchanged         | Unchanged                | Not used            | None         |
| ANY (foreach)       | Unchanged         | Modified (route to do)   | Not used            | None         |
| ALL (no foreach)    | Unchanged         | Unchanged                | Not used            | None         |
| ALL (foreach)       | Modified          | Modified                 | **NEW**             | V9 migration |
| ANY+until (no fe)   | Unchanged         | Unchanged                | Not used            | None         |
| ANY+until (foreach) | Modified          | Modified                 | **NEW**             | V9 migration |

### Edge Cases and Error Handling

#### 1. Event Arrives While foreach.do is Running

- Event is inserted into `lemline_listener_events` with `outbox_delayed_until = NULL`
- When current foreach.do completes, handler finds and triggers this event
- FIFO order maintained via `ORDER BY created_at`

#### 2. Listener Timeout During foreach Processing

- `ListenerTimeoutOutbox` checks `timeout_at`
- If timeout occurs while `foreach_processing = TRUE`:
    - Set `foreach_processing = FALSE`
    - Fail all pending events
    - Complete listener with partial outputs (or fail based on config)

#### 3. Worker Crash During foreach.do

- Standard outbox retry with exponential backoff
- Event's `outbox_attempt_count` incremented
- After max attempts, event marked `outbox_failed_at`
- Listener completion logic handles partial results

#### 4. Duplicate Event Processing (Consumer Rebalance)

- `FOR UPDATE SKIP LOCKED` prevents concurrent processing
- `foreach_processing = TRUE` flag blocks duplicate attempts
- Idempotent message IDs prevent duplicate workflow commands

### Performance Considerations

#### Sequential Processing Overhead

Unlike batch completion, foreach processes events one-at-a-time, adding latency:

```
Without foreach:
  N events arrive → 1 completion → 1 workflow resume
  Latency: O(1) * (outbox poll interval)

With foreach:
  N events arrive → N iterations → N workflow resumes → 1 completion
  Latency: O(N) * (outbox poll interval + foreach.do execution time)
```

#### Mitigation Strategies

1. **Low outbox poll interval** for `ListenerEventOutbox` (e.g., 100ms)
2. **Batch event arrival detection**: When `foreach_processing = FALSE` and multiple events pending,
   immediately trigger first event (no poll wait)
3. **Connection pooling**: Ensure sufficient DB connections for concurrent listeners

### Testing Strategy

| Test Case                         | Description                                        |
|-----------------------------------|----------------------------------------------------|
| `ForeachSingleEventTest`          | ONE strategy with foreach, single event processing |
| `ForeachMultipleEventsTest`       | ANY+until with foreach, multiple events queued     |
| `ForeachSequentialProcessingTest` | Verify FIFO order and sequential execution         |
| `ForeachConcurrentEventsTest`     | Events arriving while foreach.do runs              |
| `ForeachTimeoutTest`              | Timeout during foreach processing                  |
| `ForeachErrorHandlingTest`        | foreach.do failure scenarios                       |
| `ForeachOutputAggregationTest`    | Verify output.as transformation on outputs         |
| `ForeachScopeVariablesTest`       | $item and $index scope availability                |

---

## Implementation Summary

### lemline-core Changes

| Component           | Change Type | Description                                                       |
|---------------------|-------------|-------------------------------------------------------------------|
| `WorkflowState.kt`  | **NEW**     | Add `ListenForEachCompleted` event class                          |
| `WorkflowState.kt`  | **NEW**     | Add `ListenForEachFailed` event class                             |
| `ListenInstance.kt` | Modified    | Emit `ListenForEachCompleted`/`Failed` when foreach.do completes  |
| `Scope.kt`          | Modified    | Support `$item` and `$index` (or custom names) in foreach context |

### lemline-runner Changes

#### Database Migrations (V9)

| Table                     | Change                                                                                                                                       |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `lemline_listeners`       | Add `has_foreach`, `foreach_current_index`, `foreach_processing`, `listener_completed` columns                                               |
| `lemline_listener_events` | Add outbox columns (`outbox_scheduled_for`, `outbox_delayed_until`, `outbox_attempt_count`, `outbox_completed_at`, `outbox_failed_at`, etc.) |
| `lemline_listener_events` | Add `iteration_index`, `iteration_output` columns                                                                                            |

#### Models

| Component               | Change Type | Description                                                                              |
|-------------------------|-------------|------------------------------------------------------------------------------------------|
| `ListenerModel.kt`      | Modified    | Add `hasForeach`, `foreachCurrentIndex`, `foreachProcessing`, `listenerCompleted` fields |
| `ListenerEventModel.kt` | Modified    | Add outbox fields + `iterationIndex`, `iterationOutput` fields                           |

#### Repositories

| Component                    | Change Type | Description                                                                                  |
|------------------------------|-------------|----------------------------------------------------------------------------------------------|
| `ListenerRepository.kt`      | Modified    | Add `setForeachProcessing()`, `incrementForeachIndex()`, `setListenerCompleted()` methods    |
| `ListenerEventRepository.kt` | Modified    | Add outbox query methods, `findNextPending()`, `getAllOutputs()`, `markReadyForProcessing()` |

#### Outbox & Handlers

| Component                     | Change Type | Description                                                             |
|-------------------------------|-------------|-------------------------------------------------------------------------|
| `ListenerEventOutbox.kt`      | **NEW**     | Process events from queue for foreach.do (ALL/ANY+until only)           |
| `ListenerCompletionOutbox.kt` | Modified    | Route to foreach.do instead of completing (when foreach enabled)        |
| `CloudEventHandler.kt`        | Modified    | Atomic insert with `foreach_processing` flag management (ALL/ANY+until) |
| `CloudEventHandler.kt`        | Modified    | Set `listener_completed` flag when completion criteria met              |
| `WorkflowEventHandler.kt`     | Modified    | Handle `ListenForEachCompleted` and `ListenForEachFailed` events        |

#### Configuration

| Component                 | Change Type | Description                                                               |
|---------------------------|-------------|---------------------------------------------------------------------------|
| `LemlineConfiguration.kt` | Modified    | Add `ListenerEventOutbox` configuration (poll interval, batch size, etc.) |

### Implementation Order

1. **Phase 1: Core events** - Add `ListenForEachCompleted`/`Failed` events in lemline-core
2. **Phase 2: Database** - V9 migration adding columns to both tables
3. **Phase 3: Models & Repos** - Update models and repository methods
4. **Phase 4: ONE/ANY foreach** - Modify `ListenerCompletionOutbox` to route to foreach.do
5. **Phase 5: ALL/ANY+until foreach** - Add `ListenerEventOutbox`, modify `CloudEventHandler`
6. **Phase 6: Event handling** - Implement `WorkflowEventHandler` for foreach events
7. **Phase 7: Testing** - End-to-end tests for all strategies with foreach
