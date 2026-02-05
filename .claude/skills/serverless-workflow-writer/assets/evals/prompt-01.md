Build a workflow that takes an order, reserves inventory, charges payment, and emits an order-confirmed event.
If reserve or charge fails with transient communication errors, retry with bounded exponential backoff.
If payment still fails, raise a runtime error.
Set a global timeout and an explicit task timeout for external HTTP calls.

