# [ADR-0016] Namespace Isolation

## Status

Accepted

## Context

Lemline needed a way to restrict a runner, by configuration, to a subset of workflows: a runner
should only consume, execute and persist the workflows it is responsible for, and must never read
or process another team's (tenant's) workflow state. A deployment must be able to mix runners
dedicated to different namespace sets.

Three design iterations led to this decision (2026-07-07/08):

1. A "tenant" concept (named sets of namespaces, per-tenant destinations, routing config shared by
   all emitters) was rejected: it invented vocabulary the DSL already has (`document.namespace`),
   and required synchronizing a tenant registry across every producer and consumer.
2. Shared channels with consume-side filtering were rejected on correctness grounds: with
   competing consumers, a runner that receives a foreign message can neither ACK it (the message
   is lost for its real owner) nor NACK it (redelivery loop / DLQ).
3. The "catch-all" runner (consuming every namespace by default) was dropped as a product
   decision: every runner now declares the namespaces it follows, making isolation the base
   execution model instead of an opt-in mode.

## Decision

1. **Every runner declares `lemline.namespaces`** (list; absent ⇒ the single namespace `default`,
   preserving the zero-config experience — the DSL makes `document.namespace` mandatory and the
   examples use `default`). The property's absence is meaningful: on the gateway it means "serve
   all namespaces" (no pin).

2. **One broker destination per namespace, by pure convention**: `<prefix>-<namespace>-<type>`
   with `prefix = lemline.messaging.prefix` (default `lemline`) and
   `type ∈ {commands, events, cloudevents}` (e.g. `lemline-acme-commands`). Producers route each
   message from the namespace carried by `WorkflowInfoProto` — a pure function of the message,
   zero producer configuration, no producer/consumer synchronization. Namespace-first naming
   groups all destinations of a namespace under one prefix, enabling per-namespace broker ACLs
   (Kafka prefixed ACLs; RabbitMQ name-based permissions on the per-namespace exchanges).
   Per-channel destination-name properties were removed (except the RabbitMQ per-pool queue-name
   override). Lifecycle events keep a single shared destination: they are telemetry flowing to a
   central trusted sink (analytics), not workflow state consumed by scoped runners.

3. **Per-broker realization**:
   - *Kafka*: consumers subscribe to the topic list of their followed namespaces on one channel,
     with a shared consumer group and the cooperative-sticky assignor (the shared group is a
     single rebalance domain). Dead-lettering uses a custom failure strategy
     (`lemline-namespace-dlq`) routing each failed record to `<source-topic>.dlq`, because the
     stock strategy supports only one static DLQ topic per channel.
   - *RabbitMQ*: producers publish to the per-channel topic exchange `<prefix>-<type>` with
     routing key = namespace. `RabbitTopologyBootstrap` declares one topic exchange per namespace
     (`<prefix>-<namespace>-<type>`), an exchange-to-exchange binding from the channel exchange,
     and binds the per-pool queue (`<prefix>-<type>.<sorted-namespaces>`) to the followed
     namespaces' exchanges. Constraint: two pools with different namespace sets must be disjoint
     or identical (queue-per-pool model). Producer-side anti-spoofing relies on RabbitMQ topic
     permissions on the channel exchange.
   - *PGMQ*: one pgmq queue (= table pair) per namespace; the connector polls a queue list per
     channel, dead-letters to `<source-queue>.dlq`, and creates queues idempotently.

4. **Defense in depth (never silent)**: a message delivered to a runner whose namespace is not
   followed (broker binding/config anomaly) is counted (`…foreign_namespace.total`), logged WARN,
   persisted to the failures table with reason `foreign_namespace` — it is the only copy of the
   instance's state — and ACKed. `lemline failures replay` re-emits parked payloads to the
   destination derived from their namespace once bindings are fixed. Never re-emitted
   automatically, never NACK-looped.

5. **Database isolation is behavioral**: the shared outbox/cleaner polling and the listeners
   module add `workflow_namespace IN (…)` predicates bound to the followed namespaces (existing
   `FOR UPDATE SKIP LOCKED` dialect orderings untouched; no new indexes in v1 — outbox tables are
   transient and small). The gateway outbox uses the pin (unpinned gateway relays all namespaces).
   Rows remain readable by a compromised runner; deployments without mutual trust use separate
   databases (already supported by per-runner config).

6. **Gateway pin**: `lemline.namespaces` on the gateway intersects the JWT namespaces claim
   (`claim ∩ pin`; a `"*"` claim grants the full pin). The pin is enforced even with
   authentication disabled, including for the unauthenticated principal.

7. **CloudEvents are per-namespace like everything else**: external producers publish to
   `<prefix>-<namespace>-cloudevents` (the event producer belongs to the namespace owner, who
   already owns their broker topology). Correlation matching is scoped to the event's **arrival**
   namespace, so an event published for namespace `a` never wakes an identical listener of `b`,
   even on a runner following both. Implicit broadcast disappears: an event relevant to several
   namespaces is published to each destination explicitly. `Emit` tasks publish to the emitting
   workflow's namespace destination.

8. **Cross-namespace `Run: Workflow` is allowed**: the child's start command is routed to the
   child namespace's destination and the parent's resume back to the parent's. Calling a
   namespace means trusting it. If no runner follows the child's namespace, the child stays
   pending — visible, not lost.

9. **Orphan namespaces are loud**: runners log their followed namespaces at startup; the gateway
   WARNs and increments `lemline.gateway.start.inactive_namespace.total` when a start targets a
   namespace with no recorded runner activity; broker queue depth/lag per namespace destination
   is the standard alerting hook.

## Consequences

### Positive

- Isolation is structural (destinations), not defended by filters; a scoped runner never even
  receives foreign messages under correct bindings.
- No routing configuration and no producer/consumer synchronization: destination is a pure
  function of the message.
- Per-namespace broker ACLs and per-namespace observability (lag, depth, DLQ) with one prefix rule.
- The spec's vocabulary (`namespace`) is the only isolation concept.

### Negative

- Namespace assignment becomes an explicit ops responsibility; a workflow started in an
  unfollowed namespace stays pending until a runner is assigned (durable, diagnosable, not
  auto-repairable).
- Event broadcast across namespaces requires explicit fan-out by the producer.
- Destination cardinality is bound to namespace cardinality: the namespace is a unit of
  deployment, not a high-cardinality data partition key.
- RabbitMQ pools: "disjoint or identical namespace sets" is documented, not enforceable in code;
  editing a pool's namespace set changes its deterministic queue name (scope-change runbook:
  stop the pool, unbind, shovel the backlog, delete the old queue — Kafka/PGMQ are immune).
- Without broker ACLs, broker-level isolation is purely conventional (any credential can read
  and inject anywhere); ACLs are a required hardening step when namespaces don't trust each other.

## Out of scope (v1)

Per-namespace quotas, encryption, fine-grained RBAC beyond the namespaces claim, Lemline-managed
per-namespace database credentials/schemas, per-namespace analytics stores, automatic CloudEvent
fan-out, `Emit` targeting another namespace, namespace lifecycle management APIs, cross-namespace
Run deny policies, migration from the pre-1.0 shared-topology (project in development).

## Related

- Implementation plan: `docs/plans/namespace-isolation.md`
- ADR-0003 (messaging architecture), ADR-0007 (config strategy), ADR-0014 (gRPC gateway)
