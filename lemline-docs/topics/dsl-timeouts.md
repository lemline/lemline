---
title: Timeouts
---

# Timeouts

Open Workflow represents a timeout with an `after` duration. A task can declare the
duration inline or reference a timeout declared in `use.timeouts`.

## Handle a listen timeout

Lemline enforces listen timeouts. In this example, if an approval event fails to arrive
within 30 seconds, the surrounding `try` task handles the timeout.

<code-block lang="yaml" src="workflows/reference/timeout-listen-fallback.yaml"/>

See [Supported Features](lemline-ref-supported-features.md) for timeout enforcement at
the workflow level and on other task types.
