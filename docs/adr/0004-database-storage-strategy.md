# [ADR-0004] Database Storage Strategy

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires persistent storage for workflow
definitions, instances, and related data. We needed to decide on a database storage strategy that would be efficient,
maintainable, and aligned with the project's requirements.

## Decision

We have decided to implement a database storage strategy based on native SQL queries with the following characteristics:

1. **Native SQL Approach**: We use native SQL queries instead of an ORM solution because:
    - We need fine-grained control over SQL queries for optimal performance
    - We require runtime database type selection (PostgreSQL, MySQL, H2) with database-specific optimizations
    - We want to ensure consistent behavior across different database backends

2. **Multi-Database Support**: The system supports multiple database backends:
    - PostgreSQL
    - MySQL
    - H2 (for in-memory testing and development)
   
   Each database type has its own optimized SQL queries, connection management, and migration scripts.

3. **Repository Pattern**: We implement the Repository pattern with a strongly-typed approach:
    - Abstract `Repository<T : UuidV7Entity>` class providing common CRUD operations
    - Type-safe binding methods for SQL parameters
    - Database-specific SQL generation (e.g., for UPSERT operations)
    - Support for both single operations and batch processing
    - Connection reuse for transaction management

4. **Outbox Pattern**: We implement the Outbox pattern for reliable asynchronous message processing:
    - `OutboxModel` as the base class for outbox messages
    - Scheduled processing with configurable batch sizes and intervals
    - Retry mechanism with exponential backoff
    - Cleanup of processed messages to prevent database bloat

5. **Connection Management**: We use Agroal for efficient connection pooling:
    - Datasource selection based on database type configuration
    - Proper connection handling with try-with-resources pattern
    - Support for both connection reuse and automatic connection management

6. **Schema Migration**: We use Flyway for database schema migrations with:
    - Database-specific migration scripts in separate folders
    - Automatic application of migrations at startup (configurable)
    - Migration validation to ensure schema consistency

7. **UUID v7 for IDs**: We use UUID v7 as the primary key generation strategy:
    - Time-ordered for efficient indexing
    - Globally unique without coordination
    - Better performance than UUID v4

## Step-by-Step Guide for Adding a New Database Type

To add support for a new database type, follow these steps:

### 1. Update Configuration Constants

Add the new database type constant in `LemlineConfigConstants.kt`:

```kotlin
const val DB_TYPE_NEW_DB = "newdb"
```

### 2. Create Migration Scripts

Create database-specific migration scripts in a new folder:

1. Create a folder for your migrations: `src/main/resources/db/migration/newdb/`
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

### 3. Configure Datasource in application.properties

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

### 4. Update DatabaseManager.kt

Modify `DatabaseManager.kt` to include the new database type:

1. Add the injection for the new datasource:
```kotlin
@Inject
@DataSource("newdb")
private lateinit var newdbDataSource: Instance<AgroalDataSource>
```

2. Add the injection for the new Flyway instance:
```kotlin
@Inject
@FlywayDataSource("newdb")
private lateinit var newdbFlyway: Instance<Flyway>
```

3. Update the datasource and flyway resolution logic:
```kotlin
val datasource: AgroalDataSource by lazy {
    // Existing code...
    
    when (dbType) {
        // Existing cases...
        
        DB_TYPE_NEW_DB -> {
            if (newdbDataSource.isResolvable) newdbDataSource.get()
            else throw IllegalStateException("NewDB datasource is not available")
        }
        
        else -> throw IllegalStateException("Unknown database type '$dbType'")
    }
}

val flyway: Flyway by lazy {
    // Existing code...
    
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

### 5. Update Repository.kt

Modify the `Repository` class to handle database-specific SQL syntax:

1. Update the quote function for database identifiers:
```kotlin
private fun q(id: String): String = when (databaseManager.dbType) {
    // Existing cases...
    DB_TYPE_NEW_DB -> "«$id»" // Use the appropriate quoting for your database
    else -> id
}
```

2. Update the insert SQL generation for the new database:
```kotlin
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

### 6. Testing

1. Create integration tests for the new database type:
   - Set up a test container for the database
   - Configure the test to use the new database type
   - Run the same test suite against the new database

2. Example test configuration:
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

### 7. Documentation

1. Update this ADR document to include the new database type
2. Add configuration examples and known limitations to the project documentation
3. Ensure the README includes instructions for using the new database type

## Consequences

### Positive

- **Performance**: Native SQL queries provide optimal performance and control
- **Flexibility**: Support for multiple database backends with database-specific optimizations
- **Maintainability**: The Repository pattern encapsulates data access logic, making it easier to maintain and test
- **Reliability**: The Outbox pattern ensures reliable message processing even during failures
- **Scalability**: Batch operations and connection pooling provide efficient resource utilization
- **Type Safety**: Strongly-typed repositories prevent SQL injection and type errors

### Negative

- **Boilerplate Code**: More manual mapping between database results and domain objects
- **SQL Maintenance**: Need to maintain database-specific SQL queries
- **Learning Curve**: Developers need to understand SQL and transaction management
- **Complexity**: Managing multiple database backends adds complexity to the codebase

## Alternatives Considered

### Hibernate with Panache

Using Hibernate with Panache was considered but rejected because:
- Does not support the "SKIP LOCKED ON UPDATE" requests

### NoSQL Database

Using a NoSQL database like MongoDB or Cassandra was considered but rejected because:
- The data model has clear relational aspects
- We need strong consistency guarantees for workflow state management
- SQL databases provide better tooling and wider familiarity among developers

## References

- [Repository Pattern](https://martinfowler.com/eaaCatalog/repository.html)
- [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [FlyWay Migration](https://flywaydb.org/)
- [Agroal Connection Pool](https://quarkus.io/guides/datasource)
- [UUID v7 Specification](https://datatracker.ietf.org/doc/html/draft-peabody-uuid-v7)
