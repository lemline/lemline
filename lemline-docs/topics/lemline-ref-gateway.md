---
title: gRPC Gateway Reference
---

# gRPC Gateway Reference

This reference documents the current public `lemline-runner-gateway` API: RPC surface, cursor and watch semantics, start idempotency, security rules, and configuration required to run the gateway.

## Overview

`lemline-runner-gateway` is Lemline's public gRPC ingress and read API for:

1. workflow definition catalog access
2. definition-level execution statistics
3. workflow instance start and listing
4. per-instance lifecycle event timelines

The gateway is not started as a standalone module. It is loaded by `lemline-runner` and started with:

```bash
gateway start
```

It shares the same `.lemline.yaml` configuration model as the runner and depends on:

1. runner database configuration for gateway outbox persistence
2. runner messaging configuration to publish workflow start commands
3. analytics configuration for read-side queries and watch streams

## Service Contract

Service: `lemline.gateway.v1.GatewayService`

### Common Conventions

- `List*` RPCs return finite snapshots.
- `Get*` RPCs return exactly one resource.
- `Watch*` RPCs return live streams.
- Cursors are opaque and scoped to one RPC plus the exact filter set that produced them.
- When a paginated list response includes `watch_cursor`, every page from that snapshot returns the same cursor. Consume the final page before using that cursor with the corresponding `Watch*` RPC.
- All time windows use `[from, to)` semantics: inclusive lower bound, exclusive upper bound.
- `page_size = 0` lets the server choose a default page size.

### RPC Surface

| RPC | Request | Response | Current behavior |
|---|---|---|---|
| `ListDefinitions` | `ListDefinitionsRequest` | `ListDefinitionsResponse` | Returns a finite catalog snapshot ordered by `namespace ASC`, `name ASC`, `version DESC`. `latest_only` defaults to `true` when omitted. |
| `GetDefinition` | `GetDefinitionRequest` | `GetDefinitionResponse` | Returns one full definition. If `version` is omitted, the latest version for `namespace/name` is returned. |
| `ListDefinitionStats` | `ListDefinitionStatsRequest` | `ListDefinitionStatsResponse` | Returns finite definition-level counters for a required time window. Includes `watch_cursor` for snapshot-then-tail flows. |
| `WatchDefinitionStats` | `WatchDefinitionStatsRequest` | `stream WatchDefinitionStatsResponse` | Delta stream of `UPSERT` and `REMOVED` changes for definition stats. If `after_cursor` is omitted, the stream starts from "now" and emits only future changes. |
| `StartInstance` | `StartInstanceRequest` | `StartInstanceResponse` | Starts a workflow instance asynchronously. If `instance_id` is omitted, the server generates a UUIDv7 identifier. If `version` is omitted, the latest definition version is started. |
| `GetInstance` | `GetInstanceRequest` | `GetInstanceResponse` | Returns the current instance summary and, when known, a `latest_event_cursor` that can be used to tail events without replaying older ones. |
| `ListInstances` | `ListInstancesRequest` | `ListInstancesResponse` | Returns a finite instance snapshot ordered by `created_at DESC`, `instance_id DESC`. Includes `watch_cursor` for snapshot-then-tail flows. |
| `WatchInstances` | `WatchInstancesRequest` | `stream WatchInstancesResponse` | Delta stream of `UPSERT` and `REMOVED` changes to the filtered instance set. If `after_cursor` is omitted, the stream starts from "now" and emits only future changes. |
| `ListInstanceEvents` | `ListInstanceEventsRequest` | `ListInstanceEventsResponse` | Returns finite ordered history for one instance, in ascending event-cursor order. |
| `WatchInstanceEvents` | `WatchInstanceEventsRequest` | `stream WatchInstanceEventsResponse` | Streams one instance timeline. If `after_cursor` is omitted, the server replays from the first event and then tails live events. |

### StartInstance Request and Response

`StartInstanceRequest`:

| Field | Required | Description |
|---|---|---|
| `namespace` | yes | Target definition namespace |
| `name` | yes | Target definition name |
| `version` | no | Concrete definition version; omitted means latest |
| `instance_id` | no | Caller-supplied UUIDv7 string for idempotent starts |
| `input` | no | Workflow input as `google.protobuf.Value`; omitted behaves as `{}` |
| `zone_id` | no | IANA time zone ID; omitted or blank defaults to `UTC` |

`StartInstanceResponse`:

| Field | Description |
|---|---|
| `instance_id` | Created or reused instance identifier |
| `namespace` | Resolved definition namespace |
| `name` | Resolved definition name |
| `version` | Resolved definition version actually started |
| `result` | `START_INSTANCE_RESULT_ACCEPTED_NEW` or `START_INSTANCE_RESULT_ACCEPTED_EXISTING` |

