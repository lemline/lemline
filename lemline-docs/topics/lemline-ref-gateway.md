---
title: gRPC Gateway Reference
---

# gRPC Gateway Reference

This reference documents the public `lemline-gateway` module: gRPC methods, idempotency behavior, security requirements, and configuration keys.

## Overview

`lemline-gateway` is a secured public API layer for Lemline. It is designed for polyglot clients and exposes two RPCs:

1. `StartWorkflow` for asynchronous workflow start
2. `WatchWorkflow` for replay + live tail of lifecycle analytics events

The gateway reads the same config file as `lemline-runner` and reuses Lemline internals for workflow preparation and command dispatch.

## Service Contract

Service: `lemline.gateway.v1.WorkflowGateway`

### StartWorkflow

Request (`StartWorkflowRequest`):

| Field | Required | Description |
|---|---|---|
| `workflow_id` | yes | Workflow instance ID (UUIDv7 string) |
| `namespace` | yes | Workflow namespace |
| `name` | yes | Workflow name |
| `version` | yes | Workflow version |
| `input_json` | no | Workflow input JSON, defaults to `{}` |
| `zone_id` | no | Timezone ID, defaults to `UTC` |

Response (`StartWorkflowResponse`):

| Field | Description |
|---|---|
| `workflow_id` | Effective workflow ID |
| `version` | Effective resolved version |
| `result` | `START_WORKFLOW_RESULT_ACCEPTED_NEW` or `START_WORKFLOW_RESULT_ACCEPTED_EXISTING` |

### WatchWorkflow

Request (`WatchWorkflowRequest`):

| Field | Required | Description |
|---|---|---|
| `workflow_id` | yes | Workflow instance ID to watch |

Stream response (`WorkflowAnalyticsEvent`):

| Field | Description |
|---|---|
| `sequence` | Analytics sequence (`id`) |
| `cloudevent_json` | Lifecycle CloudEvent JSON payload |

## Execution Semantics

### Start Idempotency

The gateway persists a reservation row keyed by `workflow_id` in `lemline_gateway_workflows`.

- First valid request for `workflow_id`: starts workflow and returns `ACCEPTED_NEW`
- Same `workflow_id` + same fingerprint: does not restart and returns `ACCEPTED_EXISTING`
- Same `workflow_id` + different fingerprint: rejected with `ALREADY_EXISTS`

Start request fingerprint includes:

1. namespace
2. name
3. version
4. normalized `input_json`
5. `zone_id`

### Watch Replay and Tail

`WatchWorkflow` behavior:

1. Validates scope `lemline.watch`
2. If workflow has not been started yet, waits until reservation exists
3. Replays existing analytics events in `sequence` (`id`) ascending order
4. Polls for new events and streams them
5. Stops when terminal event type is seen:
   - `io.serverlessworkflow.workflow.completed.v1`
   - `io.serverlessworkflow.workflow.faulted.v1`

If you call `WatchWorkflow` again later for the same `workflow_id`, events are replayed again up to completion/failure.

## Security Model

Transport and identity are both enforced:

1. TLS enabled for gRPC server
2. mTLS client authentication required (`client-auth=required`)
3. Bearer JWT required in gRPC metadata header `authorization`
4. Scope checks:
   - `lemline.start` for `StartWorkflow`
   - `lemline.watch` for `WatchWorkflow`
5. Namespace authorization via JWT claim allow-list (supports `*`)

Default claim names:

- scopes claim: `scope`
- namespaces claim: `lemline_namespaces`

## Error Mapping

| Condition | gRPC status |
|---|---|
| Missing/invalid JWT | `UNAUTHENTICATED` |
| Missing scope or namespace access | `PERMISSION_DENIED` |
| Invalid request fields | `INVALID_ARGUMENT` |
| Workflow definition not found | `NOT_FOUND` |
| Idempotency fingerprint mismatch | `ALREADY_EXISTS` |
| Unexpected internal failure | `INTERNAL` |

## Configuration

### Config File Resolution

Gateway resolves configuration in this order:

1. `--config=<path>` or `--config <path>` / `-c <path>`
2. `LEMLINE_CONFIG` environment variable
3. `./.lemline.yaml`
4. `~/.config/lemline/config.yaml`
5. `~/.lemline.yaml`

### Gateway Keys

| Property | Default | Required | Description |
|---|---|---|---|
| `lemline.gateway.enabled` | `true` | no | Enables gateway server startup |
| `lemline.gateway.grpc.host` | `0.0.0.0` | no | gRPC bind host |
| `lemline.gateway.grpc.port` | `9000` | no | gRPC bind port |
| `lemline.gateway.grpc.tls.certificate` | - | yes (if enabled) | Server certificate path |
| `lemline.gateway.grpc.tls.private-key` | - | yes (if enabled) | Server private key path |
| `lemline.gateway.grpc.tls.trust-store` | - | yes (if enabled) | Trust store path for client cert validation |
| `lemline.gateway.grpc.tls.trust-store-password` | - | depends on store | Trust store password |
| `lemline.gateway.grpc.tls.client-auth` | `required` | no | Client cert requirement |
| `lemline.gateway.security.jwt.issuer` | - | yes (if enabled) | Expected JWT issuer |
| `lemline.gateway.security.jwt.jwks-url` | - | yes (if enabled) | JWKS endpoint used to validate JWTs |
| `lemline.gateway.security.claims.scope-field` | `scope` | no | JWT claim name for scopes |
| `lemline.gateway.security.claims.namespaces-field` | `lemline_namespaces` | no | JWT claim name for namespace allow-list |
| `lemline.gateway.watch.poll-interval-ms` | `250` | no | Watch polling interval |
| `lemline.gateway.watch.batch-size` | `256` | no | Max events fetched per watch poll |

### Analytics Source Keys (for Watch replay/tail)

| Property | Default | Description |
|---|---|---|
| `lemline.analytics.backend` | `postgresql` | Analytics backend selector |
| `lemline.analytics.postgresql.host` | `localhost` | PostgreSQL host |
| `lemline.analytics.postgresql.port` | `5432` | PostgreSQL port |
| `lemline.analytics.postgresql.database` | `lemline_analytics` | Database name |
| `lemline.analytics.postgresql.username` | `postgres` | Database user |
| `lemline.analytics.postgresql.password` | `postgres` | Database password |
| `lemline.analytics.postgresql.schema` | `public` | Lifecycle events schema |
| `lemline.analytics.postgresql.table` | `lemline_lifecycle_events` | Lifecycle events table |
| `lemline.analytics.migrate-at-start` | `true` | Analytics migration toggle |
| `lemline.analytics.baseline-on-migrate` | `false` | Flyway baseline toggle |

`clickhouse` is a reserved backend value for future support. Current gateway implementation supports replay/tail with PostgreSQL only.

## Build and Run

Build:

```bash
./gradlew :lemline-gateway:build
```

Run JVM package:

```bash
java -jar lemline-gateway/build/quarkus-app/quarkus-run.jar --config=/path/to/.lemline.yaml
```

Build native:

```bash
./gradlew :lemline-gateway:build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```
