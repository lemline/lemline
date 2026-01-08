# Listen Task - Simplified Implementation Plan

## Overview

This plan simplifies the listen task implementation by using a **uniform event-based model** where all CloudEvents are stored in `listener_events`, with `foreach_completed_at` as the universal "event processed" flag.

## Design Principles

1. **Single source of truth**: `listener_events` is both event storage AND foreach outbox
2. **Uniform flow**: All strategies use the same INSERT → process → complete flow
3. **Batch SQL at stage boundaries**: Batch operations happen between stages, not within
4. **Clear separation of concerns**: Each component has one job

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ STAGE 1: Event Arrival (CloudEventHandler)                                  │
│ Job: Batch INSERT events into listener_events                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  CloudEvent → Match definitions → Batch INSERT into listener_events         │
│                                                                             │
│  • For ONE/ANY: INSERT with NOT EXISTS (first event wins)                   │
│  • For ALL/ANY+until: INSERT always (accumulating)                          │
│  • Set foreach_completed_at = NOW() if listener has no foreach              │
│  • Set foreach_completed_at = NULL if listener has foreach (pending)        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STAGE 2: Foreach Processing (ListenerForeachOutbox) - extends AbstractOutbox│
│ Job: Execute foreach.do for pending events, one at a time per listener      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Poll: SELECT events WHERE foreach_completed_at IS NULL                     │
│        AND listener.has_foreach = TRUE                                      │
│        (one per listener, FIFO order)                                       │
│                                                                             │
│  Process: Emit ResumeFromTask command to execute foreach.do                 │
│                                                                             │
│  On completion (via WorkflowEventHandler.handleListenForEachCompleted):     │
│  → UPDATE listener_events SET foreach_completed_at = NOW(), output = ?      │
│  → Trigger next pending event for this listener                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ STAGE 3: Completion Check (ListenerCompletionOutbox) - extends AbstractOutbox│
│ Job: Check completion criteria, emit resume command                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Step 1: Batch UPDATE - mark listeners as ready                             │
│          WHERE all events have foreach_completed_at IS NOT NULL             │
│          AND completion criteria met per strategy                           │
│                                                                             │
│  Step 2: Batch SELECT - fetch ready listeners with aggregated output        │
│          JOIN listener_events, aggregate foreach_output into array          │
│                                                                             │
│  Step 3: For each listener:                                                 │
│          → Emit ResumeWithCompletedTask command                             │
│          → Mark listener completed                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Tables

### lemline_listeners (simplified)

```sql
CREATE TABLE lemline_listeners (
    -- Identity
    id                      UUID PRIMARY KEY,
    workflow_namespace      VARCHAR(255) NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    workflow_version        VARCHAR(255) NOT NULL,
    workflow_id             UUID NOT NULL,
    workflow_position       TEXT NOT NULL,
    instance_message        TEXT NOT NULL,

    -- Listen configuration
    strategy                VARCHAR(20) NOT NULL,  -- ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL
    filters_count           INT,                   -- for ALL: required distinct filter matches
    until_expression        TEXT,                  -- for ANY_UNTIL_EXPR: jq expression
    has_foreach             BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_values      TEXT,                  -- JSON for correlation matching
    timeout_at              TIMESTAMP,

    -- State machine (linear progression)
    ready_at                TIMESTAMP,             -- set when completion criteria met

    -- Standard outbox fields (for completion processing)
    outbox_scheduled_for    TIMESTAMP NOT NULL,
    outbox_delayed_until    TIMESTAMP,             -- NULL = waiting, NOT NULL = ready for outbox
    outbox_attempt_count    INT NOT NULL DEFAULT 0,
    outbox_error_class      VARCHAR(255),
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMP,
    outbox_failed_at        TIMESTAMP,

    -- Cleanup
    cleanup_after           TIMESTAMP,

    -- Timestamps
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for finding pending listeners by query key
CREATE INDEX idx_listeners_pending ON lemline_listeners
    (workflow_namespace, workflow_name, workflow_version, workflow_position)
    WHERE outbox_completed_at IS NULL;

-- Index for completion outbox
CREATE INDEX idx_listeners_ready ON lemline_listeners (ready_at)
    WHERE ready_at IS NOT NULL AND outbox_completed_at IS NULL;

-- Index for cleanup
CREATE INDEX idx_listeners_cleanup ON lemline_listeners (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
```

### lemline_listener_events (following standard outbox pattern)

```sql
CREATE TABLE lemline_listener_events (
    -- Identity
    id                      UUID PRIMARY KEY,
    listener_id             UUID NOT NULL REFERENCES lemline_listeners(id) ON DELETE CASCADE,

    -- Event data
    filter_index            INT,                   -- which filter matched (for ALL strategy)
    event                   TEXT NOT NULL,         -- the CloudEvent data (JSON)

    -- Sequence for FIFO ordering (per listener)
    sequence                BIGINT NOT NULL,       -- 1, 2, 3... per listener

    -- Foreach output (captured after foreach.do completes)
    foreach_output          TEXT,                  -- output from foreach.do iteration

    -- Standard outbox fields (for foreach processing via AbstractOutbox)
    outbox_scheduled_for    TIMESTAMP NOT NULL,    -- when this event was inserted
    outbox_delayed_until    TIMESTAMP,             -- NULL = waiting for FIFO turn, NOT NULL = ready
    outbox_attempt_count    INT NOT NULL DEFAULT 0,
    outbox_error_class      VARCHAR(255),
    outbox_error_message    TEXT,
    outbox_error_stacktrace TEXT,
    outbox_completed_at     TIMESTAMP,             -- foreach.do completed successfully
    outbox_failed_at        TIMESTAMP,             -- foreach.do failed after max retries

    -- Cleanup
    cleanup_after           TIMESTAMP,

    -- Timestamps
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Ensure unique sequence per listener
    UNIQUE (listener_id, sequence)
);

-- Index for foreach outbox processing (FIFO ordering)
-- Find events ready for processing: delayed_until <= NOW, not completed/failed
CREATE INDEX idx_listener_events_outbox ON lemline_listener_events
    (listener_id, outbox_delayed_until, sequence)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for counting in-flight events (FIFO check)
CREATE INDEX idx_listener_events_processing ON lemline_listener_events
    (listener_id, outbox_delayed_until)
    WHERE outbox_completed_at IS NULL
      AND outbox_failed_at IS NULL
      AND outbox_delayed_until IS NOT NULL;

-- Index for finding events by listener
CREATE INDEX idx_listener_events_listener ON lemline_listener_events (listener_id);

-- Index for ALL strategy completion check
CREATE INDEX idx_listener_events_filter ON lemline_listener_events (listener_id, filter_index);

-- Index for cleanup
CREATE INDEX idx_listener_events_cleanup ON lemline_listener_events (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
```

**State tracking via outbox columns:**
- **Waiting for FIFO turn**: `outbox_delayed_until IS NULL` (waiting for previous events to complete)
- **Ready for processing**: `outbox_delayed_until IS NOT NULL AND <= NOW()` (can be claimed)
- **Processing**: Claimed via `FOR UPDATE SKIP LOCKED` (in-flight)
- **Completed**: `outbox_completed_at IS NOT NULL`
- **Failed**: `outbox_failed_at IS NOT NULL` (max retries exceeded)
- **Skipped**: `outbox_completed_at = created_at` (no foreach, completed immediately)

**FIFO enforcement**:
- First event for a listener gets `outbox_delayed_until = NOW()` (immediately ready)
- Subsequent events get `outbox_delayed_until = NULL` (waiting)
- When an event completes, the next event's `outbox_delayed_until` is set to `NOW()`

## Components

### 1. CloudEventHandler (existing, simplified)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/cloudevents/CloudEventHandler.kt`

**Responsibility:** Batch INSERT events for all matching listeners.

**Supporting types** (from `DefinitionListenService.kt`):

