# Managing Secrets in Lemline

This guide explains how to give your workflows access to API keys, passwords, and tokens without writing them in workflow definitions.

## How It Works

1. The workflow **declares** the names of the secrets it needs in `use.secrets`.
2. The runner **reads** each declared name from an environment variable with exactly the same name.
3. Expressions **access** the values through `$secrets.<name>`.

Secret values are resolved on the runner each time it executes a step. `$secrets` is not written to broker messages or to the database as part of the workflow state; a value copied from it into task output is, like any other workflow data.

## Step 1: Declare the Secrets

<code-block lang="yaml" src="workflows/lemline-howto-secrets/step-1-declare-the-secrets.yaml"/>

A workflow only sees the secrets it declares. A secret that is set on the runner but not listed in `use.secrets` is not visible to expressions.

## Step 2: Provide the Values to the Runner

Set an environment variable named after each declared secret on **every runner** that may execute the workflow:

```bash
export PAYMENT_API_KEY='sk_live_51H...'
lemline listen
```

With Kubernetes, map a Kubernetes Secret to the same variable name:

<!-- workflow-example: data -->
```yaml
env:
  - name: PAYMENT_API_KEY
    valueFrom:
      secretKeyRef:
        name: payments
        key: api-key
```

There is no prefix and no name conversion: `use.secrets: [PAYMENT_API_KEY]` reads the variable `PAYMENT_API_KEY`. Prefer names made of letters, digits, and underscores so they can be set from any shell.

## Structured Secrets

If the value is a JSON object, Lemline parses it, and expressions can read its fields:

```bash
export DB_CREDENTIALS='{"username": "app", "password": "s3cr3t"}'
```

<code-block lang="yaml" src="workflows/lemline-howto-secrets/structured-secrets.yaml" include-lines="6-17"/>

Any other value (including a JSON array or number) is passed as a plain string.

## Using Secrets in HTTP Calls

Every part of an HTTP call evaluates expressions: `endpoint`, `headers`, `query`, `body`, and the fields of `endpoint.authentication`. Instead of building the `Authorization` header yourself, you can use an authentication policy:

<code-block lang="yaml" src="workflows/reference/http-secrets.yaml" include-lines="14-18"/>

Basic authentication works the same way, with `username` and `password` expressions.

## When a Secret Is Missing

If a declared secret is not set on the runner that executes a step, the workflow **faults** with an `authorization` error (status 403) and the title `Required secret '<name>' is unavailable`. The check runs before the step, so a `try`/`catch` in the workflow cannot handle it.

To fix it, set the variable on the runner and start a new instance.

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
