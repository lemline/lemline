# Database Storage Strategy

## Overview

Lemline implements a flexible database storage strategy using native SQL queries to store workflow definitions, instances, and related data. This approach provides:

- Fine-grained control over SQL queries for optimal performance
- Runtime database type selection (PostgreSQL, MySQL, H2) with database-specific optimizations
- Consistent behavior across different database backends

## Implementation Details

### Repository Pattern

The core of the database implementation is the Repository pattern:

- Abstract `Repository<T : UuidV7Entity>` class providing common CRUD operations
- Type-safe binding methods for SQL parameters
- Database-specific SQL generation (e.g., for UPSERT operations)
- Support for both single operations and batch processing
- Connection reuse for transaction management

Each entity type has its own repository implementation that extends the base Repository class:

```kotlin
class DefinitionRepository : Repository<DefinitionModel>() {
    override val tableName = "lemline_definitions"
    override val columns = listOf("id", "name", "version", "definition")
    
    // Specialized query methods for definitions
    fun findByNameAndVersion(name: String, version: String): DefinitionModel? {
        // Implementation
    }
}
```

### Entity Models

Each database entity is represented as a model class that extends `UuidV7Entity`:

```kotlin
data class DefinitionModel(
    override val id: UUID = UuidV7.generate(),
    val name: String,
    val version: String,
    val definition: String
) : UuidV7Entity
```

The `UuidV7Entity` base class ensures:
- Time-ordered UUIDs for efficient indexing
- Globally unique identifiers without coordination
- Better performance than UUID v4

### Outbox Pattern

For reliable message processing, Lemline implements the Outbox pattern:

- `OutboxModel` as the base class for outbox messages
- Scheduled processing with configurable batch sizes and intervals
- Retry mechanism with exponential backoff
- Cleanup of processed messages to prevent database bloat

### Connection Management

Database connections are managed by the `DatabaseManager` class:

- Datasource selection based on database type configuration
- Proper connection handling with try-with-resources pattern
- Support for both connection reuse and automatic connection management

### Schema Migration

Database schema migrations are handled by Flyway:

- Database-specific migration scripts in separate folders
- Automatic application of migrations at startup (configurable)
- Migration validation to ensure schema consistency

## Supported Databases

Lemline currently supports:

1. **PostgreSQL** - Recommended for production
2. **MySQL** - Supported for compatibility
3. **H2** - For in-memory testing and development

## Configuration

Database configuration is defined in the `DatabaseConfig` interface in `LemlineConfiguration.kt`:

```yaml
lemline:
  database:
    type: postgresql  # Options: in-memory, postgresql, mysql
    migrate-at-start: true
    postgresql:
      host: localhost
      port: 5432
      username: lemline
      password: lemline
      name: lemline
```

## Adding Support for a New Database Type

### Step 1: Update Configuration Constants

Add the new database type constant in `LemlineConfigConstants.kt`:

```kotlin
const val DB_TYPE_NEW_DB = "newdb"
```

### Step 2: Create Migration Scripts

Create database-specific migration scripts:

1. Create a folder: `src/main/resources/db/migration/newdb/`
2. Create migration scripts following the Flyway naming convention (V1__Create_definitions_table.sql, etc.)
3. Implement the SQL for each table with syntax specific to your database

Example:
```sql
-- Create definitions table for NewDB
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    id         VARCHAR(36),
    name       VARCHAR(255) NOT NULL,
    version    VARCHAR(255) NOT NULL,
    definition TEXT         NOT NULL,
    CONSTRAINT pk_lemline_definitions_name_version PRIMARY KEY (name, version),
    CONSTRAINT uk_lemline_definitions_id UNIQUE (id)
);

-- Create an index for efficient querying on name
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_name ON lemline_definitions (name);
```

### Step 3: Configure Datasource

Add configuration for the new database in `application.properties`:

