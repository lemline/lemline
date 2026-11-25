---
title: How to publish and run a workflow
---

# How to publish and run a workflow

This guide covers publishing a workflow definition to Lemline and starting instances of it, with the CLI or with the gRPC gateway.

## Prerequisites

Before you begin, ensure that:
- You have [installed Lemline](lemline-getting-started.md)
- You have [defined a workflow](lemline-howto-define-workflow.md)
- You have a configured database and broker, and at least one runner running (`lemline listen`)

## Understanding the Workflow Lifecycle

1. **Define**: write a workflow definition in YAML
2. **Publish**: store the definition in Lemline's database
3. **Start**: create instances of the workflow
4. **Observe**: follow instances in the dashboard or through the gateway

## Publishing a Workflow Definition

For a local example, save the conditional definition from [Acknowledge the first order](lemline-tutorial-hello.md#add-conditional-logic) as `acknowledge-order.yaml` in the SQLite setup directory. Use your installed Lemline CLI (or `java -jar lemline.jar`).

```bash
lemline definition post --file acknowledge-order.yaml
```

The namespace, name and version come from the `document` section of the file. Lemline parses the definition before storing it and rejects invalid files.

### Publishing Multiple Workflows

```bash
lemline definition post --directory /path/to/workflows
```

Add `--recursive` to include subdirectories.

### Republishing a Workflow

A definition with the same namespace, name and version is not replaced unless you add `--force`:

```bash
lemline definition post --file acknowledge-order.yaml --force
```

### Checking Published Definitions

```bash
lemline definition get
```

## Running a Workflow Instance

### Using the CLI

```bash
lemline instance start <namespace> <name> [<version>]
```

Without a version, the latest published version is started. Provide input as JSON:

```bash
lemline instance start tutorial acknowledge-order 0.1.3 --input '{"orderId":"ORD-101","hour":10}'
```

The command prints the new instance id and returns; it does not wait for the workflow to finish. The instance is executed by the running runners.

### Using the gRPC Gateway

Programs start instances through the gateway's `StartInstance` RPC (`lemline.gateway.v1.GatewayService`). Supply your own `instance_id` (a UUIDv7) to make retries idempotent. See [gRPC Gateway Reference](lemline-ref-gateway.md).

## Checking Instance Status

The CLI has no command to read instances. Use:

- the dashboard, connected to the gateway
- the gateway RPCs `GetInstance`, `ListInstances` and `ListInstanceEvents`
- the runner logs (`lemline listen --info`)

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

```bash
curl http://localhost:8080/q/health
```

### 5. Monitor Activity

```bash
curl http://localhost:8080/q/metrics
```

### 6. Keep One Configuration per Environment

```bash
lemline listen --config /etc/lemline/prod.yaml
```

## Troubleshooting

### Workflow Won't Publish

1. **Invalid definition**: check the error printed by `definition post`.
2. **Database connectivity**: ensure the database is reachable.
3. **Version already exists**: use `--force` to replace it, or publish a new version.

### Workflow Instance Won't Start

1. **Unknown workflow**: check namespace, name and version with `lemline definition get`.
2. **Invalid input**: input must be valid JSON and match the workflow's input schema.

### Instance Does Not Progress

1. **No runner**: check that a runner is running and serves the workflow's namespace (`lemline.namespaces`).
2. **External dependencies**: check the services the workflow calls.
3. **Waits**: the workflow may be waiting for a timer or an event.

## Next Steps

- Learn how to [monitor workflow execution](lemline-howto-monitor.md)
- Understand [how to debug faults in task execution](lemline-howto-debug.md)