```kotlin
/**
 * Result of matching a CloudEvent against workflow definitions.
 * Created by DefinitionListenService.findMatchingListenTasks().
 */
data class MatchingListenTask(
    val listenTask: CachedListenTask,
    /** Correlation values extracted from the event using 'correlate.from' expressions */
    val correlationValuesJson: String?,
    /** Filter index that matched - relevant for ALL and ANY+until strategies */
    val filterIndex: Int
) {
    // Delegated properties from CachedListenTask
    val workflowInfo: WorkflowInfo get() = listenTask.workflowInfo
    val nodePosition: NodePosition get() = listenTask.nodePosition
    val strategy: ListenStrategy get() = listenTask.strategy
    val readAs: ListenAndReadAs get() = listenTask.readAs
    val until: CachedUntilCondition? get() = listenTask.until
    val hasForeach: Boolean get() = listenTask.hasForeach

    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = correlationValuesJson,
        filterIndex = filterIndex
    )
}

/**
 * Result of matching a CloudEvent against termination filters.
 * Created by DefinitionListenService.findMatchingUntilEvents().
 */
data class MatchingListenTaskUntilEvent(
    val listenTask: CachedListenTask
) {
    val workflowInfo: WorkflowInfo get() = listenTask.workflowInfo
    val nodePosition: NodePosition get() = listenTask.nodePosition
    val hasForeach: Boolean get() = listenTask.hasForeach

    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = null  // Termination events don't use correlation
    )
}
```

```kotlin
class CloudEventHandler {

    suspend fun handleCloudEvent(cloudEvent: CloudEvent) {
        // Step 1: Find matching definitions from cache
        // This returns TWO types of matches:
        // - matchingListenTasks: event matches a listen filter (accumulate/complete)
        // - matchingUntilEvents: event matches a termination filter (stop accumulating)
        val matchingListenTasks = definitionListenService.findMatchingListenTasks(cloudEvent, eventDataProvider)
        val matchingUntilEvents = definitionListenService.findMatchingUntilEvents(cloudEvent, eventDataProvider)

        if (matchingListenTasks.isEmpty() && matchingUntilEvents.isEmpty()) return

        // Step 2: Process regular event matches (accumulate or complete)
        if (matchingListenTasks.isNotEmpty()) {
            val (oneAnyMatches, accumulatingMatches) = matchingListenTasks.partition {
                it.strategy in listOf(ListenerStrategy.ONE, ListenerStrategy.ANY) && it.until == null
            }

            coroutineScope {
                // ONE/ANY (without until): first event wins
                if (oneAnyMatches.isNotEmpty()) {
                    launch { insertForOneAny(oneAnyMatches, cloudEvent) }
                }
                // ALL/ANY+until: accumulating events
                if (accumulatingMatches.isNotEmpty()) {
                    launch { insertForAccumulating(accumulatingMatches, cloudEvent) }
                }
            }
        }

        // Step 3: Process termination events (ANY_UNTIL_EVENT strategy)
        // This marks listeners as ready when their termination event arrives
        if (matchingUntilEvents.isNotEmpty()) {
            processTerminationEvent(matchingUntilEvents)
        }
    }

    private suspend fun insertForOneAny(matches: List<MatchingListenTask>, cloudEvent: CloudEvent) {
        val keys = matches.map { it.toQueryKey() }
        val eventJson = extractEventContent(matches.first().readAs, cloudEvent)

        // Batch INSERT with NOT EXISTS (first event wins)
        // Sets outbox_completed_at based on listener.has_foreach
        listenerEventRepository.batchInsertForOneAny(keys, eventJson)
    }

    private suspend fun insertForAccumulating(matches: List<MatchingListenTask>, cloudEvent: CloudEvent) {
        // Group by (readAs, filterIndex) since both affect the INSERT
        // - readAs: determines how to extract event content
        // - filterIndex: stored in listener_events.filter_index column (for ALL strategy completion check)
        val byReadAsAndFilterIndex = matches.groupBy { it.readAs to it.filterIndex }

        for ((key, groupMatches) in byReadAsAndFilterIndex) {
            val (readAs, filterIndex) = key
            val queryKeys = groupMatches.map { it.toQueryKey() }
            val eventJson = extractEventContent(readAs, cloudEvent)

            // Batch INSERT for this (readAs, filterIndex) group
            listenerEventRepository.batchInsertForAccumulating(queryKeys, eventJson, filterIndex)
        }

        // For ANY_UNTIL_EXPR without foreach: evaluate until immediately
        val allKeys = matches.map { it.toQueryKey() }
        evaluateUntilAfterInsert(allKeys)
    }

    /**
     * Processes termination events for ANY_UNTIL_EVENT strategy.
     * Marks matching listeners as ready for completion.
     * The termination event itself is NOT added to listener_events.
     */
    private suspend fun processTerminationEvent(matches: List<MatchingListenTaskUntilEvent>) {
        val keys = matches.map { it.toQueryKey() }

        // Mark listeners ready (all accumulated events must be completed first)
        val marked = listenerRepository.batchMarkReadyByTermination(keys)

        if (marked > 0) {
            logger.info { "Termination event marked $marked ANY_UNTIL_EVENT listeners ready" }
        }
    }
}
```

### 2. ListenerEventRepository (follows standard pattern)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/ListenerEventRepository.kt`

**Structure:** Extends `CrudRepository<ListenerEventModel>` with composed operations.

