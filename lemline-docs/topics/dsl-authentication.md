---
title: Authentication
---

# Workflow authentication

Open Workflow supports both named policies and inline authentication. Check
[Lemline Supported Features](lemline-ref-supported-features.md) for runtime support.

## Defining Reusable Authentications

Declare a policy in `use.authentications` and select it with `authentication.use`
at the endpoint. Declare any secret names the policy reads.

<code-block lang="yaml" src="workflows/reference/bearer-authentication.yaml"/>

## Inline Authentication

A single endpoint can contain its own policy. This example reads the basic
authentication password from a declared secret.

<code-block lang="yaml" src="workflows/dsl-authentication/inline-authentication.yaml"/>

See the [Authentication Reference](lemline-ref-auth.md) for the supported options.
