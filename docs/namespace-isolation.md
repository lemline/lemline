# Namespace Isolation — Operations Guide

Lemline routes every workflow message to a broker destination derived from the workflow's
namespace, and every runner declares the namespaces it follows. This guide covers the resulting
broker topology and the operational procedures it requires. Design rationale:
[ADR-0016](adr/0016-namespace-isolation.md).

## Model in one paragraph

Destinations are named `<prefix>-<namespace>-<type>` with `prefix = lemline.messaging.prefix`
(default `lemline`) and `type ∈ {commands, events, cloudevents}` — e.g. `lemline-acme-commands`.
Producers need no routing configuration: the destination is a pure function of the message's
namespace. A runner consumes only the destinations of the namespaces listed in
`lemline.namespaces` (absent ⇒ the single namespace `default`). Lifecycle events keep one shared
destination (`lemline-lifecycle-events`): they feed the central analytics sink.

## Configuration

```yaml
lemline:
  namespaces: acme-prod, acme-staging   # namespaces this runner follows
  messaging:
    prefix: lemline                     # naming convention prefix (default)
```

- Validation at startup fails on: empty list, duplicates, invalid characters
  (`[a-z0-9]([a-z0-9-]*[a-z0-9])?`), wildcards.
- A WARN is logged when one followed namespace is a dash-prefix of another (`acme` vs
  `acme-prod`): a broker prefix ACL on `lemline-acme-*` would also cover `acme-prod`'s
  destinations. This check only sees the local runner — run the cross-deployment check below when
  provisioning ACLs.
- On the **gateway**, `lemline.namespaces` is optional: absent, the gateway serves all namespaces;
  set, it pins the gateway (JWT claim ∩ pin, enforced even with authentication disabled).

## Topology per broker

### Kafka

- Consumers subscribe to the topic list of their followed namespaces on one channel, in a shared
  consumer group with the cooperative-sticky assignor. All pools share one rebalance domain: use
  static membership (`group.instance.id`) for pools with distinct deploy cadences.
- Failed records are dead-lettered to `<source-topic>.dlq` (custom `lemline-namespace-dlq`
  strategy).
- Topics: pre-create `lemline-<ns>-{commands,events,cloudevents}` and their `.dlq` variants with
  your partitioning/retention policy, or rely on broker auto-create.
- ACLs: one prefixed ACL per namespace (`lemline-<ns>-` prefix) covers consume; producers
  performing cross-namespace `Run: Workflow` also need write on the called namespaces' prefixes.

### RabbitMQ

- Producers publish to the per-channel topic exchange `lemline-commands` / `lemline-events` /
  `lemline-cloudevents` with routing key = namespace.
- At startup, `RabbitTopologyBootstrap` declares, for each followed namespace: a durable topic
  exchange `lemline-<ns>-<type>`, an exchange-to-exchange binding from the channel exchange
  (routing key `<ns>`), and binds the pool queue to it.
- The pool queue is named `<prefix>-<type>.<sorted-namespaces-dot-joined>`
  (e.g. `lemline-commands.acme.team`), overridable via `lemline.messaging.rabbitmq.<channel>.queue`.
- **Constraint**: two pools with different namespace sets must be disjoint or identical —
  overlapping-but-different sets receive duplicate deliveries (queue-per-pool model).
- ACLs: name-based permissions on `lemline-<ns>-*` (exchanges) for consume-side isolation; use
  RabbitMQ *topic permissions* on the channel exchanges to restrict which routing keys (namespaces)
  a producer may publish.

#### Scope-change runbook (RabbitMQ only)

Editing `lemline.namespaces` changes the pool queue name; the old durable queue keeps its bindings
and backlog (which contains workflow state). Procedure:

1. Stop the pool.
2. Unbind the old queue from the namespace exchanges.
3. Shovel its backlog to the new queue(s) (or let a temporary consumer drain it).
4. Delete the old queue.
5. Start the pool with the new `lemline.namespaces`.

Kafka and PGMQ are immune: their destinations are per namespace, not per pool.

### PGMQ

- One pgmq queue (a `pgmq.q_*` table pair) per namespace and type, plus `.dlq` variants; created
  idempotently by the connector (`pgmq.create`), or pre-create them explicitly.
- The connector polls the queue list per channel and dead-letters to `<source-queue>.dlq`.
- Isolation can be hardened with per-namespace PostgreSQL grants on the queue tables.

## Required hardening: broker ACLs

Without broker ACLs, isolation is purely conventional: any credential can subscribe to and publish
into any namespace's destinations (worse than the shared-database caveat — injection, not just
reads). Configure per-namespace ACLs whenever namespaces don't trust each other.

Prefix-collision check at ACL provisioning time (a namespace that is a dash-prefix of another
makes prefix ACLs leak): collect `lemline.namespaces` from every deployment and verify no
namespace is a `-`-prefix of another.

## External CloudEvents

External producers publish to `lemline-<namespace>-cloudevents` — the event producer belongs to
the namespace owner. Correlation matching is scoped to the arrival namespace: an event published
for `a` never wakes a listener of `b`. An event relevant to several namespaces must be published
to each destination (no implicit broadcast). `Emit` tasks publish to the emitting workflow's
namespace destination.

## Diagnosing a stuck instance (orphan namespace)

A workflow started in a namespace no runner follows stays **pending** — durable, never lost, but
blocked until a runner is assigned. Signals:

1. Runner startup logs `Following namespaces: [...]` — check a runner follows the namespace.
2. The gateway WARNs at start time and increments
   `lemline.gateway.start.inactive_namespace.total{namespace=...}` when the target namespace has
   no recorded runner activity.
3. Broker-side: queue depth / consumer lag on `lemline-<ns>-*` destinations (the per-namespace
   prefix makes the alert rule trivial).
4. Dashboard: filter instances with status `PENDING` and an old `created_at` — a namespace whose
   instances never leave `PENDING` has no runner.

Fix: deploy or reconfigure a runner with the namespace in `lemline.namespaces`; the backlog drains.

## Misrouted (foreign-namespace) messages

A runner that receives a message of a namespace it does not follow (binding/config anomaly) parks
it: WARN log, `…foreign_namespace.total` counter, row in the failures table with reason
`foreign_namespace` (the payload is the only copy of the instance's state), then ACK. After fixing
the bindings, re-emit with:

```bash
lemline failures replay            # replays all foreign_namespace rows (with confirmation)
lemline failures replay --id <id>  # replays one row
lemline failures replay --dry-run  # lists what would be replayed
```

Routing being a pure function of the message, replay is safe: each payload goes back to the
destination of its own namespace.
