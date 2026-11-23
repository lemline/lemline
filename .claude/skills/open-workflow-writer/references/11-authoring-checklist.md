# Acceptance checklist

Trace a happy path and a failure path with concrete inputs. List the calls in order, what each receives, and which event or output the caller observes. Include a case that must skip an action. Exercise retry exhaustion, concurrent incident IDs and empty collections when the requested behavior includes them.

Run the checks in [the skill](../SKILL.md). Parsing verifies a definition’s structure. The local scenario tests additionally verify effects with the real orchestrator and simulated services.

[Lemline documentation](../../../../lemline-docs/topics/lemline-howto-debug.md)
