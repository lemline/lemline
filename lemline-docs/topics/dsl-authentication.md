---
title: Authentication
---

# Workflow authentication

Open Workflow endpoints can use a named policy or define their own authentication. See the
[authentication options](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#authentication) in the DSL reference.

## Defining Reusable Authentications

Declare a policy in `use.authentications` and select it with `authentication.use`
at the endpoint. Declare any secret names the policy reads.

<code-block lang="yaml" src="workflows/reference/bearer-authentication.yaml"/>

## Inline Authentication

A single endpoint can contain its own policy. This example reads the basic
authentication password from a declared secret.

<code-block lang="yaml" src="workflows/dsl-authentication/inline-authentication.yaml"/>

Use [Secrets](dsl-secrets.md) to configure credential values outside the definition.

> **Lemline**
>
> HTTP calls support the Basic and Bearer policies shown here. OAuth 2.0 and OpenID Connect policies register, but the worker faults on execution.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Stop the setup worker with Ctrl+C. From its terminal, export the shop’s demo credentials before restarting the worker.

```bash
export apiToken='demo-token'
export tempPassword='demo-password'
bin/lemline listen --debug
```

Use a second terminal in the setup directory for the instance commands.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f bearer-authentication.yaml
bin/lemline instance start examples bearer-authentication
bin/lemline definition post -f inline-authentication.yaml
bin/lemline instance start examples inline-authentication
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `bearer-authentication` | The partner stock for `SKU-1` with `100` available units and `authenticatedWith: "bearer"`. |
| `inline-authentication` | The partner stock for `SKU-1` with `100` available units and `authenticatedWith: "basic"`. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