```kotlin
const val LISTENER_EVENT_TABLE = "lemline_listener_events"

/**
 * Repository for managing listener events (foreach processing outbox).
 * Uses composition to provide outbox, cleaner, and ID operations.
 *
 * @see ListenerEventModel for the event model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerEventRepository : CrudRepository<ListenerEventModel>(),
    WithIdRepository<ListenerEventModel>,
    WithOutboxRepository<ListenerEventModel>,
    WithCleanerRepository<ListenerEventModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_EVENT_TABLE

    // Composed operations - initialized lazily to ensure databaseManager is injected
    private val idOps by lazy { IdRepository(tableName, idHelper, ::createModel, databaseManager) }
    private val outboxOps by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }
    private val cleanerOps by lazy { CleanerRepository(tableName, ::createModel, databaseManager) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idOps.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idOps.deleteById(id, connection)

    // Delegate WithOutboxRepository methods (FIFO-aware override)
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?): List<ListenerEventModel> =
        findEntitiesToProcessFifo(maxAttempts, limit, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerOps.findEntitiesToDelete(cutoffDate, batchSize, connection)

    // Column bindings
    override val columns: ColumnBindings<ListenerEventModel> by lazy {
        ColumnBindingsBuilder<ListenerEventModel>().apply {
            idColumn(idHelper)
            column("listener_id") { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenerId) }
            column("filter_index") { stmt, entity, idx ->
                entity.filterIndex?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
            column("event") { stmt, entity, idx -> stmt.setString(idx, entity.event) }
            column("sequence") { stmt, entity, idx -> stmt.setLong(idx, entity.sequence) }
            column("foreach_output") { stmt, entity, idx ->
                entity.foreachOutput?.let { stmt.setString(idx, it) } ?: stmt.setNull(idx, Types.VARCHAR)
            }
            outboxColumns()
            cleanupColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet) = ListenerEventModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        listenerId = getIDV7(rs, "listener_id")!!,
        filterIndex = rs.getInt("filter_index").takeIf { !rs.wasNull() },
        event = rs.getString("event"),
        sequence = rs.getLong("sequence"),
        foreachOutput = rs.getString("foreach_output"),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)

    // =====================================================================
    // Custom methods for listener event operations
    // =====================================================================

    /**
     * FIFO-aware findEntitiesToProcess.
     * Only returns events where outbox_delayed_until IS NOT NULL (ready for processing).
     * Events with outbox_delayed_until = NULL are waiting for previous events to complete.
     */
    private suspend fun findEntitiesToProcessFifo(
        maxAttempts: Int,
        limit: Int,
        connection: Connection?
    ): List<ListenerEventModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findEntitiesToProcessFifoSQL).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(Clock.System.now().toJavaInstant()))
            stmt.setInt(2, maxAttempts)
            stmt.setInt(3, limit)
            stmt.executeQuery().use { rs -> rs.toModels() }
        }
    }

    private val findEntitiesToProcessFifoSQL by lazy {
        """
            SELECT e.* FROM $tableName e
            JOIN $LISTENER_TABLE l ON l.id = e.listener_id
            WHERE e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
              AND e.$OUTBOX_FAILED_AT_COLUMN IS NULL
              AND e.$OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
              AND e.$OUTBOX_DELAYED_UNTIL_COLUMN <= ?
              AND e.$OUTBOX_ATTEMPT_COUNT_COLUMN < ?
              AND l.has_foreach = TRUE
              AND l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
            ORDER BY e.listener_id, e.sequence
            LIMIT ?
            FOR UPDATE OF e SKIP LOCKED
        """.trimIndent()
    }

    /**
     * Mark event as completed with foreach output.
     * Also triggers the next event in the FIFO queue.
     */
    suspend fun markCompletedWithOutput(
        id: IDV7,
        output: String,
        connection: Connection? = null
    ): Int = withTransaction(connection) { conn ->
        // Step 1: Mark this event as completed
        val updated = conn.prepareStatement(markCompletedSQL).use { stmt ->
            stmt.setString(1, output)
            stmt.setTimestamp(2, Timestamp.from(Clock.System.now().toJavaInstant()))
            setIDV7(stmt, 3, id)
            stmt.executeUpdate()
        }

        // Step 2: Trigger next event in FIFO queue
        if (updated > 0) {
            triggerNextEvent(id, conn)
        }

        updated
    }

    private val markCompletedSQL by lazy {
        """
            UPDATE $tableName
            SET $OUTBOX_COMPLETED_AT_COLUMN = CURRENT_TIMESTAMP,
                foreach_output = ?,
                $CLEANUP_AFTER_COLUMN = ?
            WHERE $ID_COLUMN = ?
              AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
        """.trimIndent()
    }

    /**
     * Trigger the next event in the FIFO queue for the same listener.
     */
    private suspend fun triggerNextEvent(completedEventId: IDV7, connection: Connection) {
        connection.prepareStatement(triggerNextEventSQL).use { stmt ->
            setIDV7(stmt, 1, completedEventId)
            stmt.executeUpdate()
        }
    }

    private val triggerNextEventSQL by lazy {
        """
            UPDATE $tableName
            SET $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP
            WHERE $ID_COLUMN = (
                SELECT e.id FROM $tableName e
                WHERE e.listener_id = (SELECT listener_id FROM $tableName WHERE $ID_COLUMN = ?)
                  AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND e.$OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND e.$OUTBOX_DELAYED_UNTIL_COLUMN IS NULL
                ORDER BY e.sequence
                LIMIT 1
            )
        """.trimIndent()
    }

    /**
     * Batch INSERT for ONE/ANY strategy with atomic sequence assignment.
     * Uses NOT EXISTS to ensure only first event is stored.
     * Sets outbox_delayed_until based on whether listener has foreach.
     */
    suspend fun batchInsertForOneAny(
        keys: List<ListenerQueryKey>,
        eventJson: String,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        val whereClause = ListenerQueryKey.buildWhereClause(keys, "l")

        return withConnection(connection) { conn ->
            conn.prepareStatement(batchInsertOneAnySQL(whereClause)).use { stmt ->
                setIDV7(stmt, 1, IDV7.random())
                stmt.setString(2, eventJson)
                stmt.setTimestamp(3, Timestamp.from(Clock.System.now().toJavaInstant()))
                var idx = 4
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    private fun batchInsertOneAnySQL(whereClause: String) = """
        INSERT INTO $tableName
            ($ID_COLUMN, listener_id, filter_index, event, sequence,
             $OUTBOX_SCHEDULED_FOR_COLUMN, $OUTBOX_DELAYED_UNTIL_COLUMN, $OUTBOX_COMPLETED_AT_COLUMN, $OUTBOX_ATTEMPT_COUNT_COLUMN)
        SELECT
            ?,
            l.id,
            NULL,
            ?,
            COALESCE((SELECT MAX(e.sequence) FROM $tableName e WHERE e.listener_id = l.id), 0) + 1,
            ?,
            CASE WHEN l.has_foreach THEN CURRENT_TIMESTAMP ELSE NULL END,
            CASE WHEN l.has_foreach THEN NULL ELSE CURRENT_TIMESTAMP END,
            0
        FROM $LISTENER_TABLE l
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.strategy IN ('ONE', 'ANY')
          AND NOT EXISTS (SELECT 1 FROM $tableName e WHERE e.listener_id = l.id)
          AND ($whereClause)
    """.trimIndent()

    /**
     * Batch INSERT for ALL/ANY+until strategies (accumulating).
     */
    suspend fun batchInsertForAccumulating(
        keys: List<ListenerQueryKey>,
        eventJson: String,
        filterIndex: Int,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        val whereClause = ListenerQueryKey.buildWhereClause(keys, "l")

        return withConnection(connection) { conn ->
            conn.prepareStatement(batchInsertAccumulatingSQL(whereClause)).use { stmt ->
                setIDV7(stmt, 1, IDV7.random())
                stmt.setString(2, eventJson)
                stmt.setInt(3, filterIndex)
                stmt.setTimestamp(4, Timestamp.from(Clock.System.now().toJavaInstant()))
                var idx = 5
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    private fun batchInsertAccumulatingSQL(whereClause: String) = """
        INSERT INTO $tableName
            ($ID_COLUMN, listener_id, filter_index, event, sequence,
             $OUTBOX_SCHEDULED_FOR_COLUMN, $OUTBOX_DELAYED_UNTIL_COLUMN, $OUTBOX_COMPLETED_AT_COLUMN, $OUTBOX_ATTEMPT_COUNT_COLUMN)
        SELECT
            ?,
            l.id,
            ?,
            ?,
            COALESCE((SELECT MAX(e.sequence) FROM $tableName e WHERE e.listener_id = l.id), 0) + 1,
            ?,
            CASE
                WHEN l.has_foreach AND NOT EXISTS (
                    SELECT 1 FROM $tableName e2
                    WHERE e2.listener_id = l.id AND e2.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                      AND e2.$OUTBOX_FAILED_AT_COLUMN IS NULL
                ) THEN CURRENT_TIMESTAMP  -- First event for this listener, ready now
                WHEN l.has_foreach THEN NULL  -- Waiting for previous events
                ELSE NULL  -- No foreach, completed immediately
            END,
            CASE
                WHEN l.has_foreach THEN NULL  -- Needs foreach processing
                ELSE CURRENT_TIMESTAMP  -- No foreach, mark as completed immediately
            END,
            0
        FROM $LISTENER_TABLE l
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.strategy IN ('ALL', 'ANY_UNTIL_EXPR', 'ANY_UNTIL_EVENT')
          AND ($whereClause)
    """.trimIndent()

    /**
     * Get aggregated foreach outputs for a listener.
     */
    suspend fun getOutputs(listenerId: IDV7, connection: Connection? = null): List<String> =
        withConnection(connection) { conn ->
            conn.prepareStatement(getOutputsSQL).use { stmt ->
                setIDV7(stmt, 1, listenerId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            rs.getString("foreach_output")?.let { add(it) }
                        }
                    }
                }
            }
        }

    private val getOutputsSQL by lazy {
        """
            SELECT foreach_output FROM $tableName
            WHERE listener_id = ?
              AND $OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
            ORDER BY sequence
        """.trimIndent()
    }
}
```

### 3. ListenerRepository (follows standard pattern)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/ListenerRepository.kt`

