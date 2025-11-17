# Repository Implementation Guide

## Overview

Lemline uses a layered repository pattern with native SQL for database operations. This guide covers creating and using repositories across PostgreSQL, MySQL, and H2.

## Core Concepts

### 1. Database Support

All repositories must work across three databases:
- **PostgreSQL** (production)
- **MySQL** (production alternative)
- **H2** (testing)

Use database-agnostic SQL where possible. Database-specific migrations go in:
- `src/main/resources/db/migration/postgresql/`
- `src/main/resources/db/migration/mysql/`
- `src/main/resources/db/migration/h2/`

### 2. ID Management

All entities use **IDV7** (UUID v7) as primary keys:
- Time-ordered UUIDs (sortable by creation time)
- Globally unique
- Generated in application code, not database

```kotlin
import com.lemline.common.values.IDV7

val id = IDV7.random()
```

## Repository Base Classes

### Repository<T>

Base class for all repositories. Provides CRUD operations.

```kotlin
@ApplicationScoped
class MyRepository : Repository<MyModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = "my_table"

    override val prepareStatementMap = mapOf(
        "column_name" to { stmt: PreparedStatement, entity: MyModel, idx: Int ->
            stmt.setString(idx, entity.value)
        }
    )

    override fun createModel(rs: ResultSet) = MyModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        value = rs.getString("column_name")
    )
}
```

**Key Methods:**
- `insert(entity: T, connection: Connection? = null): Int`
- `update(entity: T, connection: Connection? = null): Int`
- `delete(entity: T, connection: Connection? = null): Int`
- `findById(id: IDV7, connection: Connection? = null): T?`

### WithInstanceRepository<T>

For entities storing workflow instance context. Extends `Repository<T>`.

```kotlin
@ApplicationScoped
class ForkRepository : WithInstanceRepository<ForkModel>() {

    override val tableName = "lemline_forks"

    override val prepareStatementMap by lazy {
        super.prepareStatementMap + mapOf(
            "fork_position" to { stmt, entity, idx ->
                stmt.setString(idx, entity.forkPosition)
            }
        )
    }

    override fun createModel(rs: ResultSet) = ForkModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage()!!,
        forkPosition = rs.getString("fork_position"),
        compete = rs.getBoolean("compete"),
        branchCount = rs.getInt("branch_count")
    )
}
```

**Inherited Columns:**
- `id` (primary key)
- `workflow_id`, `workflow_namespace`, `workflow_name`, `workflow_version`
- `workflow_position`, `workflow_state`
- `parent_id`
- `created_at`, `updated_at`

**Inherited Methods:**
- All from `Repository<T>`
- `getInstanceMessage(rs: ResultSet): InstanceMessage?`

### OutboxRepository<T>

For outbox pattern implementation (retries, waits, schedules).

Extends `WithInstanceRepository<T>` and adds:
- `outbox_status` (PENDING, SENT)
- `outbox_scheduled_for`
- `outbox_delayed_until`
- `outbox_attempt_count`
- `outbox_error_class`, `outbox_error_message`, `outbox_error_stacktrace`

```kotlin
@ApplicationScoped
class RetryRepository : OutboxRepository<RetryOutboxModel>() {
    override val tableName = "lemline_retries"
    override val maxAttempts = 10
}
```

## Model Types

### InstanceModel

Sealed interface for models containing workflow instance data.

```kotlin
@Serializable
@SerialName("fork")
data class ForkModel(
    @SerialName("id")
    override val id: IDV7,

    @SerialName("i")
    val instanceMessage: InstanceMessage,

    @SerialName("fp")
    val forkPosition: String,

    @SerialName("c")
    val compete: Boolean,

    @SerialName("bc")
    val branchCount: Int
) : InstanceModel {
    override val workflowInfo get() = instanceMessage.workflowInfo
    override val workflowState get() = instanceMessage.workflowState
    override val parentId get() = instanceMessage.parentId
}
```

