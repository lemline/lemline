# Lifecycle events

Use Lemline lifecycle events for observing workflow and task transitions. Use an explicit `emit` task for an application contract such as an order-confirmed notification. Place the business event after all actions whose success it attests to. Preserve the original business identifier in its payload.

[Lemline documentation](../../../../lemline-docs/topics/dsl-lifecycle-events.md)