**Structure:** Extends `CrudRepository<ListenerModel>` with composed operations.

```kotlin
const val LISTENER_TABLE = "lemline_listeners"

/**
 * Repository for managing listeners (completion processing outbox).
 * Uses composition to provide outbox, cleaner, instance, and ID operations.
 *
 * @see ListenerModel for the listener model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerRepository : CrudRepository<ListenerModel>(),
    WithIdRepository<ListenerModel>,
    WithOutboxRepository<ListenerModel>,
    WithInstanceRepository<ListenerModel>,
    WithCleanerRepository<ListenerModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_TABLE

    // Composed operations - initialized lazily to ensure databaseManager is injected
    private val idOps by lazy { IdRepository(tableName, idHelper, ::createModel, databaseManager) }
    private val outboxOps by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }
    private val cleanerOps by lazy { CleanerRepository(tableName, ::createModel, databaseManager) }
    private val instanceOps by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseManager) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idOps.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idOps.deleteById(id, connection)

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxOps.findEntitiesToProcess(maxAttempts, limit, connection)

    // Delegate WithInstanceRepository methods
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        instanceOps.findByWorkflowId(workflowId, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerOps.findEntitiesToDelete(cutoffDate, batchSize, connection)

    // Column bindings
    override val columns: ColumnBindings<ListenerModel> by lazy {
        ColumnBindingsBuilder<ListenerModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            column("strategy") { stmt, entity, idx -> stmt.setString(idx, entity.strategy.name) }
            column("filters_count") { stmt, entity, idx ->
                entity.filtersCount?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            }
            column("has_until") { stmt, entity, idx -> stmt.setBoolean(idx, entity.hasUntil) }
            column("until_expression") { stmt, entity, idx ->
                entity.untilExpression?.let { stmt.setString(idx, it) } ?: stmt.setNull(idx, Types.VARCHAR)
            }
            column("has_foreach") { stmt, entity, idx -> stmt.setBoolean(idx, entity.hasForeach) }
            column("correlation_values") { stmt, entity, idx ->
                entity.correlationValues?.let { stmt.setString(idx, it) } ?: stmt.setNull(idx, Types.VARCHAR)
            }
            column("timeout_at") { stmt, entity, idx ->
                entity.timeoutAt?.let { stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant())) }
                    ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            column("ready_at") { stmt, entity, idx ->
                entity.readyAt?.let { stmt.setTimestamp(idx, Timestamp.from(it.toJavaInstant())) }
                    ?: stmt.setNull(idx, Types.TIMESTAMP)
            }
            outboxColumns()
            cleanupColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet) = ListenerModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.ListenStarted>(idHelper)!!,
        strategy = ListenerStrategy.valueOf(rs.getString("strategy")),
        filtersCount = rs.getInt("filters_count").takeIf { !rs.wasNull() },
        hasUntil = rs.getBoolean("has_until"),
        untilExpression = rs.getString("until_expression"),
        hasForeach = rs.getBoolean("has_foreach"),
        correlationValues = rs.getString("correlation_values"),
        timeoutAt = rs.getInstant("timeout_at"),
        readyAt = rs.getInstant("ready_at"),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)

    // =====================================================================
    // Custom methods for listener operations
    // =====================================================================

    /**
     * Find listener by workflow ID and position.
     */
    suspend fun findByWorkflowIdAndPosition(
        workflowId: WorkflowId,
        position: NodePosition,
        connection: Connection? = null
    ): ListenerModel? = withConnection(connection) { conn ->
        conn.prepareStatement(findByWorkflowIdAndPositionSQL).use { stmt ->
            setIDV7(stmt, 1, workflowId)
            stmt.setString(2, position.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) createModel(rs) else null
            }
        }
    }

    private val findByWorkflowIdAndPositionSQL by lazy {
        """
            SELECT * FROM $tableName
            WHERE $WORKFLOW_ID_COLUMN = ?
              AND $WORKFLOW_POSITION_COLUMN = ?
        """.trimIndent()
    }

    /**
     * Batch marks listeners as ready when:
     * 1. All events have outbox_completed_at IS NOT NULL (foreach done or skipped)
     * 2. Completion criteria met per strategy
     */
    suspend fun batchMarkReady(connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(batchMarkReadySQL).executeUpdate()
        }

    private val batchMarkReadySQL by lazy {
        """
            UPDATE $tableName l
            SET ready_at = CURRENT_TIMESTAMP,
                $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
                $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
            WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
              AND l.ready_at IS NULL
              -- All events must be completed (foreach done or no foreach)
              AND NOT EXISTS (
                  SELECT 1 FROM $LISTENER_EVENT_TABLE e
                  WHERE e.listener_id = l.$ID_COLUMN
                    AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
              )
              -- Must have at least one event
              AND EXISTS (
                  SELECT 1 FROM $LISTENER_EVENT_TABLE e
                  WHERE e.listener_id = l.$ID_COLUMN
              )
              -- Strategy-specific completion check
              AND (
                  -- ONE/ANY: one event is enough
                  l.strategy IN ('ONE', 'ANY')
                  OR
                  -- ALL: need filters_count distinct filter indices
                  (l.strategy = 'ALL' AND
                   (SELECT COUNT(DISTINCT e.filter_index)
                    FROM $LISTENER_EVENT_TABLE e
                    WHERE e.listener_id = l.$ID_COLUMN
                      AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL) >= l.filters_count)
                  OR
                  -- ANY_UNTIL_EVENT / ANY_UNTIL_EXPR: handled by separate paths
                  FALSE
              )
        """.trimIndent()
    }

    /**
     * Batch mark ANY_UNTIL_EVENT listeners as ready (termination event received).
     */
    suspend fun batchMarkReadyByTermination(
        keys: List<ListenerQueryKey>,
        connection: Connection? = null
    ): Int {
        if (keys.isEmpty()) return 0

        val whereClause = ListenerQueryKey.buildWhereClause(keys, "l")

        return withConnection(connection) { conn ->
            conn.prepareStatement(batchMarkReadyByTerminationSQL(whereClause)).use { stmt ->
                var idx = 1
                ListenerQueryKey.bindAllParameters(keys, stmt, idx)
                stmt.executeUpdate()
            }
        }
    }

    private fun batchMarkReadyByTerminationSQL(whereClause: String) = """
        UPDATE $tableName l
        SET ready_at = CURRENT_TIMESTAMP,
            $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
            $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.ready_at IS NULL
          AND l.strategy = 'ANY_UNTIL_EVENT'
          -- All accumulated events must be completed
          AND NOT EXISTS (
              SELECT 1 FROM $LISTENER_EVENT_TABLE e
              WHERE e.listener_id = l.$ID_COLUMN
                AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          )
          AND ($whereClause)
    """.trimIndent()

    /**
     * Find listeners for until expression evaluation.
     */
    suspend fun findListenersForUntilEvaluation(
        limit: Int,
        connection: Connection? = null
    ): List<Pair<ListenerModel, List<String>>> = withConnection(connection) { conn ->
        conn.prepareStatement(findListenersForUntilEvaluationSQL).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val listener = createModel(rs)
                        val eventsJson = rs.getString("events_json")
                        val events = parseJsonArray(eventsJson)
                        add(listener to events)
                    }
                }
            }
        }
    }

    private val findListenersForUntilEvaluationSQL by lazy {
        val jsonAgg = databaseManager.jsonArrayAgg("e.event")
        """
            SELECT l.*, $jsonAgg as events_json
            FROM $tableName l
            LEFT JOIN $LISTENER_EVENT_TABLE e ON e.listener_id = l.$ID_COLUMN
            WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
              AND l.ready_at IS NULL
              AND l.strategy = 'ANY_UNTIL_EXPR'
              AND NOT EXISTS (
                  SELECT 1 FROM $LISTENER_EVENT_TABLE e2
                  WHERE e2.listener_id = l.$ID_COLUMN
                    AND e2.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
              )
              AND EXISTS (
                  SELECT 1 FROM $LISTENER_EVENT_TABLE e3
                  WHERE e3.listener_id = l.$ID_COLUMN
              )
            GROUP BY l.$ID_COLUMN
            LIMIT ?
            FOR UPDATE OF l SKIP LOCKED
        """.trimIndent()
    }

    /**
     * Mark a single listener as ready.
     */
    suspend fun markReady(id: IDV7, connection: Connection? = null): Int =
        withConnection(connection) { conn ->
            conn.prepareStatement(markReadySQL).use { stmt ->
                setIDV7(stmt, 1, id)
                stmt.executeUpdate()
            }
        }

    private val markReadySQL by lazy {
        """
            UPDATE $tableName
            SET ready_at = CURRENT_TIMESTAMP,
                $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
                $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
            WHERE $ID_COLUMN = ?
              AND ready_at IS NULL
        """.trimIndent()
    }
}
```

