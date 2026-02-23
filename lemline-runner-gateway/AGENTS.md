<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-22 -->

# lemline-runner-gateway

gRPC ingress gateway for starting workflows and streaming workflow lifecycle analytics.

## Key Files

| File | Description |
|------|-------------|
| `grpc/WorkflowGatewayGrpcService.kt` | gRPC service implementation (`startWorkflow`, `watchWorkflow`) |
| `start/WorkflowStartService.kt` | Start workflow flow + idempotency/conflict semantics |
| `watch/WorkflowWatchService.kt` | Long-poll watch loop that streams analytics events |
| `auth/GatewayAuthInterceptor.kt` | JWT Bearer auth interceptor for gRPC requests |
| `auth/GatewayAuthorizer.kt` | Scope + namespace authorization checks |
| `outbox/GatewayCommandOutboxRepository.kt` | Gateway outbox repository and workflow-id lookups |
| `outbox/GatewayCommandOutbox.kt` | Outbox processor for start command dispatch |
| `analytics/WorkflowAnalyticsEventSourceSelector.kt` | Analytics backend selection (PostgreSQL/ClickHouse) |
| `analytics/PostgresqlWorkflowAnalyticsEventSource.kt` | Analytics event source backed by PostgreSQL |
| `../lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfigSource.kt` | Global config source mapping `lemline.*` into Quarkus properties (including gateway keys) |
| `config/GatewayStartupValidator.kt` | Required config checks + analytics source validation |
| `src/main/proto/lemline/gateway/v1/workflow_gateway.proto` | Public gateway gRPC contract |
| `src/main/resources/db/migration/{postgresql,mysql,h2}/` | Gateway outbox migrations (all supported DBs) |

## For AI Agents

### Working In This Directory
- Gateway start path must stay idempotent: insert outbox/schedule atomically and resolve duplicates correctly.
- Gateway watch path depends on analytics backend; PostgreSQL is implemented, ClickHouse is not yet implemented.
- Authentication is JWT-based via gRPC metadata; authorization requires both scope and namespace checks.
- Keep `lemline.gateway.*` and `lemline.analytics.*` config keys stable (consumed by `LemlineConfigSource`).
- This module uses Quarkus plugin primarily for gRPC code generation; do not re-enable full Quarkus app tasks here.

### Testing
```bash
./gradlew :lemline-runner-gateway:test
```

### Dependencies
- Internal: `lemline-common`, `lemline-core`, `lemline-runner-common`, `lemline-runner-definitions`, `lemline-runner-schedules`
- External: Quarkus gRPC, JWT, Flyway, PostgreSQL/MySQL JDBC, Micrometer, protobuf-kotlin

<!-- MANUAL: -->
