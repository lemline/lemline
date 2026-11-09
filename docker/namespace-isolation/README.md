# Namespace Isolation — Multi-pool E2E Scenario

A manual/nightly end-to-end scenario proving namespace isolation across **separate processes**:
an unpinned gateway, one runner pool per namespace (`team-a`, `team-b`), a shared PostgreSQL
(database + PGMQ broker), and workflow fixtures covering plain execution, cross-namespace
`Run: Workflow`, external CloudEvents and orphan namespaces.

PGMQ is used on purpose (the plan originally said Kafka): it needs no extra container and every
broker destination is a PostgreSQL table — the whole demo can be verified with `psql`.
Model reference: [ADR-0016](../../docs/adr/0016-namespace-isolation.md), ops guide:
[docs/namespace-isolation.md](../../docs/namespace-isolation.md).

## Topology

| Service | `lemline.namespaces` | Consumes |
|---|---|---|
| runner-a | `team-a` | `lemline-team-a-{commands,events,cloudevents}` |
| runner-b | `team-b` | `lemline-team-b-{commands,events,cloudevents}` |
| gateway | *(unset — unpinned)* | nothing (emissions routed by convention; analytics sink) |

## Start

```bash
cd docker/namespace-isolation
docker compose up -d --build
docker compose logs runner-a | grep "Following namespaces"   # → [team-a]
docker compose logs runner-b | grep "Following namespaces"   # → [team-b]
```

Definitions for both namespaces are seeded automatically (`seed-definitions`).

Helper for the demos below:

```bash
alias lemline-a='docker compose run --rm --no-deps -v ./config/runner-a.yaml:/config/lemline.yaml:ro runner-a --config=/config/lemline.yaml'
alias psql-lemline='docker compose exec postgres psql -U postgres -d lemline'
```

## Demo 1 — each pool only executes its namespace

```bash
lemline-a instance start team-a hello
lemline-a instance start team-b hello
```

- `docker compose logs runner-a` shows the `team-a/hello` execution; runner-b's logs show
  `team-b/hello` — and neither runner ever logs the other namespace.
- Broker-side proof — messages transited through per-namespace queues only:

```sql
-- psql-lemline
SELECT queue_name FROM pgmq.list_queues() ORDER BY queue_name;
-- → lemline-team-a-commands, lemline-team-a-events, …, lemline-team-b-commands, …
```

## Demo 2 — cross-namespace Run: Workflow

```bash
lemline-a instance start team-a run-child
```

runner-a executes the parent (`team-a/run-child`), runner-b executes the child (`team-b/child`),
then runner-a resumes and completes the parent. Two processes, one workflow — check both logs.

## Demo 3 — external CloudEvent, scoped to its arrival namespace

```bash
lemline-a instance start team-a wait-for-event
```

The instance blocks on `listen`. The "external producer" publishes directly to team-a's
cloudevents destination (this is exactly what a team-owned event source would do):

```sql
-- psql-lemline
-- NOTE: the payload must be a jsonb STRING (the connector stores/reads JSON-encoded strings),
-- hence to_jsonb(...::text) — a bare '{"…"}'::jsonb object would not be readable.
SELECT pgmq.send('lemline-team-a-cloudevents', to_jsonb('{
  "specversion": "1.0",
  "id": "demo-1",
  "source": "https://acme.example/orders",
  "type": "com.acme.order.placed",
  "data": {"orderId": 42}
}'::text));
```

runner-a correlates the event and the instance completes. Publishing the same event to
`lemline-team-b-cloudevents` instead would never wake this listener: correlation is scoped to
the arrival namespace.

## Demo 4 — orphan namespace (loud, not lost)

```bash
docker compose stop runner-b
lemline-a instance start team-b hello
```

Nothing consumes team-b anymore: the message waits durably —

```sql
SELECT * FROM pgmq.metrics('lemline-team-b-commands');   -- queue_length ≥ 1
```

`docker compose start runner-b` → the backlog drains and the instance completes.
(Starting through the gateway instead would also log the
`inactive namespace` WARN and increment `lemline.gateway.start.inactive_namespace.total`.)

## Demo 5 — foreign message: parked, counted, replayable

Simulate a binding/config mistake by injecting a team-b payload into team-a's queue: run
Demo 4's stop again (`docker compose stop runner-b`), start `team-b hello` (message waits in
`lemline-team-b-commands`), then move it by hand:

```sql
-- psql-lemline: pop the waiting message (read + delete) and re-send it to the WRONG queue
SELECT pgmq.send('lemline-team-a-commands', (pgmq.pop('lemline-team-b-commands')).message);
```

runner-a logs `Foreign namespace 'team-b' received…`, increments
`…foreign_namespace.total`, parks the payload in the failures table and ACKs:

```sql
SELECT error_reason, workflow_namespace FROM lemline_failures;   -- foreign_namespace
```

Recovery once the "binding" is fixed (runner-b back up):

```bash
docker compose start runner-b
lemline-a failures replay --yes    # re-emits to lemline-team-b-commands, deletes the row
```

## Teardown

```bash
docker compose down -v
```
