---
title: Secrets
---

# Secrets

Declare names in `use.secrets` when a workflow needs credentials or other values supplied by its environment.
The definition records the names and expressions that read them. The runtime resolves their values when it
executes the workflow.

## Declaring Secrets

Declare `API_TOKEN` without prescribing its value or putting it in the output.

<code-block lang="yaml" src="workflows/dsl-secrets/secrets-example.yaml"/>

## Secret Resolution

The runtime determines where to load a named value. Deployments can provide values through process
configuration or a secret manager.

> **Lemline**
>
> Declare the exact environment variable name, including case. Set it on every worker that
> could execute the workflow. The worker parses a JSON object as a structured secret and keeps other
> values as strings. A declared secret missing from the worker environment faults with status 403 before
> the task runs.

## Accessing Secrets in Workflows

A secret is available as `$secrets.NAME`. Limit its use to fields that need the value, and keep it out
of workflow outputs and shared context. An exported value becomes ordinary workflow data.

### Example: Using a Secret in an HTTP Call

> **Lemline**
>
> The HTTP task can read the secret into a request header, as shown here.

<code-block lang="yaml" src="workflows/dsl-secrets/example-using-a-secret-in-an-http-call.yaml"/>

### Example: Structured Secret

The Basic policy reads the two fields from a credentials object.

<code-block lang="yaml" src="workflows/dsl-secrets/example-structured-secret.yaml"/>

### Example: Secret in an Authentication Policy

Use a named [authentication policy](dsl-authentication.md) when endpoints share credentials.

<code-block lang="yaml" src="workflows/dsl-secrets/example-secret-in-an-authentication-policy.yaml"/>

## Security Considerations

Use separate values for separate environments. Never put production credentials in a workflow definition,
input argument, log, or output. The credentials below are the tutorial shop’s local demo values.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Stop the setup worker with Ctrl+C. In the same terminal, export the demo values before restarting it.

```bash
export API_TOKEN='demo-token'
export API_KEY='demo-api-key'
export SHOP_CREDENTIALS='{"username":"demo","password":"demo-password"}'
bin/lemline listen --debug
```

Use a second terminal in the setup directory for the instance commands.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f secrets-example.yaml
bin/lemline instance start examples secrets-example
bin/lemline definition post -f example-using-a-secret-in-an-http-call.yaml
bin/lemline instance start examples example-using-a-secret-in-an-http-call
bin/lemline definition post -f example-structured-secret.yaml
bin/lemline instance start examples example-structured-secret
bin/lemline definition post -f example-secret-in-an-authentication-policy.yaml
bin/lemline instance start examples example-secret-in-an-authentication-policy
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `secrets-example` | `{"ready":true}` |
| `example-using-a-secret-in-an-http-call` | The shop accepts the API-key header and returns `SKU-1` with `100` available units and `authenticatedWith: "api-key-header"`. |
| `example-structured-secret` | The shop accepts the Basic policy and returns `SKU-1` with `100` available units and `authenticatedWith: "basic"`. |
| `example-secret-in-an-authentication-policy` | The shop accepts the Bearer policy and returns `SKU-1` with `100` available units and `authenticatedWith: "bearer"`. |

### Run with a Missing Secret

Before stopping the worker, wait for all four results in its terminal. Starting an instance queues work asynchronously.
The commands below change the worker environment after those runs finish.

```bash
sleep 10
```

When the results are visible, stop the worker with Ctrl+C. Remove `API_TOKEN` in the worker terminal and start it again.

```bash
unset API_TOKEN
bin/lemline listen --debug
```

Use the second terminal to start the declaration-only workflow with the new worker environment.

```bash
bin/lemline instance start examples secrets-example
```

The instance faults with status `403` and the title `Required secret 'API_TOKEN' is unavailable`.

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
