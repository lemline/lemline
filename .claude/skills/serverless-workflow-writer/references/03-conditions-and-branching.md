# Conditions and branching

Use task-level `if` for a guard and a `switch` with ordered cases for exclusive choices. Each case jumps to a sibling task or a flow directive. Use an unconditional final case for a default route.

A normal task falls through to the following sibling. Give mutually exclusive HTTP handlers an explicit join point or exit. In a loop, `then: continue` advances to the next task in the body, which could be a rejection handler.

Validate external scores or amounts as JSON numbers before comparing thresholds. jq orders strings above numbers, so a malformed value can otherwise select the highest route.

[Lemline documentation](../../../../lemline-docs/topics/dsl-flow-control.md)
