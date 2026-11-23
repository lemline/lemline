# Events and listening

Use `schedule.on` to start an instance and `listen.to` to resume an existing one. A timer models elapsed time only. Use `one` for one filter, `any` for alternatives, and `all` when each filter must match.

Correlate by business identifier when multiple instances can wait on the same type. `correlate.from` reads event data; `expect` can read workflow expression arguments when the listener starts. A completed listen returns an array. Use `read: envelope` when the next step routes on event type. Test with an unrelated event before the intended one.

For a computed event payload use one jq expression for all of `emit.event.with.data`, for example `'${ {orderId: $workflow.input.order.id} }'`. The runtime treats a literal data object as literal, including any expression-looking strings nested inside it.

If the request says to publish even on failure, join the error and success paths at the emit step, and keep the failed operation observable. Exercise both branches with a failing notifier.

[Lemline documentation](../../../../lemline-docs/topics/dsl-event-correlation.md)
