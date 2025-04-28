# [ADR-0004] Database Storage Strategy

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires persistent storage for workflow definitions, instances, and related data. We needed to decide on a database storage strategy that would be efficient, maintainable, and aligned with the project's requirements.

## Decision

We have decided to implement a database storage strategy based on native SQL queries with the following characteristics:

1. **Native SQL Approach**: We use native SQL queries instead of an ORM solution because:
   - We need to support SKIP LOCKED for parallel processing safety, which is not supported by Hibernate
   - We require runtime database type selection (PostgreSQL, MySQL, H2) which is not easily achievable with Hibernate's entity manager

2. **Multi-Database Support**: The system supports multiple database backends:
   - PostgreSQL 
   - MySQL 
   - H2 
   Each database type has its own optimized SQL queries and connection management.

3. **Repository Pattern**: We implement the Repository pattern to encapsulate the logic for accessing and manipulating data, with:
   - Base `Repository` class providing common functionality
   - Specialized repositories for different entity types
   - Database-specific query optimizations
   - Transaction management through `@Transactional` annotations

4. **Connection Pooling**: We use Agroal for efficient connection pooling to manage database connections and resources.

5. **Schema Migration**: We use FlyWay for database schema migrations to ensure consistent schema changes across environments.

6. **Query Optimization**: We implement best practices for query optimization:
   - Database-specific UPSERT operations
   - Batch processing for bulk operations
   - Proper indexing through FlyWay migrations
   - SKIP LOCKED for parallel processing safety

7. **Monitoring and Profiling**: We continuously monitor and profile database queries to identify and address performance bottlenecks.

## Consequences

### Positive

- **Performance**: Native SQL queries provide optimal performance and control
- **Flexibility**: Support for multiple database backends with database-specific optimizations
- **Maintainability**: The Repository pattern encapsulates data access logic, making it easier to maintain and test
- **Parallel Processing**: SKIP LOCKED support enables safe parallel processing
- **Transaction Control**: Fine-grained control over transaction boundaries
- **Schema Evolution**: FlyWay migrations ensure consistent schema changes

### Negative

- **Boilerplate Code**: More manual mapping between database results and domain objects
- **SQL Maintenance**: Need to maintain database-specific SQL queries
- **Learning Curve**: Developers need to understand SQL and transaction management
- **Complexity**: Managing multiple database backends adds complexity to the codebase

## Alternatives Considered

### Hibernate with Panache

Using Hibernate with Panache was considered but rejected because:
- It doesn't support SKIP LOCKED, which is essential for our parallel processing requirements
- The entity manager cannot be easily configured at runtime for different database types
- It would limit our ability to optimize queries for specific database types
- The transaction management would be less flexible

### NoSQL Database

Using a NoSQL database like MongoDB or Cassandra was considered but rejected because:
- The data model has clear relational aspects
- We need strong consistency guarantees for workflow state management
- The team has more experience with relational databases
- The ecosystem of tools for relational databases is more mature

## References

- [FlyWay Migration](https://flywaydb.org/)
- [Repository Pattern](https://martinfowler.com/eaaCatalog/repository.html)
- [Agroal Connection Pool](https://quarkus.io/guides/datasource)