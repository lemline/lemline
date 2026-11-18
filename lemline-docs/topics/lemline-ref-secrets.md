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
| Storage | Never included in broker messages or persisted workflow state |
| Missing value | The workflow faults with an `authorization` error (status 403) |
| Child workflows | Resolve their own declared secrets; nothing is inherited from the parent |
| Rotation | Update the variable and restart the runners |

## Declaration

```yaml
use:
  secrets:
    - PAYMENT_API_KEY
    - DB_CREDENTIALS
```

## Value Types

| Environment value | Value of `$secrets.<name>` |
|-------------------|----------------------------|
| A JSON object, e.g. `{"username":"app","password":"s3cr3t"}` | The parsed object (`$secrets.DB_CREDENTIALS.password`) |
| Anything else | The raw string |

## Where Secrets Can Be Used

`$secrets` is available in every runtime expression of the workflow: HTTP `endpoint`, `headers`, `query`, `body` and authentication policies (`basic`, `bearer`), shell `command` and `arguments`, `if`, `set`, `export`, `output`, and so on.

```yaml
endpoint:
  uri: https://payments.example.com/v1/charges
  authentication:
    bearer:
      token: ${ $secrets.PAYMENT_API_KEY }
```

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
- Every runner that may execute a workflow needs the variables it declares, including runners of other pools that execute its child workflows.