### 4. ListenerForeachOutbox (extends AbstractOutbox)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/ListenerForeachOutbox.kt`

**Responsibility:** Process foreach.do for pending events using standard outbox pattern with FIFO ordering.

```kotlin
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerForeachOutbox : AbstractOutbox<ListenerEventModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: ListenerEventRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val crudRepository: WithCrudRepository<ListenerEventModel> get() = outboxRepository

    override val enabled by lazy {
        lemlineConfig.outbox().listener().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    override val outboxConf by lazy { lemlineConfig.outbox().listener().foreach() }
    override val cleanerConf by lazy { lemlineConfig.outbox().listener().cleanup() }

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    /**
     * Process a single event by emitting foreach command.
     * The event's outbox_completed_at is set when foreach.do completes
     * (via WorkflowEventHandler.handleListenForEachCompleted).
     */
    override suspend fun process(entity: ListenerEventModel) {
        // Fetch listener for workflow info
        val listener = listenerRepository.findById(entity.listenerId)
            ?: error("Listener ${entity.listenerId} not found")

        // Emit foreach command
        val messageId = entity.id.derive("-foreach")
        instanceEmitter.send(
            InstanceMessage(
                workflowInfo = listener.instanceMessage.workflowInfo,
                workflowState = WorkflowCommand.ResumeFromTask(
                    nodeStack = listener.instanceMessage.workflowState.nodeStack,
                    nodePosition = listener.instanceMessage.workflowState.nodePosition.appendForeach(),
                    rawInput = Json.parseToJsonElement(entity.event)
                )
            ),
            messageId
        )

        logger.debug { "Emitted foreach command for event ${entity.id}, listener ${listener.id}" }

        // Note: Do NOT mark completed here - that happens in handleListenForEachCompleted
        // The entity stays in "processing" state (claimed but not completed)
    }
}
```

**Key points:**
- Extends `AbstractOutbox<ListenerEventModel>` - inherits retry logic, error tracking, cleanup
- `outboxRepository.findEntitiesToProcess()` enforces FIFO via `outbox_delayed_until`
- Event's `outbox_completed_at` is set by `WorkflowEventHandler.handleListenForEachCompleted`
- When completed, `triggerNextEvent()` sets next event's `outbox_delayed_until = NOW()`

**FIFO enforcement via outbox_delayed_until:**

```
Listener A events:

Initial state (first event ready, others waiting):
[seq=1: delayed_until=NOW]  [seq=2: delayed_until=NULL]  [seq=3: delayed_until=NULL]
        ↑ ready                      ↑ waiting                    ↑ waiting

After seq=1 is claimed and processed:
[seq=1: completed_at=NOW]   [seq=2: delayed_until=NULL]  [seq=3: delayed_until=NULL]
        ↑ done                       ↑ still waiting              ↑ waiting

When handleListenForEachCompleted marks seq=1 done, triggerNextEvent runs:
[seq=1: completed_at=NOW]   [seq=2: delayed_until=NOW]   [seq=3: delayed_until=NULL]
        ↑ done                       ↑ NOW ready                  ↑ waiting

Next poll claims seq=2:
[seq=1: completed_at=NOW]   [seq=2: processing...]       [seq=3: delayed_until=NULL]
```

### 5. ListenerCompletionOutbox (replaces current ListenerOutbox)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/ListenerCompletionOutbox.kt`

**Responsibility:** Check completion criteria, emit resume commands.

```kotlin
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerCompletionOutbox : AbstractOutbox<ListenerModel>() {

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var failureRepository: FailureRepository

    @Inject
    override lateinit var outboxRepository: ListenerRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val crudRepository get() = outboxRepository

    override val enabled by lazy { /* config check */ }
    override val outboxConf by lazy { lemlineConfig.outbox().listener().completion() }
    override val cleanerConf by lazy { lemlineConfig.outbox().listener().cleanup() }

    /**
     * Override doWork to add batch mark-ready step before standard processing.
     */
    override suspend fun doWork() {
        // Step 1: Batch mark listeners as ready
        outboxRepository.batchMarkReady()

        // Step 2: Process ready listeners (standard AbstractOutbox flow)
        super.doWork()
    }

    /**
     * Process a ready listener by emitting resume command.
     */
    override suspend fun process(entity: ListenerModel) {
        // Fetch aggregated output
        val outputs = listenerEventRepository.getOutputs(entity.id)
        val outputArray = JsonArray(outputs.map { Json.parseToJsonElement(it) })

        // Emit resume command
        val messageId = entity.id.derive("-resume")
        instanceEmitter.send(
            InstanceMessage(
                workflowInfo = entity.instanceMessage.workflowInfo,
                workflowState = WorkflowCommand.ResumeWithCompletedTask(
                    nodeStack = entity.instanceMessage.workflowState.nodeStack,
                    rawOutput = outputArray
                )
            ),
            messageId
        )

        // Mark for cleanup
        entity.cleanupAfter = Clock.System.now()
    }
}
```

### 6. WorkflowEventHandler.handleListenForEachCompleted (simplified)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`

```kotlin
/**
 * Handles foreach iteration completion.
 *
 * Simplified: just mark event complete and trigger next.
 */
private suspend fun handleListenForEachCompleted(
    message: InstanceMessage<WorkflowEvent.ListenForEachCompleted>
) {
    val state = message.workflowState
    val output = LemlineJson.encodeToString(state.output)

    databaseManager.withTransaction { conn ->
        // Find the listener
        val listener = listenerRepository.findByWorkflowIdAndPosition(
            message.workflowId,
            state.nodePosition,
            conn
        ) ?: error("Listener not found")

        // Find current event (the one being processed)
        val currentEvent = listenerEventRepository.findProcessingEvent(listener.id, conn)
            ?: error("No processing event found")

        // Mark event as foreach-completed
        listenerEventRepository.markForeachCompleted(currentEvent.id, output, conn)

        // Clear processing flag
        listenerRepository.setForeachProcessing(listener.id, false, conn)

        // Trigger next event (if any) - will be picked up by ListenerForeachOutbox
        // Nothing to do here - outbox will find next pending event
    }
}
```

### 7. ListenerCleaner (extends AbstractCleaner)

**File:** `lemline-runner/src/main/kotlin/com/lemline/runner/cleaner/ListenerCleaner.kt`

```kotlin
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerCleaner : AbstractCleaner<ListenerModel>() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    override lateinit var cleanerRepository: ListenerRepository

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val crudRepository get() = cleanerRepository

    override val enabled by lazy { lemlineConfig.outbox().listener().cleanup().enabled() }
    override val cleanerConf by lazy { lemlineConfig.outbox().listener().cleanup() }

    // listener_events are cleaned via ON DELETE CASCADE
}
```

## Handling Special Cases

### Correlation (`correlate` property)

