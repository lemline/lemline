---
title: URI Templates
---

# URI Templates

> **Lemline runtime:** placeholders such as `{userId}` are not expanded by Lemline yet; the URI is used as written. Build dynamic URIs with a runtime expression, e.g. `${ "https://api.example.com/users/" + .userId }`.


## Purpose

URI Templates provide a limited mechanism for creating dynamic URIs within the Open Workflow DSL, based on
variable substitution. They are primarily used in places where a URI needs to be constructed based on available data,
such as defining the endpoint for an HTTP `call`.

This feature is based on [RFC 6570](https://datatracker.ietf.org/doc/html/rfc6570) but supports only a small subset of
its capabilities.

## Supported Syntax: Simple String Expansion

The only syntax supported is **Simple String Expansion**: `{variableName}`.

* The workflow runtime identifies placeholders enclosed in curly braces `{}`.
* It looks for a variable with the exact name specified inside the braces (e.g., `variableName`) within the current data
  context.
* It replaces the entire `{variableName}` placeholder with the **value** of that variable.
* If no variable with that exact name is found, the placeholder is replaced with an **empty string**.

<code-block lang="yaml" src="workflows/dsl-uri-templates/supported-syntax-simple-string-expansion.yaml" include-lines="8-11"/>

## Limitations Compared to Runtime Expressions (`${...}`)

URI Templates are **much less powerful** than standard [Runtime Expressions](dsl-runtime-expressions.md). Key
limitations include:

1. **No Dot Notation:** You cannot access nested properties within variables (e.g., `{user.id}` is invalid for accessing
   `id` within a `user` object). The template processor looks for a variable literally named `user.id`. You must ensure
   the required value exists as a top-level variable (e.g., use `{userId}` instead).
2. **Restricted Variable Types:** The variable referenced inside `{}` **must** resolve to a `string`, `number`,
   `boolean`, or `null`. Using variables that resolve to objects or arrays will result in an error.
3. **No Access to Workflow Arguments:** You **cannot** use special workflow arguments like `$context`, `$task`,
   `$input`, `$output`, or `$secrets` inside `{}` placeholders. Use standard Runtime Expressions (`${...}`) when you
   need access to these.

This expression builds an endpoint from the current input and escapes the product ID
before inserting it into the path.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

Choose URI Templates only for very simple substitutions where the limitations are acceptable. For more complex URI
construction or access to workflow context, use standard Runtime Expressions.

## Error Handling

If a variable referenced within a URI Template resolves to an unsupported type (like an object or array), the workflow *
*must** fault with an error:

* **`type`**: `https://open-workflow-specification.org/spec/1.0.0/errors/expression`
* **`status`**: `400` (Bad Request) 