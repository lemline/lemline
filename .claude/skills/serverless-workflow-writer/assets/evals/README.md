# One-Shot Evaluations

This folder contains one-shot workflow drafts used to stress-test the skill.

Method:

1. Start from a business prompt.
2. Produce a single workflow YAML draft without iterative redesign.
3. Validate with:
   - `scripts/validate_workflow.py --profile spec-strict`
   - `scripts/validate_workflow.py --profile lemline-compatible`

Files:

- `prompt-01.md`, `workflow-01.yaml`
- `prompt-02.md`, `workflow-02.yaml`
- `prompt-03.md`, `workflow-03.yaml`

Negative cases:

- `negative/*.yaml` (must fail in `spec-strict`)

Profile cases:

- `profile/openapi-spec-only.yaml` (must pass `spec-strict`, fail `lemline-compatible`)

Automated run:

- `scripts/run_eval_suite.sh`