Correlation allows listeners to filter events based on values extracted from the event data. The current implementation uses **Mode 2: First-sets-baseline** where the first matching event sets the correlation values, and subsequent events must match.

**How it works:**

1. **Definition-time**: Each filter can define `correlate` with `from` expressions:
   ```yaml
   listen:
     to:
       any:
         - with:
             type: order.item.added
           correlate:
             orderId:
               from: .orderId
   ```

2. **Event matching**: `DefinitionListenService.extractCorrelationValues()` evaluates `correlate.from` expressions against event data

3. **Query key**: `ListenerQueryKey` includes `correlationValuesJson` for database matching

4. **Database matching**: SQL uses `(correlation_values IS NULL OR correlation_values = ?)`:
   - If listener's `correlation_values` is NULL: Match any event (first event sets baseline)
   - If listener's `correlation_values` is set: Match only events with same correlation values

**Data flow:**

```
CloudEvent arrives
    │
    ▼
DefinitionListenService.findMatchingListenTasks()
    │
    ├── For each matching filter:
    │     extractCorrelationValues(filter, eventData)
    │     → Returns Map<String, String> from correlate.from expressions
    │     → Serialized to JSON: {"orderId": "123"}
    │
    ▼
MatchingListenTask(correlationValuesJson = "{"orderId":"123"}")
    │
    ▼
ListenerQueryKey(correlationValuesJson = "{"orderId":"123"}")
    │
    ▼
SQL WHERE condition (in batch INSERT/UPDATE):
    (l.correlation_values IS NULL OR l.correlation_values = ?)
```

**ListenerQueryKey SQL generation:**

```kotlin
data class ListenerQueryKey(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val correlationValuesJson: String?,
    val filterIndex: Int? = null
) {
    /**
     * Builds SQL WHERE condition for this key, including correlation check.
     */
    fun toSqlCondition(tableAlias: String = ""): String {
        val prefix = if (tableAlias.isNotEmpty()) "$tableAlias." else ""
        return if (correlationValuesJson == null) {
            // No correlation from event - match only by workflow identity
            "(${prefix}workflow_namespace = ? AND ${prefix}workflow_name = ? AND ${prefix}workflow_version = ? AND ${prefix}workflow_position = ?)"
        } else {
            // Correlation from event - match if listener has no baseline OR event matches baseline
            "(${prefix}workflow_namespace = ? AND ${prefix}workflow_name = ? AND ${prefix}workflow_version = ? AND ${prefix}workflow_position = ? AND (${prefix}correlation_values IS NULL OR ${prefix}correlation_values = ?))"
        }
    }

    /**
     * Binds parameters to PreparedStatement starting at the given index.
     */
    fun bindParameters(stmt: PreparedStatement, startIndex: Int): Int {
        var idx = startIndex
        stmt.setString(idx++, workflowInfo.namespace.toString())
        stmt.setString(idx++, workflowInfo.name.toString())
        stmt.setString(idx++, workflowInfo.version.toString())
        stmt.setString(idx++, position.toString())
        if (correlationValuesJson != null) {
            stmt.setString(idx++, correlationValuesJson)
        }
        return idx
    }

    companion object {
        /**
         * Builds OR-combined WHERE clause for multiple keys.
         * Used in batch INSERT/UPDATE operations.
         */
        fun buildWhereClause(keys: List<ListenerQueryKey>, tableAlias: String = ""): String =
            keys.joinToString(" OR ") { it.toSqlCondition(tableAlias) }

        /**
         * Binds all keys' parameters to PreparedStatement.
         */
        fun bindAllParameters(keys: List<ListenerQueryKey>, stmt: PreparedStatement, startIndex: Int): Int {
            var idx = startIndex
            for (key in keys) {
                idx = key.bindParameters(stmt, idx)
            }
            return idx
        }
    }
}
```

**Note**: The `whereClause` used in batch INSERT queries (e.g., `batchInsertForOneAny`, `batchInsertForAccumulating`) is built from `ListenerQueryKey.buildWhereClause(keys, "l")`, which automatically includes correlation matching when `correlationValuesJson` is present.

**Batch INSERT with correlation** (used in `batchInsertForOneAny` and `batchInsertForAccumulating`):

```sql
INSERT INTO lemline_listener_events (...)
SELECT ...
FROM lemline_listeners l
WHERE l.outbox_completed_at IS NULL
  AND l.strategy IN ('ONE', 'ANY')
  AND NOT EXISTS (SELECT 1 FROM lemline_listener_events e WHERE e.listener_id = l.id)
  -- Correlation: match if listener has no baseline OR event matches baseline
  AND (l.correlation_values IS NULL OR l.correlation_values = ?)
  AND (workflow_namespace = ? AND workflow_name = ? ...)
```

**Setting correlation baseline** (when listener is created):

The `correlation_values` column is set when the listener is created (in `WorkflowEventHandler.handleListenStarted()`):
- If workflow provides correlation context: Set `correlation_values` to those values
- If no correlation context: Set `correlation_values = NULL` (first event sets baseline)

**Note**: The current implementation uses simple JSON string comparison for correlation matching. This requires correlation values to be serialized with sorted keys for consistent comparison.

---

### ANY_UNTIL_EXPR (expression-based until)

Events accumulate until a JQ expression evaluates to `true` against the collected array.

**Key insight**: The until expression must be evaluated **after each event is processed** (not just once when checking completion). This ensures we stop accumulating as soon as the condition is met.

**When to evaluate:**
1. After event INSERT (if no foreach)
2. After foreach.do completes (if has foreach)

**Flow:**

```
Event arrives → INSERT into listener_events
     │
     ├── No foreach: evaluate until immediately
     │                │
     │                ├── until = true → mark listener ready
     │                └── until = false → continue (event stays, wait for more)
     │
     └── Has foreach: wait for foreach.do to complete
                      │
                      └── After completion: evaluate until
                                           │
                                           ├── until = true → mark listener ready
                                           └── until = false → trigger next event
```

**Implementation: Two evaluation points**

**Point 1: After event INSERT (no foreach)**

In `CloudEventHandler`, after batch INSERT for accumulating strategies, evaluate until expressions for listeners WITHOUT foreach:

```kotlin
/**
 * After batch INSERT for accumulating strategies, evaluate until expressions
 * for listeners WITHOUT foreach (immediate evaluation).
 */
private suspend fun evaluateUntilAfterInsert(
    keys: List<ListenerQueryKey>,
    eventJson: String
) {
    val whereClause = ListenerQueryKey.buildWhereClause(keys, "l")
    val jsonAgg = databaseManager.jsonArrayAgg("e.event")

    // Find ANY_UNTIL_EXPR listeners that just received an event and have no foreach
    val sql = """
        SELECT l.*, $jsonAgg as events_json
        FROM $LISTENER_TABLE l
        JOIN $LISTENER_EVENT_TABLE e ON e.listener_id = l.$ID_COLUMN
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.ready_at IS NULL
          AND l.strategy = 'ANY_UNTIL_EXPR'
          AND l.has_foreach = FALSE
          AND ($whereClause)
        GROUP BY l.$ID_COLUMN
        FOR UPDATE OF l SKIP LOCKED
    """.trimIndent()

    val listenersToEvaluate = databaseManager.withConnection { conn ->
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            ListenerQueryKey.bindAllParameters(keys, stmt, idx)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val listener = listenerRepository.createModel(rs)
                        val eventsJson = rs.getString("events_json")
                        val events = parseJsonArray(eventsJson)
                        add(listener to events)
                    }
                }
            }
        }
    }

    // Evaluate until expression for each listener
    for ((listener, events) in listenersToEvaluate) {
        val eventsArray = events.map { Json.parseToJsonElement(it) }
        val result = expressionEvaluator.evaluateBoolean(
            expression = listener.untilExpression!!,
            input = JsonArray(eventsArray)
        )

        if (result) {
            listenerRepository.markReady(listener.id)
            logger.debug { "Listener ${listener.id} until condition satisfied (no foreach)" }
        }
    }
}
```