```properties
# NewDB configuration
quarkus.datasource.newdb.db-kind=newdb
quarkus.datasource.newdb.username=${NEWDB_USERNAME:username}
quarkus.datasource.newdb.password=${NEWDB_PASSWORD:password}
quarkus.datasource.newdb.jdbc.url=${NEWDB_URL:jdbc:newdb://localhost:5432/database}

# Flyway configuration for NewDB
quarkus.flyway.newdb.migrate-at-startup=false
quarkus.flyway.newdb.locations=db/migration/newdb
quarkus.flyway.newdb.baseline-on-migrate=true
quarkus.flyway.newdb.baseline-version=0
```

### Step 4: Update DatabaseManager.kt

Modify `DatabaseManager.kt` to include the new database type:

```kotlin
// Add the injection for the new datasource
@Inject
@DataSource("newdb")
private lateinit var newdbDataSource: Instance<AgroalDataSource>

// Add the injection for the new Flyway instance
@Inject
@FlywayDataSource("newdb")
private lateinit var newdbFlyway: Instance<Flyway>

// Update the datasource resolution logic
val datasource: AgroalDataSource by lazy {
    when (dbType) {
        // Existing cases...
        
        DB_TYPE_NEW_DB -> {
            if (newdbDataSource.isResolvable) newdbDataSource.get()
            else throw IllegalStateException("NewDB datasource is not available")
        }
        
        else -> throw IllegalStateException("Unknown database type '$dbType'")
    }
}

// Update the flyway resolution logic
val flyway: Flyway by lazy {
    when (dbType) {
        // Existing cases...
        
        DB_TYPE_NEW_DB -> {
            if (newdbFlyway.isResolvable) newdbFlyway.get()
            else throw IllegalStateException("NewDB flyway is not available")
        }
        
        else -> throw IllegalStateException("Unknown database type '$dbType'")
    }
}
```

### Step 5: Update Repository.kt

Modify the `Repository` class to handle database-specific SQL syntax:

```kotlin
// Update the quote function for database identifiers
private fun q(id: String): String = when (databaseManager.dbType) {
    // Existing cases...
    DB_TYPE_NEW_DB -> "«$id»" // Use the appropriate quoting for your database
    else -> id
}

// Update the insert SQL generation
private fun getInsertSql(): String {
    val colsCsv = columns.joinToString { q(it) }
    val valsCsv = columns.joinToString { "?" }

    return when (databaseManager.dbType) {
        // Existing cases...
        
        DB_TYPE_NEW_DB -> """
            INSERT INTO $tableName ($colsCsv)
            VALUES ($valsCsv)
            -- Add the appropriate ON DUPLICATE KEY syntax for your database
        """.trimIndent()
        
        else -> error("Unsupported database type '${databaseManager.dbType}'")
    }
}
```

### Step 6: Testing

Create integration tests for the new database type:

```kotlin
@QuarkusTest
@TestProfile(NewDbTestProfile::class)
class NewDbRepositoryTest {
    // Test cases
}

class NewDbTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            "lemline.database.type" to "newdb",
            // Other necessary configuration
        )
    }
}
```

## Performance Considerations

### Query Optimization

For optimal performance:

1. **Use indexed fields** for WHERE clauses
2. **Batch operations** for bulk inserts/updates
3. **Use prepared statements** (automatic with Repository pattern)
4. **Limit result sets** when fetching large amounts of data

### Connection Management

The `Repository` class provides connection management methods:

```kotlin
// For transaction control:
connection.use { conn ->
    // Multiple operations using the same connection
    repository.withConnection(conn) {
        repository.create(entity1)
        repository.create(entity2)
    }
}

// For simple operations:
repository.create(entity)
```

## Troubleshooting

### Common Issues

- **Migration errors**: Check that your SQL is compatible with the target database
- **Connection failures**: Verify database credentials and connection properties
- **Performance issues**: Check for missing indexes or inefficient queries

### Debugging Tips

- Enable SQL logging: `quarkus.hibernate-orm.log.sql=true`
- Use database-specific tools to analyze query performance
- Use the in-memory database for isolated testing 