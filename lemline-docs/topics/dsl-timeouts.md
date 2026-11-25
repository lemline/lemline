---
title: Timeouts
---

# Timeouts

A timeout limits how long a workflow or task can run. Define it with an `after` duration
or reuse a named timeout from `use.timeouts`.

## Handle a listen timeout

The `listen` task waits up to 30 seconds for an approval. Leave the event unsent to take the
timeout path. The surrounding `try` handles the timeout and returns an expired result.

<code-block lang="yaml" src="workflows/reference/timeout-listen-fallback.yaml"/>

## Limit a call

This definition sets a one-second timeout for a stock request. The shop delays its response
by two seconds.

<code-block lang="yaml" src="workflows/dsl-timeouts/partner-http-timeout.yaml"/>

> **Lemline**
>
> The worker enforces `listen` timeouts. On HTTP calls, the client’s 30-second
> limit applies and the task’s `timeout` has no effect. The stock request below completes
> despite exceeding its declared timeout. [Issue #96](https://github.com/lemline/lemline/issues/96)
> tracks the HTTP limitation.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f timeout-listen-fallback.yaml
bin/lemline instance start examples timeout-listen-fallback
bin/lemline definition post -f partner-http-timeout.yaml
bin/lemline instance start examples partner-http-timeout
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `timeout-listen-fallback` | With no approval event, returns `{"status":"expired"}` after the timeout. |
| `partner-http-timeout` | Returns the stock for `SKU-1` with `100` available units after the shop’s delay. |

Allow up to 45 seconds for the SQLite worker to pick up the expired listener. Keep the shop running while the stock request is in progress.

```bash
sleep 45
```

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