**Call site** in `CloudEventHandler.insertForAccumulating()`:

```kotlin
private suspend fun insertForAccumulating(matches: List<DefinitionMatch>, cloudEvent: CloudEvent) {
    val keys = matches.map { it.toQueryKey() }
    val eventJson = cloudEvent.toJson()
    val filterIndex = matches.first().filterIndex ?: 0

    // Insert the event
    listenerEventRepository.batchInsertForAccumulating(keys, eventJson, filterIndex)

    // For ANY_UNTIL_EXPR without foreach: evaluate until immediately
    evaluateUntilAfterInsert(keys, eventJson)
}
```

**Point 2: After foreach.do completes**

In `WorkflowEventHandler.handleListenForEachCompleted()`:

```kotlin
private suspend fun handleListenForEachCompleted(message: InstanceMessage<...>) {
    // ... mark event completed, trigger next event ...

    // If listener has until expression, evaluate it now
    val listener = listenerRepository.findById(...)
    if (listener.strategy == ListenerStrategy.ANY_UNTIL_EXPR) {
        evaluateUntilAndMarkReadyIfSatisfied(listener)
    }
}

private suspend fun evaluateUntilAndMarkReadyIfSatisfied(listener: ListenerModel) {
    // Get all completed events for this listener
    val events = listenerEventRepository.getCompletedEvents(listener.id)

    // Parse events into JsonElement array
    val eventsArray = events.map { Json.parseToJsonElement(it) }

    // Evaluate JQ expression against the array
    val result = expressionEvaluator.evaluateBoolean(
        expression = listener.untilExpression!!,
        input = JsonArray(eventsArray)
    )

    if (result) {
        // Stop accumulating - mark listener ready
        listenerRepository.markReady(listener.id)
        logger.debug { "Listener ${listener.id} until condition satisfied after ${events.size} events" }
    }
    // else: continue accumulating, next event will be processed
}
```

**Repository method** to get completed events:

```kotlin
/**
 * Get all completed events for a listener (for until expression evaluation).
 */
suspend fun getCompletedEvents(
    listenerId: IDV7,
    connection: Connection? = null
): List<String> = withConnection(connection) { conn ->
    conn.prepareStatement(getCompletedEventsSQL).use { stmt ->
        setIDV7(stmt, 1, listenerId)
        stmt.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(rs.getString("event"))
                }
            }
        }
    }
}

private val getCompletedEventsSQL by lazy {
    """
        SELECT event FROM $tableName
        WHERE listener_id = ?
          AND $OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
        ORDER BY sequence
    """.trimIndent()
}
```

**Fallback: Polling in ListenerCompletionOutbox**

As a safety net, the outbox also evaluates until expressions periodically:

```kotlin
override suspend fun doWork() {
    // Step 1: Evaluate until expressions (catches any missed evaluations)
    evaluateUntilExpressions()

    // Step 2: Batch mark simple listeners as ready
    outboxRepository.batchMarkReady()

    // Step 3: Process ready listeners
    super.doWork()
}

private suspend fun evaluateUntilExpressions() {
    val listeners = listenerRepository.findListenersForUntilEvaluation(limit = 100)

    for ((listener, events) in listeners) {
        val eventsArray = events.map { Json.parseToJsonElement(it) }
        val result = expressionEvaluator.evaluateBoolean(
            expression = listener.untilExpression!!,
            input = JsonArray(eventsArray)
        )

        if (result) {
            listenerRepository.markReady(listener.id)
        }
    }
}
```

**Repository method** for polling (using correct columns):

```kotlin
/**
 * Find ANY_UNTIL_EXPR listeners with at least one completed event
 * that haven't been marked ready yet.
 */
suspend fun findListenersForUntilEvaluation(
    limit: Int,
    connection: Connection? = null
): List<Pair<ListenerModel, List<String>>> {
    val jsonAgg = databaseManager.jsonArrayAgg("e.event")

    val sql = """
        SELECT l.*, $jsonAgg as events_json
        FROM $LISTENER_TABLE l
        JOIN $LISTENER_EVENT_TABLE e ON e.listener_id = l.$ID_COLUMN
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.ready_at IS NULL
          AND l.strategy = 'ANY_UNTIL_EXPR'
          -- Only include completed events
          AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NOT NULL
          -- No pending events (all processed)
          AND NOT EXISTS (
              SELECT 1 FROM $LISTENER_EVENT_TABLE e2
              WHERE e2.listener_id = l.$ID_COLUMN
                AND e2.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          )
        GROUP BY l.$ID_COLUMN
        LIMIT ?
        FOR UPDATE OF l SKIP LOCKED
    """.trimIndent()

    return withConnection(connection) { conn ->
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val listener = createModel(rs)
                        val eventsJson = rs.getString("events_json")
                        val events = parseJsonArray(eventsJson)
                        add(listener to events)
                    }
                }
            }
        }
    }
}
```

---

### ANY_UNTIL_EVENT (termination event)

Events accumulate until a specific termination event arrives.

**CloudEventHandler integration**:

```kotlin
suspend fun handleCloudEvent(cloudEvent: CloudEvent) {
    // Step 1: Find matching definitions from cache
    val matches = findMatchingDefinitions(cloudEvent)

    // Step 2: Separate normal events from termination events
    val (normalMatches, terminationMatches) = matches.partition { !it.isTerminationEvent }

    // Step 3: Process normal events (accumulate)
    if (normalMatches.isNotEmpty()) {
        val (oneAnyMatches, accumulatingMatches) = normalMatches.partition {
            it.strategy in listOf(ListenerStrategy.ONE, ListenerStrategy.ANY)
        }

        coroutineScope {
            if (oneAnyMatches.isNotEmpty()) {
                launch { insertForOneAny(oneAnyMatches, cloudEvent) }
            }
            if (accumulatingMatches.isNotEmpty()) {
                launch { insertForAccumulating(accumulatingMatches, cloudEvent) }
            }
        }
    }

    // Step 4: Process termination events (mark listeners ready)
    if (terminationMatches.isNotEmpty()) {
        markListenersReadyByTermination(terminationMatches, cloudEvent)
    }
}

private suspend fun markListenersReadyByTermination(
    matches: List<DefinitionMatch>,
    cloudEvent: CloudEvent
) {
    val keys = matches.map { it.toQueryKey() }
    listenerRepository.batchMarkReadyByTermination(keys)
}
```

**Repository method** for batch marking termination:

```kotlin
/**
 * Batch mark ANY_UNTIL_EVENT listeners as ready when termination event arrives.
 *
 * The termination event is NOT added to listener_events - it just triggers completion.
 */
suspend fun batchMarkReadyByTermination(
    keys: List<ListenerQueryKey>,
    connection: Connection? = null
): Int {
    if (keys.isEmpty()) return 0

    val whereClause = ListenerQueryKey.buildWhereClause(keys, "l")

    val sql = """
        UPDATE $LISTENER_TABLE l
        SET ready_at = CURRENT_TIMESTAMP,
            $OUTBOX_DELAYED_UNTIL_COLUMN = CURRENT_TIMESTAMP,
            $UPDATED_AT_COLUMN = CURRENT_TIMESTAMP
        WHERE l.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
          AND l.ready_at IS NULL
          AND l.strategy = 'ANY_UNTIL_EVENT'
          -- All accumulated events must be completed (foreach done or no foreach)
          AND NOT EXISTS (
              SELECT 1 FROM $LISTENER_EVENT_TABLE e
              WHERE e.listener_id = l.$ID_COLUMN
                AND e.$OUTBOX_COMPLETED_AT_COLUMN IS NULL
                AND e.$OUTBOX_FAILED_AT_COLUMN IS NULL
          )
          AND ($whereClause)
    """.trimIndent()

    return withConnection(connection) { conn ->
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            ListenerQueryKey.bindAllParameters(keys, stmt, idx)
            stmt.executeUpdate()
        }
    }
}
```

