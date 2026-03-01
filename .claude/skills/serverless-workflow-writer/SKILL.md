---
name: serverless-workflow-writer
description: Write, review, and refactor workflow definitions that conform to Serverless Workflow Specification v1.0.0. Use when authoring workflow.yaml files, decomposing business logic into task types (call, do, fork, emit, for, listen, raise, run, set, switch, try, wait), defining data flow transformations, or validating constraints from the official DSL reference and workflow schema.
---

# Serverless Workflow Writer

## Scope

Pin all guidance in this skill to Serverless Workflow spec tag `v1.0.0`.

Authoritative sources:

- `https://raw.githubusercontent.com/serverlessworkflow/specification/v1.0.0/dsl.md`
- `https://raw.githubusercontent.com/serverlessworkflow/specification/v1.0.0/dsl-reference.md`
- `https://raw.githubusercontent.com/serverlessworkflow/specification/v1.0.0/schema/workflow.yaml`

Do not mix rules from newer or older tags unless the user explicitly asks for migration guidance.

## One-Shot Output Contract

When asked to produce a workflow, return exactly one complete workflow definition in YAML.

Mandatory constraints:

- Output one `workflow.yaml` only.
- Include `document` and `do`.
- Set `document.dsl: "1.0.0"`.
- Set `document.namespace`, `document.name`, and `document.version`.
- Do not leave placeholders (`TODO`, `<fill-me>`, `...`).
- Prefer explicit, deterministic task names.
- Prefer quoting `schedule."on"` in YAML to avoid parser ambiguity in YAML 1.1 tooling.

If user requirements are incomplete, make minimal assumptions and encode them in names/defaults instead of asking follow-up questions unless blocked.

## One-Shot Authoring Procedure

Apply this sequence every time:

1. Convert intent to task primitives using `references/12-intent-to-task-map.md`.
2. Draft minimal valid skeleton (`document`, `do`).
3. Add control flow and data flow (`if`, `switch`, `input/output/export`).
4. Add resilience (`try/catch`, `retry`, `timeout`) where external calls exist.
5. Validate semantic constraints via `references/13-semantic-constraints.md`.
6. Apply profile constraints via `references/14-runtime-profiles.md`.
7. Run final checklist in `references/11-authoring-checklist.md`.

Before returning final YAML, run the self-check loop:

1. Shape check: does every task item have exactly one task name key?
2. Flow check: are all `then` targets in same scope?
3. Data check: are transformations in correct stage (`input.from`, `output.as`, `export.as`)?
4. Profile check: does output comply with target profile?

## Topic Map

- Workflow structure and required fields: `references/01-workflow-foundation.md`
- Data flow and runtime expressions: `references/02-data-and-expressions.md`
- Conditions and branching: `references/03-conditions-and-branching.md`
- Loops and parallel execution: `references/04-loops-and-parallelism.md`
- Service calls and protocol interactions: `references/05-calls-and-integrations.md`
- Run task (container, script, shell, nested workflow): `references/06-run-and-processes.md`
- Events and subscriptions: `references/07-events-and-listening.md`
- Errors, retries, and timeouts: `references/08-errors-retries-timeouts.md`
- Authentication, catalogs, and extensions: `references/09-auth-catalog-extensions.md`
- Lifecycle event contracts: `references/10-lifecycle-events.md`
- Final compliance checklist: `references/11-authoring-checklist.md`
- Intent to task decision table: `references/12-intent-to-task-map.md`
- Semantic constraints beyond schema: `references/13-semantic-constraints.md`
- Runtime profiles (`spec-strict`, `lemline-compatible`): `references/14-runtime-profiles.md`
- One-shot prompt-to-YAML playbook: `references/15-generate-from-prompt.md`

## Validation Script

Use deterministic validation when a file path is available:

- `scripts/validate_workflow.py /path/to/workflow.yaml --profile spec-strict`
- `scripts/validate_workflow.py /path/to/workflow.yaml --profile lemline-compatible`

Script location:

- `scripts/validate_workflow.py`

Regression suite:

- `scripts/run_eval_suite.sh`

## Authoring Defaults

- Keep expressions in `strict` mode unless a user explicitly asks for `loose`.
- Use the required task primitives first; avoid custom function URIs until required.
- Document schemas (`input.schema`, `output.schema`) when data contracts matter.
- Prefer explicit `then` only when a jump is required; otherwise rely on declaration order.
- Keep flow directives inside the same scope depth.
- Prefer `spec-strict` profile unless user asks runtime-specific compatibility.

## Templates

Reusable starting points are available in:

- `assets/templates/http-orchestration.yaml`
- `assets/templates/event-listener.yaml`
- `assets/templates/batch-loop-switch.yaml`
- `assets/templates/retry-timeout-http.yaml`
