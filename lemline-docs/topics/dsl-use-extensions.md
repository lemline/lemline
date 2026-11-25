# Workflow extensions

DSL 1.0.3 `use.extensions` adds tasks before and after selected tasks without moving the original task's execution position.

<code-block lang="yaml" src="workflows/dsl-use-extensions/workflow-extensions.yaml"/>

`extend` accepts `call`, `composite`, `emit`, `for`, `listen`, `raise`, `run`, `set`, `switch`, `try`, `wait`, and `all`. `composite` matches declared `do` and `fork` tasks. Reusable function calls match `call`; their declared body tasks can also have extensions. Internal execution blocks are excluded.

The engine evaluates the task's `if` and input transformation before selecting extensions. All `when` expressions are evaluated once, before any `before` runs. A false `if` skips extensions. Invalid or non-boolean `when` results fail the task.

For two selected extensions, the order is `before 1`, `before 2`, body, `after 2`, `after 1`. Every `before` block receives the task's transformed input; every `after` receives its prepared output. A block's final result is ignored, while explicit exports update the shared context. The body is initialized after the `before` blocks, so a loop can read a collection they exported.

Within extension expressions, `$task` describes the owner, including its original definition, raw input, start time, and raw output when available. Use `$task.definition.call` and `$task.definition.with`. The current dataset, `$input`, `$output`, and local variables belong to the executing step. Extension descendants, including functions they call, never receive additional extensions. Child workflows use their own definitions.

## Short-circuiting and navigation

A top-level `before` step with `then: exit` returns a simulated raw result, skips the body and remaining extensions, and applies the owner's output transformation, validation, and export once. The owner's own `then` is still honored.

<code-block lang="yaml" src="workflows/dsl-use-extensions/short-circuiting-and-navigation.yaml"/>

A nested `exit` remains local to its nested block. A top-level `after` exit skips remaining after blocks and keeps the owner's result. `end` terminates the workflow without executing additional after blocks.

## Errors, retries, and durable execution

After blocks run only after the body actually finishes, including HTTP, waits, listeners, forks, and awaited child workflows. They are not `finally` blocks. Unhandled failures stop the extension sequence and fail the owner. A `try` protects its body, not its own extensions.

Put a retry inside an after block to retry only that block's protected work. An outer retry can execute the business operation again: if payment succeeds and auditing fails, an outer retry can charge again. Existing exports and external effects are not rolled back.

Suspension messages preserve selection, phase, inputs, and prepared results. Resuming does not re-evaluate `when` or reapply output transformations. A new invocation caused by a retry selects extensions again.

The owner's lifecycle spans all its extensions. Declared extension steps have their own CloudEvents and appear beneath the owner in the dashboard, with the extension name, phase, type, errors, and attempts. The owner's success is published only after its final after block succeeds.

## Deployment

Deploy workers supporting extension continuations before enabling workflows that use them. Older workers cannot process `_ext` positions or pending task state. Do not route these executions to older workers during a mixed deployment. New workers continue to read messages without extension fields; already active legacy frames are not retroactively extended.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f workflow-extensions.yaml
bin/lemline instance start examples workflow-extensions
bin/lemline definition post -f short-circuiting-and-navigation.yaml
bin/lemline instance start examples short-circuiting-and-navigation --input '{"simulate":false}'
bin/lemline instance start examples short-circuiting-and-navigation --input '{"simulate":true}'
```

| Example | Expected result |
|---|---|
| `workflow-extensions` | The stock; the extension records `Response with 100 units` for the HTTP task in the shop's audit list |
| `short-circuiting-and-navigation`, `simulate` false | The real stock |
| `short-circuiting-and-navigation`, `simulate` true | `{"sku":"SKU-1","available":0,"simulated":true}`: the HTTP call is skipped and the shop receives no request |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