**Flow diagram** (no foreach):

```
Timeline for ANY_UNTIL_EVENT without foreach:

t=0   listen starts (strategy=ANY_UNTIL_EVENT, has_foreach=FALSE)
t=2   temperature event → INSERT (seq=1, outbox_completed_at=NOW)  -- immediate "skip"
t=5   bpm event → INSERT (seq=2, outbox_completed_at=NOW)
t=7   temperature event → INSERT (seq=3, outbox_completed_at=NOW)
t=10  patient.checked-out (termination) → batchMarkReadyByTermination()
      → Listener marked ready_at=NOW()
      → ListenerCompletionOutbox picks up, aggregates 3 events, emits resume

Output: [temp@t=2, bpm@t=5, temp@t=7]  (termination event NOT included)
```

**Flow diagram** (with foreach):

```
Timeline for ANY_UNTIL_EVENT with foreach:

t=0   listen starts (strategy=ANY_UNTIL_EVENT, has_foreach=TRUE)
t=2   temperature event → INSERT (seq=1, delayed_until=NOW, completed_at=NULL)
      → ListenerForeachOutbox claims and runs foreach.do
t=3   foreach.do completes → completed_at=NOW
t=5   bpm event → INSERT (seq=2, delayed_until=NOW, completed_at=NULL)
      → foreach.do runs for bpm
t=6   foreach.do completes → completed_at=NOW
t=10  patient.checked-out (termination) → batchMarkReadyByTermination()
      → All events completed? Yes → ready_at=NOW
      → ListenerCompletionOutbox aggregates foreach_outputs, emits resume

Output: [output_from_temp, output_from_bpm]  (termination event NOT included)
```

---

### Empty `any: []` (Wildcard)

An empty `any: []` means "listen to any event regardless of type".

**Implementation**: The definition cache maps empty filters to a wildcard entry:

```kotlin
// In DefinitionListenService or cache
data class ListenerDefinition(
    val workflowInfo: WorkflowInfo,
    val position: NodePosition,
    val strategy: ListenerStrategy,
    val filters: List<EventFilter>,
    val isWildcard: Boolean  // TRUE when any: []
)

// Wildcard listeners match ALL events
fun findMatchingDefinitions(cloudEvent: CloudEvent): List<DefinitionMatch> {
    val matches = mutableListOf<DefinitionMatch>()

    // Check specific type matches
    val typeMatches = definitionCache.findByEventType(cloudEvent.type)
    matches.addAll(typeMatches.filter { it.matchesEvent(cloudEvent) })

    // Add wildcard matches (any: [])
    val wildcardMatches = definitionCache.findWildcardListeners()
    matches.addAll(wildcardMatches.map { DefinitionMatch(it, filterIndex = null) })

    return matches
}
```

The batch INSERT logic remains unchanged - wildcards are just listeners without type filtering.

---

### Timeout Handling

Keep `ListenerTimeoutOutbox` unchanged - it handles timeout independently by:
1. Finding listeners where `timeout_at < NOW()`
2. Marking them as failed (not ready)
3. Emitting a timeout error to the workflow

## Migration Plan

> **Note:** Since we're not in production, we can update existing migration files directly
> (`V8__Create_lemline_listeners_tables.sql`) rather than creating new migrations.

### Phase 1: Database Migration

1. Update `V8__Create_lemline_listeners_tables.sql` with simplified schema:
   - Add `ready_at` column to `lemline_listeners`
   - Remove `foreach_current_index`, `listener_completed` (replaced by event status)
   - Add `sequence`, `foreach_status`, `foreach_claimed_at` to `lemline_listener_events`
   - Update indexes for ADR-0013 pattern
2. Test migrations on PostgreSQL, MySQL, H2

### Phase 2: Repository Changes

1. Simplify `ListenerRepository`:
   - Remove: `markReadyForCompletion*`, `markTerminated*`, `markAllCompleted*`, etc.
   - Add: `batchMarkReady()`, `findReadyWithOutput()`
   - Keep: `findByWorkflowIdAndPosition()`, `findByIds()`

2. Simplify `ListenerEventRepository`:
   - Remove: `bulkInsertEventsForKeys`, `setForeachScheduled*`, `triggerFirstEvent*`, etc.
   - Add: `batchInsertForOneAny()`, `batchInsertForAccumulating()`
   - Add: `claimNextForForeach()`, `markForeachCompleted()` (ADR-0013 pattern)
   - Add: `releaseStaleClaimsForForeach()`, `getNextSequence()`

### Phase 3: Outbox Changes

1. Rename `ListenerOutbox` → `ListenerCompletionOutbox`
2. Rename `ListenerEventOutbox` → `ListenerForeachOutbox`
3. Simplify both to use new repository methods
4. Update configuration keys

### Phase 4: Handler Changes

1. Simplify `CloudEventHandler`:
   - Remove strategy-specific code paths
   - Use two batch INSERT methods

2. Simplify `WorkflowEventHandler.handleListenForEachCompleted`:
   - Remove strategy branching
   - Just mark event complete and clear processing flag

### Phase 5: Testing

1. Update existing tests to match new structure
2. Add tests for batch operations
3. Test all strategy combinations
4. Test concurrent event arrival
5. Test foreach sequential processing

## Summary

| Aspect                    | Before                              | After                                    |
|---------------------------|-------------------------------------|------------------------------------------|
| Code paths                | 4 in CloudEventHandler              | 2 batch INSERT methods                   |
| State tracking            | Multiple flags                      | Standard outbox columns                  |
| Repository methods        | 25+                                 | ~10 focused methods                      |
| Completion logic          | Scattered across handlers           | Centralized in ListenerCompletionOutbox  |
| Flow                      | Hard to trace                       | Linear: INSERT → foreach → complete      |
| Sequential foreach        | Custom `foreach_processing` flag    | FIFO via `outbox_delayed_until`          |
| Retry/error handling      | Custom implementation               | Inherited from AbstractOutbox            |

## Standard Outbox Pattern Compliance

Both `lemline_listeners` and `lemline_listener_events` follow the standard outbox pattern:

| Outbox Column             | Purpose                                                       |
|---------------------------|---------------------------------------------------------------|
| `outbox_scheduled_for`    | When the event was scheduled (observability)                  |
| `outbox_delayed_until`    | When to process (NULL = waiting, NOT NULL = ready)            |
| `outbox_attempt_count`    | Retry counter for exponential backoff                         |
| `outbox_error_*`          | Error tracking (class, message, stacktrace)                   |
| `outbox_completed_at`     | When processing completed successfully                        |
| `outbox_failed_at`        | When processing permanently failed                            |
| `cleanup_after`           | When to delete the row                                        |

## FIFO Enforcement (listener_events)

Sequential foreach processing is enforced via `outbox_delayed_until`:

| State                     | outbox_delayed_until | outbox_completed_at |
|---------------------------|----------------------|---------------------|
| Waiting for FIFO turn     | NULL                 | NULL                |
| Ready for processing      | NOT NULL, <= NOW()   | NULL                |
| Processing (claimed)      | NOT NULL, <= NOW()   | NULL (in-flight)    |
| Completed                 | (any)                | NOT NULL            |
| Failed                    | (any)                | NULL, failed_at SET |

**FIFO flow:**
1. First event inserted with `outbox_delayed_until = NOW()` (immediately ready)
2. Subsequent events inserted with `outbox_delayed_until = NULL` (waiting)
3. When event completes, `triggerNextEvent()` sets next event's `delayed_until = NOW()`
4. Standard outbox polling picks up the next ready event

The key simplification: **all events go through `listener_events`**, and standard outbox columns provide state tracking, retry logic, and cleanup - all inherited from `AbstractOutbox`.
