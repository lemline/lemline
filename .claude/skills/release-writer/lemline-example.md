# Example: Lemline v0.5.2 Release Notes

This example demonstrates the recommended structure and style for release notes.

---

# 0.5.2 Release Notes

## New Feature: PGMQ Messaging Connector

This release introduces **PGMQ (PostgreSQL Message Queue)** as a new messaging backend, enabling workflow orchestration using only PostgreSQL — no separate message broker required.

### Why PGMQ?

PGMQ is ideal for deployments where:

- You want to minimize infrastructure dependencies (just PostgreSQL)
- Message throughput requirements are moderate
- You need transactional guarantees between workflow state and messaging
- You're running in environments where Kafka/RabbitMQ aren't available

### Features

| Feature | Description |
|---------|-------------|
| **SQL-Only Implementation** | Uses Flyway migrations (V800-V802) — no PGMQ extension required |
| **Message Deduplication** | Unique index on `messageId` header prevents duplicate messages |
| **Long Polling** | Database-side `read_with_poll()` reduces round-trips (PGMQ v1.8.1) |
| **Batch Operations** | `sendBatch`, `deleteBatch`, `archiveBatch` for efficiency |
| **Dead Letter Queues** | Configurable DLQ for failed message handling |
| **Native Image Support** | Full GraalVM native compilation compatibility |

### Configuration

```yaml
lemline:
  messaging:
    type: pgmq
    pgmq:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
      password: ${LEMLINE_PG_PASSWORD}
      queue: lemline-commands
      visibility-timeout: 30
      batch-size: 10
```

### Architecture

PGMQ uses the same dual-channel pattern as Kafka and RabbitMQ:

- **Commands Channel**: High-throughput workflow step execution
- **Events Channel**: Durable operations (waits, retries, parent tracking)

---

## Bug Fixes

- **PGMQ message ordering**: Sort results by `msg_id` to ensure FIFO order since `UPDATE...RETURNING` doesn't guarantee ordering with concurrent consumers
- **Quarkus injection warnings**: Change `@Inject` fields from `private` to `internal`
- **Native image compilation**: Replace raw Vert.x PostgreSQL client with Quarkus reactive-pg-client extension to resolve Netty buffer initialization conflicts

---

## Improvements

- **Test initialization**: Broker test clients now initialize once per test class using `@TestInstance(PER_CLASS)`, reducing test overhead
- **Global connector defaults**: PGMQ channels inherit connection settings from `pgmq.*` global properties, matching Kafka and RabbitMQ patterns
- **Graceful shutdown**: `PgmqIncomingConnector` properly handles shutdown events to prevent error logs during teardown
- **Kafka testcontainers**: Migrate from deprecated `org.testcontainers.containers.KafkaContainer` to `org.testcontainers.kafka.KafkaContainer` using Apache Kafka native image (`apache/kafka:3.7.0`) which supports KRaft mode by default

---

## Dependencies

| Dependency | Version | Notes |
|------------|---------|-------|
| `io.quarkus:quarkus-reactive-pg-client` | — | Replaces raw Vert.x pg-client |

---

## Breaking Changes

None.

---

## Full Changelog

Compare: https://github.com/lemline/lemline/compare/v0.5.1...v0.5.2

---

# What Makes This Example Effective

## User-Oriented Focus

1. **"Why PGMQ?" section** — Helps users decide if this feature is for them
2. **Use case bullets** — Concrete scenarios, not abstract descriptions
3. **Complete configuration** — Users can copy-paste and adapt

## Technical Clarity

1. **Feature table** — Scannable, structured information
2. **Architecture note** — Only included because users need to understand the dual-channel model
3. **Bug fix context** — Explains *why* the bug occurred, not just that it was fixed

## Consistent Structure

1. **Clear hierarchy** — H2 for sections, H3 for subsections
2. **Predictable sections** — Users know where to look
3. **Breaking Changes always present** — Even when empty, confirms safe upgrade

## Professional Polish

1. **No jargon without context** — "FIFO" and "DLQ" are explained or obvious
2. **Realistic examples** — `${LEMLINE_PG_PASSWORD}` shows environment variable pattern
3. **Links for deep dives** — Full Changelog for those who want details