### Resource Shapes

- `WorkflowDefinition` returns the stored definition `source`, the resolved `version`, and `available_versions` ordered newest first.
- `DefinitionStats` returns `started_count`, `completed_count`, `faulted_count`, and `running_count_at_time_to` for one `namespace/name/version`.
- `InstanceSummary` returns the resolved definition identity, lifecycle status, timestamps, optional `duration`, and optional structured `error`.
- `InstanceEvent` returns an opaque per-instance `cursor`, normalized `event_type`, optional `event_time`, and canonical `cloud_event_json`.

## Execution Semantics

### Start Idempotency

`StartInstance` is idempotent when the caller supplies `instance_id`.

- First accepted request for an `instance_id`: returns `START_INSTANCE_RESULT_ACCEPTED_NEW`
- Same `instance_id` with the same effective start parameters: returns `START_INSTANCE_RESULT_ACCEPTED_EXISTING`
- Same `instance_id` with different effective parameters: rejected with `ALREADY_EXISTS`

Current conflict detection compares:

1. resolved workflow identity (`namespace`, `name`, resolved `version`)
2. normalized workflow input
3. schedule zone when schedule-based persistence is involved

Additional request behavior:

- If `instance_id` is omitted, the gateway generates a UUIDv7 identifier.
- If `input` is omitted, the server behaves as if `{}` was supplied.
- If `input` is present but does not contain a JSON value, the request is rejected.
- If `zone_id` is invalid, the request is rejected with `INVALID_ARGUMENT`.

### List and Watch Pairing

For definition stats and instances, the intended no-gap client pattern is:

1. call the corresponding `List*` RPC
2. consume all pages from that snapshot
3. pass the returned `watch_cursor` into the matching `Watch*` RPC

Current watch semantics differ by resource:

- `WatchDefinitionStats`: delta stream, future-only when `after_cursor` is omitted
- `WatchInstances`: delta stream, future-only when `after_cursor` is omitted
- `WatchInstanceEvents`: replay-first stream, replays from the first event when `after_cursor` is omitted

### Instance Event Access

`GetInstance`, `ListInstanceEvents`, and `WatchInstanceEvents` authorize read access by namespace.

Current implementation resolves that namespace from:

1. the latest instance summary when available
2. otherwise the first lifecycle event for the instance

If neither exists, the gateway returns `NOT_FOUND`.

## Security Model

Transport security and request authentication are configured separately:

1. TLS is controlled by `lemline.gateway.tls.*`
2. client certificate mode is controlled by `lemline.gateway.tls.client-auth` (`none`, `request`, `required`)
3. bearer JWT authentication and authorization are controlled by `lemline.gateway.authentication.*`

Validation and runtime rules:

- `lemline.gateway.authentication.enabled=true` requires `lemline.gateway.tls.enabled=true`
- when TLS is enabled, `certificate` and `private-key` are required
- when client auth is `request` or `required`, `trust-store` is required
- when authentication is enabled, `jwt.issuer` and `jwt.jwks-url` are required
- when authentication is enabled and no authenticated principal is available, the RPC fails with `UNAUTHENTICATED`
- when authentication is disabled, the gateway uses an anonymous wildcard principal and bypasses scope and namespace checks

Current scope requirements:

- `lemline.start` for `StartInstance`
- `lemline.watch` for `WatchDefinitionStats`
- `lemline.watch` for `WatchInstances`
- `lemline.watch` for `WatchInstanceEvents`

Current namespace rules:

- non-streaming read RPCs do not require a scope
- namespace authorization is still enforced for read RPCs when authentication is enabled
- `List*` calls without an explicit namespace are automatically limited to the caller's allowed namespaces
- `*` in the namespaces claim allows access to all namespaces

Default claim names:

- scopes claim: `scope`
- namespaces claim: `lemline_namespaces`

Accepted claim formats for scopes and namespaces:

1. space-separated string
2. comma-separated string
3. array or collection values

## Error Mapping

| Condition | gRPC status |
|---|---|
| Missing authenticated principal when authentication is enabled | `UNAUTHENTICATED` |
| Invalid request fields, invalid UUIDv7, invalid timezone, invalid cursors, or invalid time ranges | `INVALID_ARGUMENT` |
| Missing definition or instance | `NOT_FOUND` |
| Duplicate `instance_id` with conflicting effective parameters | `ALREADY_EXISTS` |
| Missing scope or namespace authorization | `PERMISSION_DENIED` |
| Unhandled internal failure | `INTERNAL` |

## Configuration

### Config File Resolution

Gateway startup uses the same config resolution order as `lemline-runner`:

1. `--config=<path>` or `-c <path>`
2. `LEMLINE_CONFIG`
3. `./.lemline.yaml`
4. `~/.config/lemline/config.yaml`
5. `~/.lemline.yaml`

