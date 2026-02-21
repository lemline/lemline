# [ADR-0014] Public Secured gRPC Gateway for Lemline

## Status

Proposed

## Context

Lemline currently exposes runtime capabilities through CLI and broker-driven processing. This is strong for internal
operations but not ideal for external, polyglot client integration.

The gateway must provide:

1. A language-neutral API to start workflows.
2. A stream API to retrieve lifecycle analytics events for a workflow instance.
3. Strict idempotent behavior for `workflow_id` so repeat requests never restart a completed or running instance.
4. Strong security at transport and application layers.
5. Compatibility with existing Lemline config, messaging, and analytics architecture.

## Decision

We introduce a new Quarkus module, `lemline-gateway`, as a public API boundary with a secured gRPC server and native
image support.

### API Shape

Expose two RPCs:

1. `StartWorkflow` (unary, fire-and-forget semantics).
2. `WatchWorkflow` (server stream, replay then live tail until terminal event).

This separates intent cleanly:

1. Start can be used independently by async clients.
2. Streaming can be consumed independently by observer clients.

### Security Model

Use layered authentication and authorization:

1. mTLS for workload identity at transport level.
2. JWT validation using OIDC/JWKS for caller identity.
3. Claim-based authorization enforcing:
   - action scopes (`lemline.start`, `lemline.watch`)
   - namespace authorization claim (allow-list, wildcard supported)

### Idempotency and Replay

To guarantee that repeated starts with the same `workflow_id` do not restart workflows:

1. Add a dedicated idempotency table in the main Lemline database (`lemline_gateway_workflows`).
2. Persist first accepted start request fingerprint (`workflow_id`, namespace, name, version, input, zone).
3. Reject mismatched repeats for same `workflow_id` with `ALREADY_EXISTS`.

For replay and tail:

1. Introduce a pluggable analytics event source selected by `lemline.analytics.backend`.
2. Support `postgresql` in v1, reusing `lemline_lifecycle_events` as canonical event source.
3. Reserve `clickhouse` as a future backend without changing the public gRPC contract.
4. Stream events ordered by analytics sequence (`id ASC`).
5. End stream on workflow terminal lifecycle event:
   - `io.serverlessworkflow.workflow.completed.v1`
   - `io.serverlessworkflow.workflow.faulted.v1`

### Configuration Strategy

`lemline-gateway` uses the same config discovery and loading model as `lemline-runner` (`.lemline.yaml` and related
lookup order), with a dedicated `lemline.gateway.*` subset for gRPC endpoint and security settings.

### Runtime Constraints

1. `version` is required in start requests.
2. `zone_id` is optional and defaults to `UTC`.
3. `WatchWorkflow` request includes `workflow_id` only.
4. If watch starts before workflow start, stream waits for external start.
5. No server-side default timeout for watch streams.
6. No dedicated scheduling RPC in v1.
7. Gateway startup fails fast if analytics replay prerequisites are unavailable.

## Consequences

### Positive

1. Strong polyglot integration story with a stable public API.
2. Explicit and verifiable idempotency semantics at instance level.
3. Deterministic replay/tail behavior from existing analytics architecture.
4. Security posture suitable for untrusted client/server environments.
5. Clear separation between public API contracts and internal transport protobuf.

### Negative

1. Additional module and operational surface to maintain.
2. Added persistence responsibilities (idempotency table and migrations across databases).
3. Watch request with `workflow_id` only delays namespace auth decision until start metadata exists.
4. Gateway availability becomes coupled to analytics availability by design.

### Neutral

1. Existing runner command/event channels remain unchanged.
2. Existing internal protobuf (`lemline-messages-proto`) remains internal and unaffected.

## Alternatives Considered

1. Reusing internal transport protobuf as public API contract.
   - Rejected: internal schemas optimize runtime transport, not external API stability.

2. Single streaming RPC that both starts and streams.
   - Rejected: conflates start and observe concerns; weaker fire-and-forget ergonomics.

3. Three RPC model (`Start`, `Replay`, `Watch`).
   - Rejected: larger surface without material v1 value.

4. REST-first gateway.
   - Rejected: weaker fit for long-lived event streams and strongly typed cross-language contracts.

5. No dedicated idempotency table (analytics-only inference).
   - Rejected: race windows before first lifecycle event can violate no-restart guarantee.

## References

1. `docs/roadmap/grpc-gateway-secured-v1.md`
2. `docs/adr/0003-messaging-architecture.md`
3. `docs/adr/0007-config-strategy.md`
4. `docs/roadmap/lifecycle-events-analytics-postgres.md`
