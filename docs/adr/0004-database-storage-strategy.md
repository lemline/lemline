# [ADR-0004] Database Storage Strategy

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires persistent storage for workflow definitions, instances, and related data. We needed to decide on a database storage strategy that would be efficient, maintainable, and aligned with the project's requirements.

## Decision

We have decided to implement a database storage strategy based on Hibernate with Panache with the following characteristics:

1. **ORM Approach**: We use Hibernate with Panache as our Object-Relational Mapping (ORM) solution to map between our domain objects and the database.

2. **Multi-Database Support**: The system supports multiple database backends, with PostgreSQL as the default and MySQL as an alternative option.

3. **Repository Pattern**: We implement the Repository pattern to encapsulate the logic for accessing and manipulating data.

4. **Connection Pooling**: We use efficient connection pooling to manage database connections and resources.

5. **Schema Migration**: We use FlyWay for database schema migrations to ensure consistent schema changes across environments.

6. **Query Optimization**: We implement best practices for query optimization, including proper indexing, caching strategies, and batch processing.

7. **Monitoring and Profiling**: We continuously monitor and profile database queries to identify and address performance bottlenecks.

## Consequences

### Positive

- **Abstraction**: The ORM approach provides an abstraction layer that simplifies data access and manipulation.
- **Productivity**: Panache's active record pattern and repository abstractions improve developer productivity.
- **Flexibility**: Support for multiple database backends provides flexibility in deployment environments.
- **Maintainability**: The Repository pattern encapsulates data access logic, making it easier to maintain and test.
- **Schema Evolution**: FlyWay migrations ensure consistent schema changes across environments.
- **Performance**: Query optimization and connection pooling ensure efficient database operations.

### Negative

- **Learning Curve**: Developers need to understand Hibernate and Panache to effectively work with the database.
- **Potential for N+1 Queries**: ORM solutions can lead to N+1 query problems if not carefully managed.
- **Overhead**: The ORM layer introduces some overhead compared to raw SQL queries.
- **Complexity**: Managing multiple database backends adds complexity to the codebase.

## Alternatives Considered

### Raw SQL Queries

Using raw SQL queries instead of an ORM was considered. This approach was rejected because:
- It would require more boilerplate code for mapping between database results and domain objects.
- It would make the codebase more vulnerable to SQL injection attacks if not carefully implemented.
- It would make it harder to support multiple database backends.
- It would reduce developer productivity.

### NoSQL Database

Using a NoSQL database like MongoDB or Cassandra was considered. This approach was rejected because:
- The data model of the Lemline project has clear relational aspects that are well-suited to a relational database.
- Relational databases provide stronger consistency guarantees, which are important for workflow state management.
- The team has more experience with relational databases, reducing the learning curve.
- The ecosystem of tools and libraries for relational databases is more mature and comprehensive.

## References

- [Hibernate with Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [FlyWay Migration](https://flywaydb.org/)
- [Repository Pattern](https://martinfowler.com/eaaCatalog/repository.html)