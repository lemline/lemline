---
title: How to publish and run a workflow
---

# How to publish and run a workflow

This guide covers publishing a workflow definition to Lemline and starting instances of it, with the CLI or with the gRPC gateway.

## Prerequisites

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands below from a
second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.
The [production section](#running-workflows-in-production) lists what changes with a shared database and broker.

## Understanding the Workflow Lifecycle

1. **Define**: write a workflow definition in YAML
2. **Publish**: store the definition in Lemline's database
3. **Start**: create instances of the workflow
4. **Observe**: follow instances in the dashboard, through the gateway or in the worker log

## Publishing a Workflow Definition

Save this definition, the conditional greeting from [Acknowledge the first order](lemline-tutorial-hello.md#add-conditional-logic), as `acknowledge-order.yaml`:

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/6-adding-conditional-logic.yaml"/>

```bash
bin/lemline definition post --file acknowledge-order.yaml
```

The CLI answers `Workflow successfully created: 'acknowledge-order' (version '0.1.3')`. The namespace, name and
version come from the `document` section of the file. Lemline rejects a file that is not a readable definition.
Nothing is stored for a rejected file, and the command exits with a non-zero code.

### Publishing Multiple Workflows

Pass a directory to publish every definition file it contains. Add `--recursive` to include subdirectories.

```bash
mkdir -p workflows
cp acknowledge-order.yaml workflows/
bin/lemline definition post --directory workflows
```

Each file is reported on its own line. Here the only definition is already registered, so the CLI prints
`Workflow already exists (use --force to overwrite)` and still exits successfully.

### Republishing a Workflow

A definition with the same namespace, name and version is not replaced unless you add `--force`:

```bash
bin/lemline definition post --file acknowledge-order.yaml --force
```

The CLI answers `Workflow successfully updated`.

### Checking Published Definitions

```bash
bin/lemline definition get
```

The CLI lists the namespace, name and version of each definition, here `tutorial acknowledge-order 0.1.3`, then prints the selected definition.
With several definitions it asks which one to show. In a script, pass the namespace, name and version instead, as in
`bin/lemline definition get tutorial acknowledge-order 0.1.3`.

## Running a Workflow Instance

### Using the CLI

The command takes a namespace, a name and an optional version:

```text
bin/lemline instance start <namespace> <name> [<version>]
```

Without a version, the latest published version is started. Provide input as JSON:

```bash
bin/lemline instance start tutorial acknowledge-order --input '{"orderId":"ORD-101","hour":10}'
```

The command prints `Instance <id> started successfully` with the version it chose, here `0.1.3`, and returns;
it does not wait for the workflow to finish. The running worker executes the instance and logs
`Workflow completed with output` with `{"message":"Good morning: order received for ORD-101!"}`.

### Using the gRPC Gateway

Programs start instances through the gateway's `StartInstance` RPC (`lemline.gateway.v1.GatewayService`). Supply your own `instance_id` (a UUIDv7) to make retries idempotent. See [gRPC Gateway Reference](lemline-ref-gateway.md).

## Checking Instance Status

The CLI has no command to read instances. Use:

- the dashboard, connected to the gateway
- the gateway RPCs `GetInstance`, `ListInstances` and `ListInstanceEvents`
- the worker log (`bin/lemline listen --debug` logs each workflow output)
- on the SQLite setup, the `lemline_lifecycle_events` table of `analytics.db`

## Running Workflows in Production

### 1. Use a Production Database

<!-- workflow-example: data -->
```yaml
lemline:
  database:
    type: postgresql
    postgresql:
      host: db.internal
      port: 5432
      database: lemline
      username: lemline_user
```

Pass the password as `LEMLINE_DATABASE_POSTGRESQL_PASSWORD`.

### 2. Configure a Message Broker

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      brokers: kafka:9092
```

### 3. Run Several Runners

Start several `lemline listen` processes with the same database and broker. They share the work; no load balancer is needed.

### 4. Check Health

The worker serves health and metrics on port 8080, or on the port given with `--metrics-port`:

```bash
curl http://localhost:8080/q/health
```

A healthy worker answers `"status": "UP"`.

### 5. Monitor Activity

```bash
curl http://localhost:8080/q/metrics
```

### 6. Keep One Configuration per Environment

```bash
bin/lemline listen --config /etc/lemline/prod.yaml
```

## Troubleshooting

### Workflow Won't Publish

1. **Invalid definition**: check the error printed by `definition post`.
2. **Database connectivity**: ensure the database is reachable.
3. **Version already exists**: use `--force` to replace it, or publish a new version.

### Workflow Instance Won't Start

1. **Unknown workflow**: check namespace, name and version with `bin/lemline definition get`.
2. **Invalid input**: input must be valid JSON and match the workflow's input schema.

### Instance Does Not Progress

1. **No runner**: check that a runner is running and serves the workflow's namespace (`lemline.namespaces`).
2. **External dependencies**: check the services the workflow calls.
3. **Waits**: the workflow may be waiting for a timer or an event.

## Next Steps

- Learn how to [monitor workflow execution](lemline-howto-monitor.md)
- Understand [how to debug faults in task execution](lemline-howto-debug.md)