### Gateway Keys

| Property | Default | Required | Description |
|---|---|---|---|
| `lemline.gateway.enabled` | `false` | no | Enables gateway server startup |
| `lemline.gateway.grpc.host` | `0.0.0.0` | no | gRPC bind host |
| `lemline.gateway.grpc.port` | `9000` | no | gRPC bind port |
| `lemline.gateway.cors.enabled` | `true` | no | Enables CORS for browser and gRPC-Web clients |
| `lemline.gateway.cors.origins` | `http://localhost:5173` | no | Allowed CORS origins |
| `lemline.gateway.cors.methods` | `GET,POST,OPTIONS` | no | Allowed CORS methods |
| `lemline.gateway.cors.headers` | `Accept,Authorization,Content-Type,Grpc-Timeout,X-Grpc-Web,X-User-Agent` | no | Allowed CORS request headers |
| `lemline.gateway.tls.enabled` | `true` | no | Enables TLS for the gRPC server |
| `lemline.gateway.tls.certificate` | - | yes if `tls.enabled=true` | Server certificate path |
| `lemline.gateway.tls.private-key` | - | yes if `tls.enabled=true` | Server private key path |
| `lemline.gateway.tls.client-auth` | `none` | no | mTLS mode: `none`, `request`, or `required` |
| `lemline.gateway.tls.trust-store` | - | yes if `client-auth` is `request` or `required` | Trust store used for client certificate validation |
| `lemline.gateway.tls.trust-store-password` | - | depends on store | Trust store password |
| `lemline.gateway.authentication.enabled` | `true` | no | Enables JWT authentication and authorization |
| `lemline.gateway.authentication.jwt.issuer` | - | yes if `authentication.enabled=true` | Expected JWT issuer |
| `lemline.gateway.authentication.jwt.jwks-url` | - | yes if `authentication.enabled=true` | JWKS URL used for JWT validation |
| `lemline.gateway.authentication.claims.scope-field` | `scope` | no | JWT claim name for scopes |
| `lemline.gateway.authentication.claims.namespaces-field` | `lemline_namespaces` | no | JWT claim name for allowed namespaces |
| `lemline.gateway.watch.poll-interval-ms` | `250` | no | Watch polling interval in milliseconds |
| `lemline.gateway.watch.batch-size` | `256` | no | Maximum rows fetched per watch poll |

Notes:

1. `gateway start` sets `lemline.gateway.enabled=true` automatically; keeping it in config is still fine.
2. Authentication cannot be enabled on plaintext transport.

### Analytics Keys for Read APIs

Definition stats, instance reads, and instance-event timelines are backed by the analytics lifecycle-event store.

Current gateway read-side implementations support:

- `h2`
- `postgresql`

| Property | Default | Description |
|---|---|---|
| `lemline.analytics.type` | `h2` | Analytics backend selector |
| `lemline.analytics.migrate-at-start` | `true` | Analytics migration toggle |
| `lemline.analytics.baseline-on-migrate` | `false` | Analytics Flyway baseline toggle |
| `lemline.analytics.postgresql.host` | `localhost` | PostgreSQL host |
| `lemline.analytics.postgresql.port` | `5432` | PostgreSQL port |
| `lemline.analytics.postgresql.database` | `lemline` | PostgreSQL database name |
| `lemline.analytics.postgresql.username` | `postgres` | PostgreSQL user |
| `lemline.analytics.postgresql.password` | `postgres` | PostgreSQL password |
| `lemline.analytics.postgresql.schema` | `lemline` | Lifecycle-events schema |

Operational note:

- the analytics datasource is provisioned when either the gateway or the lifecycle-events consumer is enabled
- if the analytics datasource cannot be resolved, gateway startup and read APIs fail validation
- for PostgreSQL analytics, configure `lemline.analytics.type=postgresql` and the `lemline.analytics.postgresql.*` settings

### Minimal Local Development Example

Use this only on trusted local networks:

```yaml
lemline:
  gateway:
    enabled: true
    grpc:
      host: 127.0.0.1
      port: 9000
    cors:
      enabled: true
      origins: http://localhost:5173
    tls:
      enabled: false
    authentication:
      enabled: false

  analytics:
    type: postgresql
    migrate-at-start: true
    baseline-on-migrate: false
    postgresql:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
      password: postgres
      schema: lemline
```

## Build and Run

Build:

```bash
./gradlew :lemline-runner:build
```

Run the JVM package:

```bash
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar --config=/absolute/path/.lemline.yaml gateway start
```

Build a native package:

```bash
./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

Run the native binary:

```bash
./lemline-runner/build/lemline-runner-*-runner --config=/absolute/path/.lemline.yaml gateway start
```
