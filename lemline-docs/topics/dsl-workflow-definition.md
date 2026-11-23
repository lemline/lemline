---
title: Workflow Definition Structure
---

# Workflow Definition Structure

A workflow is a YAML or JSON object. `document` identifies it and `do` lists its
named tasks.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Top-Level Properties

| Property | Purpose |
| --- | --- |
| `document` | Required `dsl`, `namespace`, `name`, and `version`. Optional titles, summaries and metadata also belong here. |
| `do` | Required list of named tasks. |
| `input` | Validate the supplied data or transform it before the first task. |
| `output` | Transform or validate the result. |
| `use` | Declare shared resources, including retry policies, authentication and extensions. |
| `schedule` | Start instances on a timer or when an event matches. |
| `evaluate` | Set expression evaluation options. |
| `timeout` | Describe a workflow timeout in the DSL. See [Timeouts](dsl-timeouts.md) for runtime enforcement. |

## Schedule workflows

Use `schedule.cron` for time-driven instances or `schedule.on` for event-driven ones.

<code-block lang="yaml" src="workflows/reference/scheduled-processing.yaml"/>

<code-block lang="yaml" src="workflows/reference/order-event-schedule.yaml"/>

## Share resources

Use named resources when multiple tasks need the same policy or function. See
[Extensions](dsl-use-extensions.md) for the `use.extensions` section.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>
