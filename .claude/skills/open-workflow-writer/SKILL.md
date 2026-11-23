---
name: open-workflow-writer
description: Author and review Open Workflow DSL 1.0.3 YAML for Lemline from business requirements, including data flow, HTTP calls, routing, retries and event correlation.
---

# Write a Lemline workflow

Produce a complete workflow with a stable document identity. Preserve requested inputs, HTTP methods and endpoints, event types, outputs and failure behavior across revisions. Ask about an integration detail when guessing it would change a side effect. Label assumptions in accompanying text when the output format permits it.

Start with the [intent map](references/12-intent-to-task-map.md) and the [authoring procedure](references/15-generate-from-prompt.md). Use the matching guide when working on:

- [Workflow structure and triggers](references/01-workflow-foundation.md), [data and expressions](references/02-data-and-expressions.md), [branching](references/03-conditions-and-branching.md), [loops and forks](references/04-loops-and-parallelism.md)
- [Service calls](references/05-calls-and-integrations.md), [processes and child workflows](references/06-run-and-processes.md), [events](references/07-events-and-listening.md), [retries and timeouts](references/08-errors-retries-timeouts.md)
- [Secrets and reusable resources](references/09-auth-catalog-extensions.md), [lifecycle events](references/10-lifecycle-events.md), [runtime support](references/14-runtime-profiles.md)

Four [templates](assets/templates/) cover HTTP orchestration, event listeners, batch routing and retries. Their example endpoints and event types belong to the sample contract. Substitute the actual integration details when adapting them.

## Verify the result

From the repository root, use Python with the dependencies in `lemline-docs/validation/requirements.txt`. Run the schema and local flow checks on the authored file:

```sh
python .claude/skills/open-workflow-writer/scripts/validate_workflow.py path/to/workflow.yaml
```

To check the same file with Lemline's JVM reader, place it in a directory containing just complete workflow definitions and pass that directory to the reader test:

```sh
./gradlew :lemline-core:test --tests '*WorkflowExamplesTest' -PworkflowExampleDir=/path/to/workflows
```

Check the [semantic constraints](references/13-semantic-constraints.md) and [acceptance checklist](references/11-authoring-checklist.md). Verify business behavior with representative input and simulated services. The three [scenario prompts](assets/evals/) have executable examples in `WorkflowAuthoringScenarioTest`. Report which checks ran and any requested guarantees the runtime cannot enforce.