### OutboxModel

For outbox pattern entities.

```kotlin
data class RetryOutboxModel(
    override val id: IDV7,
    override val instanceMessage: InstanceMessage,
    override val outboxStatus: OutBoxStatus,
    override val outboxScheduledFor: Instant?,
    override val outboxDelayedUntil: Instant?,
    override val outboxAttemptCount: Int,
    override val outboxErrorClass: String?,
    override val outboxErrorMessage: String?,
    override val outboxErrorStacktrace: String?
) : OutboxModel
```

## Transaction & Connection Handling

### Connection Parameter Pattern

All suspend methods that use `withConnection` **must** accept a `connection: Connection? = null` parameter:

```kotlin
// ✅ CORRECT
suspend fun findByWorkflowIdAndPosition(
    workflowId: WorkflowId,
    position: NodePosition,
    connection: Connection? = null
): ForkModel? = withConnection(connection) { conn ->
    // implementation
}

suspend fun getBranches(
    forkId: IDV7,
    connection: Connection? = null
): List<BranchModel> = withConnection(connection) { conn ->
    // implementation
}

// ❌ INCORRECT - missing connection parameter
suspend fun findById(id: IDV7): Model? = withConnection(null) { conn ->
    // implementation
}
```

### When to Use What

**withConnection(connection)**
- Read operations (SELECT)
- Single writes that can participate in external transactions
- Accepts `connection: Connection? = null` parameter

```kotlin
suspend fun findById(id: IDV7, connection: Connection? = null): T? =
    withConnection(connection) { conn ->
        // SELECT query
    }
```

**withTransaction**
- Multiple related writes requiring atomicity
- Never accepts connection parameter (always creates new transaction)

```kotlin
suspend fun insertForkWithBranches(
    fork: ForkModel,
    branches: List<ForkBranchModel>
) = withTransaction { conn ->
    insert(fork, conn)
    // batch insert branches
}
```

### Calling Repositories Within Transactions

```kotlin
// Use existing transaction
failureRepository.withTransaction { conn ->
    parentRepository.insert(parent, conn)
    scheduleRepository.insert(schedule, conn)
    instanceEmitter.send(message)
}
```

## Helper Methods

### IDV7 Handling

```kotlin
// Set IDV7 in PreparedStatement
setIDV7(stmt, index, idv7)

// Get IDV7 from ResultSet
val id = getIDV7(rs, "id_column")
```

### Instance Message Handling

```kotlin
// Get instance message from ResultSet (WithInstanceRepository)
val instanceMessage = rs.getInstanceMessage()

// Serialize instance message for storage
stmt.setString(index, LemlineJson.encodeToString(instanceMessage))
```

## Migration Patterns

### Creating Tables

PostgreSQL example:
```sql
CREATE TABLE lemline_forks (
    -- Primary key
    id UUID NOT NULL PRIMARY KEY,

    -- Workflow instance columns (WithInstanceRepository)
    workflow_id UUID,
    workflow_namespace VARCHAR(255),
    workflow_name VARCHAR(255),
    workflow_version VARCHAR(255),
    workflow_position TEXT,
    workflow_state TEXT,
    parent_id UUID,

    -- Entity-specific columns
    fork_position TEXT NOT NULL,
    compete BOOLEAN NOT NULL,
    branch_count INT NOT NULL,

    -- Outbox columns (if OutboxRepository)
    outbox_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    outbox_scheduled_for TIMESTAMP,
    outbox_delayed_until TIMESTAMP,
    outbox_attempt_count INT NOT NULL DEFAULT 0,
    outbox_error_class VARCHAR(500),
    outbox_error_message TEXT,
    outbox_error_stacktrace TEXT,

    -- Standard timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Indexes
CREATE INDEX idx_forks_workflow_id ON lemline_forks(workflow_id);
CREATE INDEX idx_forks_position ON lemline_forks(fork_position);
```

