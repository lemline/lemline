# Single-host SQLite coordination

The starting point is `origin/main` at `e230538d685297d0b63b31faff40ee6534d63630`.
Lot 0 adds the JDBC driver to the runner distribution. The following contract is for the
three implementation branches and the eventual integration PR.

## Configuration and startup

[ADR 0004](adr/0004-database-storage-strategy.md) defines the repository and Flyway strategy.
[ADR 0003](adr/0003-messaging-architecture.md) defines the command and durable-event channels.
[ADR 0016](adr/0016-namespace-isolation.md) defines namespace routing and the shared lifecycle
destination. The table traces the current startup paths to the proposed SQLite settings.

| Selection | Current path and compatibility to retain | SQLite path setting |
| --- | --- | --- |
| `lemline.database.type` | `LemlineConfigSource.generateWorkflowDatabaseProperties` uses the explicit type, otherwise infers PostgreSQL or MySQL from the corresponding section and rejects ambiguity. Its fallback is `in-memory` (H2), available in test packaging. `DatabaseManager` selects Agroal and Flyway, `DatabaseStartupValidator` checks connectivity and `DatabaseMigration` applies or checks migrations. Preserve enable gates and the persistent backend default of `migrate-at-start: false`. | `lemline.database.sqlite.path` |
| `lemline.analytics.type` | `generateAnalyticsDatabaseProperties` runs when the lifecycle consumer or gateway is enabled. It uses the explicit type, otherwise infers PostgreSQL from its section and falls back to `h2` (test packaging). `AnalyticsManager` supplies ingestion, `AnalyticsStartupValidator` and `AnalyticsMigration` check and migrate for the lifecycle consumer, and the gateway has its own `AnalyticsDataSourceProvider` and `GatewayStartupValidator`. Confirm schema readiness on gateway-only startup as well. | `lemline.analytics.sqlite.path` |
| `lemline.messaging.type` | `generateMessagingProperties` uses the explicit type, otherwise infers Kafka, RabbitMQ or PGMQ from their sections and rejects ambiguity. The fallback is `in-memory`. It supplies `mp.messaging.*` for SmallRye, and `MessagingStartupValidator` checks the selected broker when messaging is enabled. Preserve channel enablement, prefix and namespace defaults. | `lemline.messaging.sqlite.path` |

