---
title: How to execute tasks in parallel (fork)
---

# How to execute tasks in parallel

Use `fork.branches` to run independent branches concurrently. Each branch has its own
task input. By default, a fork waits for all branches.

<code-block lang="yaml" src="workflows/reference/parallel-order-checks.yaml"/>

## Handle errors around a fork

Use a surrounding `try` task when the workflow needs a fallback for the whole group.
Place a separate `try` inside a branch when that branch can recover independently.

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

## Wait for outcomes published as events

A `listen` task can collect events produced by independent services. In this example,
`all` requires one match for each filter and `timeout.after` bounds the wait.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

Use a [For Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#for) to iterate over a collection at runtime. See
[Fan-Out and Fan-In](lemline-explain-fan-in.md) for the difference between iteration,
forking tasks and waiting for events.
