# SQLite orchestration on one host

Select SQLite with `lemline.database.type: sqlite`. The runner resolves
`lemline.database.sqlite.path` against its working directory and defaults to
`orchestration.db`. The parent directory must exist and allow writes to the database
and its WAL files. Use the same resolved filename for every process on the host.
The runner resolves symbolic links. Opening the same file through different hard links
can give processes different WAL sidecar paths.

Apply the Flyway migrations with `lemline migrate --force`, or initialize the file
from one process with `lemline.database.migrate-at-start: true` before joining other
processes. The default for a persistent database is `false`. The SQLite migrations
live under `db/migration/sqlite` in each orchestration module.

PGMQ and PostgreSQL analytics use their own databases. When PostgreSQL only hosts
the broker, provision the existing PGMQ SQL migrations V800 through V803 before
starting the gateway or workers. The packaged test applies those migrations to an
independent PostgreSQL database.

## Connection and transaction semantics

The named SQLite pool establishes and checks WAL before Flyway or runtime traffic.
Scheduled work starts after database migrations and messaging initialization, so
a due row can publish on the first poll after restart.
Xerial applies `foreign_keys=ON`, `synchronous=FULL` and `busy_timeout=5000` when
it opens each physical connection. Flyway uses the same pool.

A write transaction acquires the only writer with `BEGIN IMMEDIATE` before it
reads candidate rows. Nested repository writes reuse its connection. This avoids
upgrading a stale WAL read snapshot after another runner has committed. The SQLite
busy handler bounds an individual writer acquisition to five seconds. Contention
that outlasts that wait reaches the caller or the existing scheduler or broker
retry path. Plain reads can use independent connections during a write.
PostgreSQL, MySQL and H2 continue using their existing JDBC transaction and locking
semantics.

## Reservation, publication and finalization

1. The scheduled outbox opens a write transaction and selects due, uncompleted
   rows within its configured namespace scope. The gateway fast path selects
   its committed start row inside the same kind of transaction.
2. The relay holds the writer reservation while awaiting publication. It sends
   the row's existing ID or the deterministic ID associated with the emission.
   A recurring schedule derives its identity from the persisted tick.
3. The relay records completion, the next recurrence, or the retry or failure
   state and commits. Cleaners select and delete rows in one transaction under
   the same writer reservation.

Reservation lasts as long as the transaction. If the process is killed, its
connection closes, SQLite rolls back the pending write, and the next poll can
select the row. If the broker committed before SQLite did, the relay republishes
with the same message ID. Consumers can receive the replay and the existing
handler and repository idempotency rules apply. The PGMQ deduplication index
expects an object, and the current PGMQ connector writes these headers as JSON
strings. The packaged crash test verifies that each active copy carries the same
ID and that delivery of the copies preserves one durable wait. Publication requires
a separate transport database so it can commit while orchestration holds its writer.

An unavailable broker can delay every SQLite writer for the duration of publication
attempts, including writers for other namespaces. The emitter's bounded retries
still apply. WAL permits concurrent readers. A single host can run multiple workers
with serialized write transactions and at-least-once delivery.

## SQL and lock audit

| Area | SQLite treatment |
| --- | --- |
| Common CRUD and IDs | Canonical UUID text, epoch microsecond timestamps, quoted names, null-safe equality and conflict handling that preserves other constraint errors. |
| Wait, retry, recurring schedule, event-start and gateway outboxes | The write transaction replaces row-level `FOR UPDATE SKIP LOCKED`. Existing delays, attempts, cleanup and namespace predicates still select the candidates. |
| Cleaners | The write transaction encloses candidate selection and deletion. Foreign keys cascade to owned fork branches, listener events and receipts, and event-schedule state. |
| Gateway start and schedule completion | Insert and duplicate lookup share a connection in the start transaction. Schedule completion reads and updates the row inside its write transaction. |
| Definitions and event schedules | The writer protects definition lookup, event collection progress, receipts and start insertion. Definition deletion removes listeners on the same connection. SQLite's `json_each` matches correlation keys and values exactly. |
| Fork and parent | The write transaction serializes branch initialization and completion, join or compete decisions, parent-child state writes and the associated durable-event handling. |
| Listeners | The writer protects delivery receipts, event-index allocation, closure and timeout. The foreach eligibility update materializes its candidate heads in one SQL statement. Ordered JSON aggregation breaks timestamp ties by event index. |
| Direct inserts and updates | SQLite acquires the writer in the statement's implicit transaction when the caller has no enclosing transaction. |

`SQLitePackagedRunnerIT` exercises durable wait restart, crash recovery and stable
replay through PGMQ, and two scoped workers sharing a file. It uses PostgreSQL
lifecycle analytics to check terminal state. Native SQLite qualification and
cross-role same-file checks belong to the integration branch.