`LemlineApplication` locates the config file before Quarkus starts.
`LemlineConfigSource` merges file and external properties, `LemlineConfiguration` validates the
selectors, and `lemline-runner/src/main/resources/application.properties` registers the
build-time datasource kinds and CDI indexing needed by the packaged app. For the JVM pools,
start with Quarkus's custom JDBC configuration (`db-kind=other`, `jdbc.driver=org.sqlite.JDBC`).
[Quarkus custom JDBC drivers](https://quarkus.io/guides/datasource/#custom-databases-and-drivers)

SQLite is opt-in through each exact `type: sqlite` selector. A path alone leaves existing
inference and defaults in effect. Preserve precedence across file, environment and system
properties. Gate each SQLite pool and its migrations on its own selector and enable flags.
Disable the unused server datasource for that role when SQLite is selected. Help and version
must work independently of file availability.

### Target profile for the integration PR

```yaml
lemline:
  database:
    type: sqlite
    migrate-at-start: true
    sqlite:
      path: orchestration.db
  analytics:
    type: sqlite
    sqlite:
      path: analytics.db
  messaging:
    type: sqlite
    sqlite:
      path: messaging.db
    lifecycleevents:
      producer:
        enabled: true
      consumer:
        enabled: true
  gateway:
    enabled: true
    grpc:
      host: 127.0.0.1
    tls:
      enabled: false
    authentication:
      enabled: false
```

With an explicit SQLite selection, an omitted path uses the basename shown above. Resolve
relative paths against the runner process working directory, consistently across config
sources, and pass the resolved absolute path to JDBC. Accept local filesystem paths with an
existing, writable parent directory. Reject blank paths, directories and SQLite URI or
in-memory settings. Before any selected component opens or migrates its file, resolve aliases
through the real parent path for new files and the real path for existing ones. Use same-file
identity for existing files to catch hard links as well. If two enabled roles resolve to the same
file, fail startup with both setting names and the resolved paths. Recheck identities once any
new paths have been created and before migrations or traffic, to catch aliases on filesystems
with case-insensitive names. A shared file makes orchestration and transport compete for
SQLite's only writer lock. Where a publication happens inside an orchestration write
transaction, as when a child workflow starts, the transport's second connection would need
the lock that transaction holds while it waits for the publish confirmation.

## SQLite contracts

- Establish and verify `journal_mode=WAL` per file before serving traffic. Apply
  `foreign_keys=ON`, `synchronous=FULL` and `busy_timeout=5000` to every physical connection,
  including migrations and connections created after startup. Verify the settings on independently
  acquired connections. SQLite still serializes writers. Bound any retry around the five-second
  lock wait and surface persistent contention through the component's existing failure or retry
  path. Each component owns its transaction and concurrency policy.
- Publication confirms only after the message transaction commits in `messaging.db`, including
  when a previously committed active copy satisfies a duplicate send. This survives a process
  crash. WAL with FULL sync also makes a confirmed write durable across host power loss if the
  local filesystem and device honor sync. A lost or damaged disk can still lose the queue. The
  sync on each commit costs write throughput. Cross-file commits remain independent.
  [SQLite PRAGMA reference](https://www.sqlite.org/pragma.html#pragma_synchronous)
- An outbox retry after the transport commit and before the orchestration completion commit
  republishes the same logical message with the same `messageId`. The producer must derive or
  persist the ID before its first attempt and pass it through `CommandEmitter` / `EventEmitter`
  to `MessageEmitter`, which otherwise generates a fresh ID. Deduplicate sends atomically
  by `(queue, messageId)` while the copy is active, including while leased. Once consumed,
  a repeated send may be delivered again. Exercise that case with Lemline's existing consumer
  and repository idempotency. Keep the original ID across delivery attempts and preserve the existing
  message encoding. Follow the configured namespace prefix for commands, durable events and
  emit/listen CloudEvents. Lifecycle events keep a shared destination.
- Store the complete serialized CloudEvent, including extensions and data. Deduplicate only on
  the exact `(source, event_id)` pair with a targeted unique constraint and conflict handler.
  Keep both values as case-sensitive text, without parsing event IDs as UUIDs.
  Store the projected `lemline_workflow_id` as canonical lowercase UUID text
  (SQL NULL for a missing or invalid value). Store projected `event_time` and `ingested_at` as
  signed INTEGER Unix epoch microseconds in UTC. Preserve null event times and bind/read
  timestamp query parameters as integers with the same conversion. The original CloudEvent
  retains any precision finer than a microsecond. Preserve existing order and cursor tie-breaks.

## Branch handoffs

**Orchestration.** Own `DatabaseType.kt`, `DatabaseConfig.kt`, the SQL helpers and repo fixtures
under `lemline-runner-common`, the runner's `DatabaseManager.kt`, `DatabaseStartupValidator.kt`
and `DatabaseMigration.kt`, plus the orchestration migrations and repositories in each feature
module. The gateway start transaction belongs here. Extend `GatewayCommandRepository.kt`,
`GatewayCommandOutbox.kt`, `InstanceStartService.kt` and the gateway's orchestration migrations.
See [SQLite orchestration](sqlite-orchestration.md) for the SQL and transaction audit.
Handle the new enum in all callers, including the CLI, and audit stable relay IDs through
`AbstractOutbox.kt`. Add a named `sqlite` datasource and
`classpath:db/migration/sqlite` migrations. Exercise a scheduled wait and resume after
restarting the runner on the same file, using existing PGMQ in PostgreSQL. Verify that the row
and resume command survive the restart, and the workflow reaches its expected terminal state.
A focused packaged check can extend `PackagedRunnerIT` with its own temp-file profile.

**Analytics.** Own `AnalyticsType.kt`, `AnalyticsConfig.kt`, `AnalyticsManager.kt`, its
startup validator and migration hooks, `LifecycleAnalyticsSubscriber.kt`,
`LifecycleAnalyticsRepository.kt`, and `db/migration/analytics/sqlite`. In the gateway own
`AnalyticsDataSourceProvider.kt`, `WorkflowAnalyticsEventSourceSelector.kt` and its SQL
event source, `GatewayJdbcSupport.kt` analytics helpers, `DefinitionStatsQueryService.kt`,
`InstanceQueryService.kt`, `InstanceEventQueryService.kt` and `NamespaceActivityMonitor.kt`.
Use a named `analytics-sqlite` datasource with its own Flyway history. Key analytics SQL
helpers on `AnalyticsType` so this branch can compile independently of an orchestration
`DatabaseType.SQLITE` change. Against the existing in-memory lifecycle connector, ingest
duplicate events and verify one row per identity and gateway history, watch cursors and
statistics through the real SQLite file. Use PostgreSQL for orchestration if testing a
packaged gateway.

**Messaging.** Own the new `lemline-runner-messaging-sqlite` module, the `smallrye-sqlite`
connector and its Flyway migrations under `db/migration/messaging/sqlite`.
Use `lemline-runner-messaging-pgmq`'s
`PgmqConnector.kt` and `PgmqMetadata.kt` for the SmallRye SPI reference. Own
`MessagingType.kt`, the runner's `MessagingStartupValidator.kt`, `MessageMetaData.kt`,
`ArrivalNamespaceResolver.kt` and connector-specific parts of the emitters. Preserve
`NamespaceDestinations`, channel toggles and namespace recovery behavior. Verify queue
persistence, ack/nack and redelivery, deduplication, and all four channel families with SQLite
transport, PostgreSQL orchestration and existing analytics. Reuse the shared workflow cases
and PGMQ scenario coverage below.

**Integration owns the shared files.** Each branch may include the following scoped wiring
in its own checkout to build and test independently. Integration reconciles this wiring once,
owns cross-role path validation and the final all-SQLite profile, and verifies the combined
CLI and packaged runner. Keep native-image driver registration and release checks under the
integrator's ownership.

| Shared file | Orchestration contribution | Analytics contribution | Messaging contribution |
| --- | --- | --- | --- |
| `LemlineConfigSource.kt`, `LemlineConfiguration.kt` and `LemlineConfigConstants.kt` | Database selection and settings | Analytics selection and settings | Messaging selection and settings, plus SmallRye channel properties |
| `AnalyticsConfig.kt` | Keep the existing analytics dialect helpers exhaustive if adding a new `DatabaseType` requires it | Move the analytics helpers to `AnalyticsType` and carry that version forward | |
| Runner `application.properties` and `build.gradle.kts`, root `settings.gradle.kts` and version catalog | SQLite datasource/Flyway registration | Analytics datasource/Flyway registration | Connector module include, dependency and CDI index |
| CLI `ConfigCreateCommand.kt` / `ConfigTemplateGenerator.kt` | Database enum and config rendering | Analytics template inputs if exposed | Transport enum and config rendering |
| Runner packaged environment and tests | SQLite DB with existing broker restart case | SQLite analytics with existing broker | SQLite connector with existing DB cases |

`lemline-runner-common:generateLemlineConfigKeys` derives keys from `LemlineConfiguration.kt`.
Add each SQLite group there and cover its explicit selector, path default and precedence in
the corresponding config-source tests.

## Test matrix

For the branch commands below run Gradle from the repository root and capture complete logs
with `set -o pipefail` and `2>&1 | tee /tmp/lemline-<lot>-test.log`. Use Docker/Testcontainers
for the existing server and broker profiles. SQLite tests use a fresh temporary file per
fixture and run with each branch's other selections set to existing backends.

| Owner and Gradle task | Existing suite and backend coverage | SQLite addition |
| --- | --- | --- |
| Orchestration, each feature module's `test` | Common `TestDatabaseConfig` and repository fixtures (CRUD, ID/instance, outbox and cleaner). H2, MySQL and PostgreSQL concrete suites in `lemline-runner-{definitions,event-schedules,failures,forks,listeners,parents,retries,schedules,waits}`: Definition, EventSchedule, Failure, Fork and ForkBranch, Listener and ListenerEvent, Parent, Retry, Schedule and ScheduleRecurrence, Wait. | Run the same inherited cases on SQLite. Extend the H2-only `ScheduleOutboxH2Test` cases to SQLite too. |
| Orchestration, `lemline-runner-gateway:test` | `GatewayCommandRepository{H2,MySQL,Postgres}Test`, plus H2 `GatewayCommandOutbox{Integration,Processor}Test` and `DefinitionQueryServiceH2Test`. | Repeat repository, atomic start/outbox, relay and definition read cases on SQLite. |
| Orchestration, `lemline-runner:test` and `lemline-runner:quarkusIntTest` | `{InMemory,MySQL,Postgres}ProfileTest` and packaged PostgreSQL runner/CLI with PGMQ. | Add startup/migration checks and the packaged restart case. |
| Analytics, `lemline-runner-analytics:test` and `lemline-runner-gateway:test` | `LifecycleAnalyticsMigrationTest` and `DefinitionStatsQueryServiceTestBase` subclasses cover H2 and PostgreSQL. Event and instance watch service tests cover the read API, generally with doubles. | Add SQLite migration/index and stats cases. Cover real-file instance listing, history, watch, cursor ordering and namespace activity alongside existing read API checks. |
| Analytics, `lemline-runner:test` | `LifecycleAnalyticsInMemoryIntegrationTest` on H2; `LifecycleAnalyticsBrokerIntegrationTestBase` on Kafka/RabbitMQ with H2 and PGMQ with PostgreSQL analytics. | Add SQLite analytics with the existing in-memory connector for duplicate/concurrent ingestion and gateway readback. |
| Messaging, `lemline-runner:test` | `WorkflowConsumer{InMemory,Kafka,RabbitMQ,Pgmq}Test`, corresponding connector profiles and `{InMemory,Kafka,RabbitMQ,Pgmq}ExecutionTests.kt`. The shared workflow profiles pair PGMQ with PostgreSQL orchestration and the others with H2. Broker schedule base runs on Kafka, RabbitMQ and PGMQ. PGMQ also has `PgmqEventScheduleExecutionTest`. Lifecycle broker tests cover Kafka, RabbitMQ and PGMQ with the analytics backends above. | Add a SQLite connector profile for consumer, shared workflow/emit/listen, schedule and event-start scenarios, and lifecycle ingest with existing analytics. |
| Messaging, `lemline-runner-messaging-pgmq:test`, new connector module's `test`, and `lemline-runner:test` | PGMQ connector, client, error handling, config, integration and multi-queue integration suites. Opt-in `ConnectorConfigJarTest` checks Kafka/PGMQ startup against the fast JAR. | Port transport-equivalent connector assertions to the SQLite module. Add packaged connector selection coverage. |
| Integration, `lemline-runner:quarkusIntTest` / `lemline-runner:testNative` | `PackagedCliIT` / `PackagedRunnerIT` cover the PostgreSQL orchestration and analytics + PGMQ profile. `Native Binary Tests` runs all on Linux and a CLI-only slice on Windows. | Keep the historical profiles and add the all-SQLite scenario in the JVM and supported native packages. |

Run these commands independently once each branch implements its cases.

```sh
# Orchestration. Run the relevant feature module tasks together.
./gradlew :lemline-runner-definitions:test :lemline-runner-event-schedules:test \
  :lemline-runner-failures:test :lemline-runner-forks:test :lemline-runner-listeners:test \
  :lemline-runner-parents:test :lemline-runner-retries:test :lemline-runner-schedules:test \
  :lemline-runner-waits:test :lemline-runner-gateway:test
./gradlew :lemline-runner:test --tests '*InMemoryProfileTest' --tests '*MySQLProfileTest' \
  --tests '*PostgresProfileTest' --tests '*SQLiteDatabaseProfileTest'
./gradlew :lemline-runner:quarkusIntTest --tests '*PackagedRunnerIT*'

# Analytics. Use the in-memory lifecycle connector for the SQLite analytics profile.
./gradlew :lemline-runner-analytics:test :lemline-runner-gateway:test
./gradlew :lemline-runner:test --tests '*LifecycleAnalytics*IntegrationTest*'

# Messaging. Run the connector module and runner profiles with existing persistence.
./gradlew :lemline-runner-messaging-pgmq:test :lemline-runner-messaging-sqlite:test
./gradlew :lemline-runner:test --tests '*ExecutionTest*' --tests '*WorkflowConsumer*Test*' \
  --tests '*ProfileTest*' --tests '*LifecycleAnalytics*IntegrationTest*'
./gradlew :lemline-runner:assemble
RUN_JAR_TESTS=true ./gradlew :lemline-runner:test --tests '*.ConnectorConfigJarTest'
```

Keep every historical case and assertion. The existing JVM suites run via module Gradle tasks;
there is no combined JVM backend job in the current workflows. Integration owns that CI matrix
and runs the combined backend and packaging checks on the last SHA of the integration PR.
Interrupt the fully SQLite runner after publication commits and before the outbox completion
commits. Restart from the same three files and exercise both a still-active queue copy and an
already-consumed copy. Verify stable message identity, bounded recovery without a lock stall,
and complete, deduplicated lifecycle history from the gateway.

## Lot 0 packaging verification

The Gradle 9.9.1 wrapper ran on GraalVM Community JDK 25.0.1 with the Java 17 toolchain.
Quarkus 3.40.1.2 resolves `flyway-core:12.0.0`, whose service registry includes
`SQLiteDatabaseType`. The default Xerial `sqlite-jdbc:3.54.1.0` JAR supplies the JDBC
service and the SQLite 3.54.1 shared library. The file probe passed on macOS arm64 with
Zulu JDK 17.0.13 using the Quarkus release uber-JAR and the separate libraries from the
default fast-JAR distribution. Flyway core and the unclassified JDBC JAR suffice for both.
The probe uses packaged dependencies directly. SQLite datasource registration
and migration wiring inside the runner are part of the implementation lots.
[Driver release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0)
[Flyway SQLite reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite)

The uber-JAR passed four packaged CLI and runner tests, including workflow execution with
PostgreSQL and PGMQ. The fast-JAR started with the existing Kafka and PGMQ configurations
in the targeted runner suite, which passed 77 tests.

```sh
./gradlew :lemline-runner:dependencyInsight --configuration runtimeClasspath --dependency sqlite-jdbc
./gradlew :lemline-runner:dependencyInsight --configuration runtimeClasspath --dependency flyway-core
./gradlew :lemline-runner:quarkusBuild :lemline-runner:spotlessCheck -Dquarkus.package.jar.type=uber-jar
./gradlew :lemline-runner:quarkusIntTest -Dquarkus.package.jar.type=uber-jar
./gradlew :lemline-runner:quarkusBuild
RUN_JAR_TESTS=true ./gradlew :lemline-runner:test --tests '*.ConnectorConfigJarTest' \
  --tests '*LemlineConfigSource*Test' --tests '*ArrivalNamespaceResolverTest'
```

To reproduce the file probe, set `JAVA17` to a JDK 17 `java` executable and run from the
repository root. Use the corresponding package immediately after its build. The printed JDBC
and Flyway code sources should be inside that distribution.

```sh
probe=$(mktemp -d)
mkdir "$probe/uber" "$probe/fast"
cat > "$probe/SQLiteProbe.java" <<'JAVA'
import java.nio.file.*;
import java.sql.*;
import org.flywaydb.core.Flyway;

class SQLiteProbe {
    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        Path db = dir.resolve("probe.db");
        check(Files.notExists(db));
        String url = "jdbc:sqlite:" + db;
        Class<?> driver = DriverManager.getDriver(url).getClass();
        check(driver.getName().equals("org.sqlite.JDBC"));
        System.out.println(driver.getProtectionDomain().getCodeSource().getLocation());
        System.out.println(Flyway.class.getProtectionDomain().getCodeSource().getLocation());
        Path migration = Files.createDirectory(dir.resolve("migration"));
        Files.writeString(migration.resolve("V1__probe.sql"), "CREATE TABLE probe (id INTEGER PRIMARY KEY);");
        Flyway flyway = Flyway.configure().dataSource(url, null, null)
            .locations("filesystem:" + migration).load();
        check(flyway.migrate().migrationsExecuted == 1);
        flyway.validate();
        check(Files.isRegularFile(db) && Files.size(db) > 0);
        try (var conn = DriverManager.getConnection(url); var sql = conn.createStatement()) {
            conn.setAutoCommit(false);
            sql.executeUpdate("INSERT INTO probe VALUES (1)");
            conn.commit();
            sql.executeUpdate("INSERT INTO probe VALUES (2)");
            conn.rollback();
        }
        try (var conn = DriverManager.getConnection(url); var sql = conn.createStatement();
             var rows = sql.executeQuery("SELECT id FROM probe")) {
            check(rows.next() && rows.getInt(1) == 1 && !rows.next());
        }
        check(flyway.migrate().migrationsExecuted == 0);
        System.out.println("PASS: migration, validation, file creation, commit, rollback, reopen");
    }
}
JAVA
build="$PWD/lemline-runner/build"
"$JAVA17" -cp "$build/lemline-runner-0.7.0-snapshot-runner.jar" "$probe/SQLiteProbe.java" "$probe/uber"
"$JAVA17" -cp "$build/quarkus-app/lib/main/*:$build/quarkus-app/lib/boot/*" "$probe/SQLiteProbe.java" "$probe/fast"
```

The macOS arm64 native build passed all four packaged tests on the existing PostgreSQL /
PGMQ profile with GraalVM 25.0.1. This validates the native build and historical startup with
the JDBC dependency present. Exercise SQLite file access, Flyway and JNI in the native runner
when the SQLite backends are wired. The Linux and Windows packages also require qualification.

```sh
./gradlew -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
  :lemline-runner:testNative -Dquarkus.native.native-image-xmx=5g
```
