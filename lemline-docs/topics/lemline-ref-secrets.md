# Secrets Reference

This page summarizes how Lemline resolves workflow secrets. For a step-by-step guide, see [Managing Secrets in Lemline](lemline-howto-secrets.md).

## Summary

| Aspect | Behavior |
|--------|----------|
| Declaration | `use.secrets`: a list of secret names |
| Source | Runner environment variables with exactly the declared names (no prefix, no case conversion) |
| Access in expressions | `$secrets.<name>` |
| Visibility | Only the names declared by the current workflow |
| Resolution time | On the runner, before each step it executes |
| Storage | `$secrets` is not included in broker messages or persisted workflow state; values copied into task output (including request headers returned by `output: response`) are |
| Missing value | The workflow faults with an `authorization` error (status 403) |
| Child workflows | Resolve their own declared secrets; nothing is inherited from the parent |
| Rotation | Update the variable and restart the runners |

## Declaration

<code-block lang="yaml" src="workflows/lemline-ref-secrets/declaration.yaml"/>

## Value Types

| Environment value | Value of `$secrets.<name>` |
|-------------------|----------------------------|
| A JSON object, e.g. `{"username":"app","password":"s3cr3t"}` | The parsed object (`$secrets.DB_CREDENTIALS.password`) |
| Anything else | The raw string |

## Where Secrets Can Be Used

`$secrets` is available in every runtime expression of the workflow: HTTP `endpoint`, `headers`, `query`, `body` and authentication policies (`basic`, `bearer`), shell `command` and `arguments`, `if`, `set`, `export`, `output`, and so on.

<code-block lang="yaml" src="workflows/reference/http-secrets-reference.yaml"/>

Lemline does not provide its own secret store, file source, or vault integration. Use your platform to inject environment variables (Kubernetes Secrets, Docker secrets mapped to variables, your deployment tool…).

## Missing Secret Error

When a declared secret is not set on the runner, the workflow faults before running the step with:

| Field | Value |
|-------|-------|
| `type` | `https://open-workflow-specification.org/spec/1.0.0/errors/authorization` |
| `status` | `403` |
| `title` | `Required secret '<name>' is unavailable` |

This error faults the workflow directly; it cannot be caught by a `try` task.

## Security Notes

- Resolved values are kept in memory only while a step runs.
- An expression that copies a secret into output, context, or a request sends that value onward; Lemline does not mask it.
- With `output: response`, an HTTP task's output includes the headers of the request it sent, including `Authorization`: filter them out (for example with `output.as`) before they become workflow data.
- Every runner that may execute a workflow needs the variables it declares, including runners of other pools that execute its child workflows.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The examples read secrets from the worker's environment. Stop the setup worker with Ctrl+C, export the values, and start it again:

```bash
export PARTNER_TOKEN='demo-token'
export SHOP_CREDENTIALS='{"username": "demo", "password": "demo-password"}'
bin/lemline listen --debug
```

Run the next commands from a second terminal in the setup directory.

```bash
bin/lemline definition post -f declaration.yaml
bin/lemline instance start examples declaration
bin/lemline definition post -f http-secrets-reference.yaml
bin/lemline instance start examples http-secrets-reference
```

| Example | Expected result |
|---|---|
| `declaration` | `{"tokenSet":true,"user":"demo"}` |
| `http-secrets-reference` | The partner stock with `"authenticatedWith":"bearer"` |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

