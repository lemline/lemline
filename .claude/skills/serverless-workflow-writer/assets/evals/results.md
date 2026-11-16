# One-Shot Evaluation Results

## Date

2026-02-05

## Scenario Set

- Prompt 01: order reserve + payment + retries + emit
- Prompt 02: nightly batch loop + switch routing + emit
- Prompt 03: incident event handling + listen + fork compete + emit

## Initial Findings

- Prompt 03 first draft failed semantic validation:
  - `fork.branches` items were authored as single task objects instead of task lists.

## Hardening Applied

- Fixed Prompt 03 to use valid fork branch shape.
- Updated fork guidance in `references/04-loops-and-parallelism.md`.
- Improved validator diagnostic for fork branch shape mismatch.

## Final Outcome

- All templates pass:
  - `spec-strict`
  - `lemline-compatible`
- All one-shot eval workflows pass:
  - `spec-strict`
  - `lemline-compatible`
- Negative suite rejects targeted invalid patterns:
  - invalid fork branch shape
  - invalid event consumption strategy combinations
  - unresolved timeout/retry references
  - placeholder markers in payloads
  - invalid run script language
  - missing `run.workflow.namespace`
  - empty schedule object
  - invalid use of `until` with non-`any` strategy
- Profile suite confirms:
  - OpenAPI call accepted in `spec-strict`
  - OpenAPI call rejected in `lemline-compatible`
