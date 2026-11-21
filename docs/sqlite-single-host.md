# Run Lemline with SQLite on one host

Use the [SQLite example](../examples/lemline-sqlite.yaml) with a worker and a gateway
on the same host. The example binds the unauthenticated gateway to loopback. Use
a local filesystem and a directory writable by both processes. Both processes
resolve relative database paths against their working directory.

From a data directory containing a copy of the example as `lemline.yaml`, start
the worker and gateway in separate terminals:

```sh
lemline --config lemline.yaml listen
lemline --config lemline.yaml gateway start
```

Set `lemline.namespaces` to the namespaces the worker should follow. Register
workflow definitions with `lemline --config lemline.yaml definition post -f workflow.yaml`.
Clients can then start runs through the gateway's `StartInstance` RPC. The
`listen` command consumes lifecycle events and writes the analytics file. The
gateway reads history from that file independently of the consumer.

Use a separate file for orchestration, analytics and messaging. SQLite has one
writer per file, including in WAL mode. The writer lock on the orchestration file
can span a transport publication. Independent files let that publication commit
while the orchestration transaction remains open. The files have independent
transaction boundaries. Transport commits use full synchronization. A crash
between publication and orchestration completion can cause a repeat publication
of the same logical message. Delivery and redelivery follow Lemline's existing
idempotency guarantees.

## Back up and restore the three files together

1. Stop all gateway, worker and administrative processes that can open the
   databases. Keep them stopped until the backup is complete. Record the runner
   version and save the matching configuration with the backup.
2. From the data directory, truncate each WAL using SQLite's checkpoint
   command. Confirm the first returned column is zero for every file. A
   nonzero value means the checkpoint is busy. Resolve the open connection and
   retry the checkpoint before copying any database. If a process crashed,
   retain its WAL until SQLite can replay and checkpoint it.
3. Copy all three main database files into one new backup directory. Restore
   all three files from that same backup into an empty directory and validate
   each with `PRAGMA quick_check`. Use the matching configuration and start both
   processes from the restored directory. For absolute paths, update all three
   settings to point to the restored files.

```sh
set -eu
backup_dir=$(mktemp -d ./lemline-backup.XXXXXX)
for db in orchestration.db messaging.db analytics.db; do
    test -f "$db"
    checkpoint=$(sqlite3 "$db" 'PRAGMA wal_checkpoint(TRUNCATE);')
    test "${checkpoint%%|*}" = 0
done
cp orchestration.db messaging.db analytics.db lemline.yaml "$backup_dir/"
restore_dir=$(mktemp -d ./lemline-restore.XXXXXX)
cp "$backup_dir/"*.db "$backup_dir/lemline.yaml" "$restore_dir/"
for db in orchestration.db messaging.db analytics.db; do
    test "$(sqlite3 "$restore_dir/$db" 'PRAGMA quick_check;')" = ok
done
printf 'Restored files in %s\n' "$restore_dir"
```

Choose an isolated gateway port if you retain a running source deployment while
validating the restore. Operators should check that a pending workflow resumes
and the gateway can read both its prior and new history.

## Run packaged validation

```sh
./gradlew :lemline-runner:quarkusIntTest --tests '*SingleHostSQLiteIT'
./gradlew :lemline-runner:quarkusIntTest --tests '*SingleHostSQLiteIT' -Dquarkus.package.jar.type=uber-jar
./gradlew :lemline-runner:testNative --tests '*SingleHostSQLiteIT'
```

The packaged tests start temporary local processes, verify gateway history
after restart and restore, and exercise two transport replay cases and lifecycle
redelivery after ingestion. The test report identifies the temporary directories
that contain process logs.

To measure command latency, run the optional benchmark with no other test
processes on the host:

```sh
RUN_SQLITE_COMMAND_BENCHMARK=1 ./gradlew :lemline-runner:quarkusIntTest \
  --tests '*measure gateway command latency*' -Dquarkus.package.jar.type=uber-jar
```

The benchmark warms up the deployed runner, then offers 160 gateway starts at
20 per second in each phase. The second phase adds up to 400 synthetic lifecycle
events per second through the SQLite lifecycle queue for approximately eight
seconds. It reports p50, p95 and p99 from the enqueue timestamp to the start
command's acknowledgement. This includes queue polling and workflow command
execution. The acknowledgement audit adds the same SQLite write in both
phases. The benchmark also reports how much synthetic analytics work had been
ingested when latency samples were read and requires the backlog to drain.