MySQL differences:
- Use `DATETIME` instead of `TIMESTAMP`
- Use `VARCHAR(36)` instead of `UUID`
- Adjust syntax for AUTO_INCREMENT if needed

H2 differences:
- Generally compatible with PostgreSQL syntax
- Use for testing only

## Best Practices

1. **Always Use IDV7**: Never use database auto-increment for primary keys
2. **Connection Parameters**: All `withConnection` methods must accept `connection: Connection? = null`
3. **Transactions**: Use `withTransaction` for multi-statement atomicity
4. **Database Agnostic SQL**: Write SQL that works across all three databases
5. **PrepareStatementMap**: Define all column mappings explicitly
6. **Lazy Initialization**: Use `by lazy` for SQL query strings
7. **Testing**: Test with all three database profiles (PostgreSQL, MySQL, H2)
8. **Serialization**: Use `LemlineJson` for JSON encoding/decoding
9. **Timestamps**: Use kotlinx.datetime.Instant, not java.time.Instant
10. **Nullable Connections**: Always provide `connection: Connection? = null` for flexibility

## Common Patterns

### Custom Find Method

```kotlin
suspend fun findByWorkflowIdAndPosition(
    workflowId: WorkflowId,
    position: NodePosition,
    connection: Connection? = null
): ForkModel? = withConnection(connection) { conn ->
    conn.prepareStatement(findByWorkflowIdAndPositionSql).use { stmt ->
        setIDV7(stmt, 1, workflowId.value)
        stmt.setString(2, position.toString())
        stmt.executeQuery().use { rs ->
            if (rs.next()) createModel(rs) else null
        }
    }
}

private val findByWorkflowIdAndPositionSql by lazy {
    "SELECT * FROM $tableName WHERE workflow_id = ? AND fork_position = ? LIMIT 1"
}
```

### Batch Operations

```kotlin
suspend fun insertBatch(
    models: List<MyModel>,
    connection: Connection? = null
) = withConnection(connection) { conn ->
    conn.prepareStatement(insertSql).use { stmt ->
        models.forEach { model ->
            setIDV7(stmt, 1, model.id)
            stmt.setString(2, model.value)
            stmt.addBatch()
        }
        stmt.executeBatch()
    }
}
```

### Pessimistic Locking

```kotlin
suspend fun updateWithLock(id: IDV7) = withTransaction { conn ->
    // Lock row
    val entity = conn.prepareStatement(
        "SELECT * FROM $tableName WHERE id = ? FOR UPDATE"
    ).use { stmt ->
        setIDV7(stmt, 1, id)
        stmt.executeQuery().use { rs ->
            if (rs.next()) createModel(rs) else null
        }
    } ?: return@withTransaction

    // Update locked row
    update(entity.copy(value = "new"), conn)
}
```

## Testing

Create database-specific test classes:

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class PostgresForkRepositoryTest : ForkRepositoryTest()

@QuarkusTest
@TestProfile(MySQLProfile::class)
class MySQLForkRepositoryTest : ForkRepositoryTest()

@QuarkusTest
@TestProfile(InMemoryProfile::class)
class H2ForkRepositoryTest : ForkRepositoryTest()
```

Base test class:
```kotlin
abstract class ForkRepositoryTest {
    @Inject
    protected lateinit var repository: ForkRepository

    @Test
    fun `should insert and retrieve`() = runTest {
        val model = createTestModel()
        repository.insert(model)

        val retrieved = repository.findById(model.id)
        retrieved shouldBe model
    }
}
```

## Summary

- Use appropriate base class: `Repository`, `WithInstanceRepository`, or `OutboxRepository`
- All primary keys use `IDV7`
- All `withConnection` methods accept `connection: Connection? = null`
- Use `withTransaction` for multi-statement atomicity (no connection parameter)
- Test across PostgreSQL, MySQL, and H2
- Follow database-agnostic SQL patterns
