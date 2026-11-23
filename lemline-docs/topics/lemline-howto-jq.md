---
title: How to write jq runtime expressions
---

# How to use jq in workflow expressions

Write expressions as `${ ... }`. Start with current task input `.`, move through
fields with jq, and use pipes to break a transformation into steps.

## Read and transform input

The following workflow builds an output from an array of order lines. The fallback
after `add` handles an empty array.

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

## Keep data between tasks

Use `$context` for earlier values exported by a task and `$workflow.input` for the
input supplied when the instance started.

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

## Check an expression with local data

Use jq on a small sample with the shape you expect the task to receive. Pass the
expression inside `${ ... }` to jq as its filter.

```bash
printf '%s\n' '{"items":[{"quantity":2,"price":12.5}]}' |
  jq '[.items[] | .quantity * .price] | add // 0'
```

See [Runtime Expressions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl.md#runtime-expressions) for evaluation scopes and
[Debugging Workflows](lemline-howto-debug.md) for logging task input.
