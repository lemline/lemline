# Defining and using custom errors

Workflow errors carry a URI identifying the error type and a numeric status. Optional
fields include `title`, `detail` and `instance`.

## Define and raise a named error

Put reusable errors under `use.errors`. Reference a named definition with `raise.error`.

<code-block lang="yaml" src="workflows/reference/typed-error.yaml"/>

## Construct an error with dynamic details

Use an inline error object when fields depend on the current task input.

<code-block lang="yaml" src="workflows/reference/raise-order-error.yaml"/>

## Read or map a caught error

Inside `catch.do`, read the error variable when constructing a fallback result.
Use `catch.errors.with.type` to match a specific error type.

<code-block lang="yaml" src="workflows/reference/format-error-result.yaml"/>

See [Error Handling with Try-Catch](lemline-howto-try-catch.md) for filtering and retries.
