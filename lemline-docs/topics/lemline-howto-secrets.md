# Managing Secrets in Lemline

This guide explains how to give your workflows access to API keys, passwords, and tokens without writing them in workflow definitions.

## Prepare the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md). The examples call the partner route of the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop), `GET /partner/stock`. It answers
HTTP 401 without credentials and HTTP 403 with a wrong value, and lists every request it received, with its headers
and query, under **Requests** on the shop page. Start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Run the other commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.
The worker reads secrets from its environment when it starts, so each example below stops the worker with Ctrl+C,
exports the value, and starts the worker again in the same terminal. Run the `definition` and `instance` commands
from a second terminal.

## How It Works

1. The workflow **declares** the names of the secrets it needs in `use.secrets`.
2. The runner **reads** each declared name from an environment variable with exactly the same name.
3. Expressions **access** the values through `$secrets.<name>`.

Secret values are resolved on the runner each time it executes a step. `$secrets` is not written to broker messages or to the database as part of the workflow state; a value copied from it into task output is, like any other workflow data.

## Step 1: Declare the Secrets

<code-block lang="yaml" src="workflows/lemline-howto-secrets/step-1-declare-the-secrets.yaml"/>

Save it as `step-1-declare-the-secrets.yaml`.

A workflow only sees the secrets it declares. A secret that is set on the runner but not listed in `use.secrets` is not visible to expressions.

## Step 2: Provide the Values to the Runner

Set an environment variable named after each declared secret on **every runner** that may execute the workflow:

```bash
export PARTNER_TOKEN='demo-token'
bin/lemline listen --debug
```

From the second terminal, register and run the workflow:

```bash
bin/lemline definition post -f step-1-declare-the-secrets.yaml
bin/lemline instance start examples declare-secrets
```

The shop accepts the token: the instance completes with `{"sku":"SKU-1","available":100,"authenticatedWith":"bearer"}`,
and the shop's request list shows the `authorization` header `Bearer demo-token` it received.

With Kubernetes, map a Kubernetes Secret to the same variable name:

<!-- workflow-example: data -->
```yaml
env:
  - name: PARTNER_TOKEN
    valueFrom:
      secretKeyRef:
        name: partner
        key: token
```

There is no prefix and no name conversion: `use.secrets: [PARTNER_TOKEN]` reads the variable `PARTNER_TOKEN`. Prefer names made of letters, digits, and underscores so they can be set from any shell.

## Structured Secrets

If the value is a JSON object, Lemline parses it, and expressions can read its fields:

<code-block lang="yaml" src="workflows/lemline-howto-secrets/structured-secrets.yaml"/>

Save it as `structured-secrets.yaml`. Stop the worker, then export the credentials as one JSON value and start it again:

```bash
export SHOP_CREDENTIALS='{"username": "demo", "password": "demo-password"}'
bin/lemline listen --debug
```

```bash
bin/lemline definition post -f structured-secrets.yaml
bin/lemline instance start examples structured-secrets
```

The policy sends `demo` and `demo-password` as basic credentials: expect `"authenticatedWith":"basic"`.

Any other value (including a JSON array or number) is passed as a plain string.

## Using Secrets in HTTP Calls

Every part of an HTTP call evaluates expressions: `endpoint`, `headers`, `query`, `body`, and the fields of `endpoint.authentication`. Instead of building the `Authorization` header yourself, you can use an authentication policy:

<code-block lang="yaml" src="workflows/reference/http-secrets.yaml"/>

Save it as `http-secrets.yaml`. It reuses the `PARTNER_TOKEN` exported above:

```bash
bin/lemline definition post -f http-secrets.yaml
bin/lemline instance start examples http-secrets
```

Expect `"authenticatedWith":"bearer"` again, this time from the policy.

Basic authentication works the same way, with `username` and `password` expressions.

## When a Secret Is Missing

If a declared secret is not set on the runner that executes a step, the workflow **faults** with an `authorization` error (status 403) and the title `Required secret '<name>' is unavailable`. The check runs before the step, so a `try`/`catch` in the workflow cannot handle it.

To see it, stop the worker, remove the variable and start the worker again, then start the first example again:

```bash
unset PARTNER_TOKEN
bin/lemline listen --debug
```

```bash
bin/lemline instance start examples declare-secrets
```

The instance faults with type `authorization`, status 403 and the title `Required secret 'PARTNER_TOKEN' is
unavailable`, and the shop receives no new request. To fix it, set the variable on the runner and start a new
instance. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Child Workflows

A child workflow started with `run: workflow` resolves its own `use.secrets`. It does not inherit secrets from its parent, and it can use a secret its parent does not declare.

## Rotating a Secret

Environment variables are read from the runner process. To rotate a secret:

1. Update the value where your runners get it (Kubernetes Secret, deployment configuration…).
2. Restart the runners.

Because `$secrets` is resolved again for each step, instances already in progress use the new value from their next step on.

## Good Practices

- **Declare only what the workflow needs.** Each workflow only sees its own declared secrets.
- **Filter request headers from `output: response`.** An HTTP task's output includes the headers of the request it sent, including `Authorization`; keep only what you need with `output.as`.
- **Limit where you reference secrets.** An expression can still copy a secret into task output, a log, or a request body. Lemline does not mask it there.
- **Use different values per environment.** Runners of different namespaces or environments can receive different values for the same name.

## Related Resources

- [Secrets Reference](lemline-ref-secrets.md)
- [Secrets in the Open Workflow DSL](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#secret)
