---
title: How to run loops (for, while)
---

# How to run loops (for, while)

Use a `for` task to iterate over an array. The `in` expression selects the collection,
`each` binds an item and `at` binds its index. The body is a nested `do` list.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

## Collect a result across iterations

Lemline passes one iteration's result into the next iteration. Initialize an
accumulator, then update it in the body. The last iteration's result becomes the
loop result. Here the body adds a line total for each input item.

<code-block lang="yaml" src="workflows/reference/collect-order-lines.yaml"/>

## Stop a loop early

Add a `while` condition to the same task when the loop should stop before the
collection is exhausted. See the [For Task](dsl-task-for.md) for the condition and
its scope. Use a [fork](lemline-howto-parallel.md) for independent concurrent branches.
