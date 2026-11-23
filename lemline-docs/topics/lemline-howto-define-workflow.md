---
title: How to define a workflow
---

# How to define a workflow

Start with a YAML file containing a `document` section and a `do` list.

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

## Identify the workflow

The `document` section contains four required fields:

| Field | Purpose |
| --- | --- |
| `dsl` | Open Workflow DSL version, such as `1.0.0` |
| `namespace` | Namespace the runner serves |
| `name` | Workflow name within that namespace |
| `version` | Semantic version of this workflow definition |

The combination of namespace, name and version identifies a definition. Use the optional `title`
and `summary` fields for human-readable descriptions.

## Define and connect tasks

Write one named task per entry in `do`. Tasks run in list order by default. Add `then` to
jump to a named task or end execution. A `switch` lists named cases with conditions and
flow directives. A final case without `when` provides a fallback.

## Validate and transform data

Use `input.schema.document` to declare a JSON Schema and `output.as` to shape the final
result. Expressions delimited by `${ ... }` use jq. The task input is `.` and the original
workflow input is available as `$workflow.input`.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Reuse workflow resources

Use the `use` section for named functions, errors, authentication policies and retry
policies. The following workflow declares a reusable retry policy and selects it in
`catch.retry`.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

See [Publish and Run a Workflow](lemline-howto-publish-run.md) to register and start
your definition, or [DSL Syntax Reference](lemline-ref-dsl-syntax.md) for the main
workflow sections and task types.
