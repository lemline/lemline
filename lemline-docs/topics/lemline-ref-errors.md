# Error Reference

This reference documents the standard error types and error handling capabilities in Lemline.

## Standard Error Types

Lemline implements the standardized error types defined in the Open Workflow Specification, with the base URI `https://open-workflow-specification.org/spec/1.0.0/errors/`:

| Error Type | URI | Default Status | Description |
|------------|-----|----------------|-------------|
| Configuration | `https://open-workflow-specification.org/spec/1.0.0/errors/configuration` | 400 | Errors in workflow definition or structure |
| Validation | `https://open-workflow-specification.org/spec/1.0.0/errors/validation` | 400 | Input/output data schema validation failures |
| Expression | `https://open-workflow-specification.org/spec/1.0.0/errors/expression` | 400 | Expression evaluation errors (JQ syntax or execution) |
| Authentication | `https://open-workflow-specification.org/spec/1.0.0/errors/authentication` | 401 | Authentication failures |
| Authorization | `https://open-workflow-specification.org/spec/1.0.0/errors/authorization` | 403 | Authorization/permission failures |
| Timeout | `https://open-workflow-specification.org/spec/1.0.0/errors/timeout` | 408 | Timeout violations (workflow or task) |
| Communication | `https://open-workflow-specification.org/spec/1.0.0/errors/communication` | 500 | Errors communicating with external services |
| Runtime | `https://open-workflow-specification.org/spec/1.0.0/errors/runtime` | 500 | General runtime execution errors |

## Error Structure

All errors in Lemline follow the RFC 7807 Problem Details format:

<!-- workflow-example: data -->
```json
{
  "type": "https://open-workflow-specification.org/spec/1.0.0/errors/validation",
  "status": 400,
  "instance": "/do/0/validateInput",
  "title": "Input Validation Failed",
  "details": "The 'amount' field must be a positive number"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | URI reference identifying the problem type |
| `status` | Yes | HTTP status code (numeric) corresponding to the error |
| `instance` | Yes | JSON pointer to the location in the workflow where the error occurred |
| `title` | No | Short, human-readable summary of the problem |
| `details` | No | Detailed explanation specific to this error occurrence |

## Error Titles and Details

Lemline errors carry no numeric or symbolic error code: identify them by `type` (and `status`), and read `title` and `details` for the specific cause. For example:

| Situation | `type` | `status` | Example message |
|-----------|--------|----------|-----------------|
| A declared secret is not set on the runner | `authorization` | 403 | `Required secret 'API_KEY' is unavailable` |
| An HTTP call returns a non-2xx status | `runtime` | 500 | `HTTP 404: ...` |
| A `listen` task reaches its timeout | `timeout` | 408 | `Listen task timed out at ...` |
| An expression fails to evaluate | `expression` | 400 | the evaluation error message |
| Input or output does not match its schema | `validation` | 400 | the schema validation message |

Lemline only raises `timeout` errors for `listen` tasks: workflow-level timeouts and timeouts on other tasks are not enforced yet.

## CLI Exit Codes

| Exit Code | Description |
|-----------|-------------|
| 0 | Success |
| 1 | Error while running the command (including a missing configuration file) |
| 2 | Invalid command line (unknown option, missing argument) |

## Gateway API Errors

The gateway exposes gRPC (and Connect) APIs. Failed calls return standard gRPC status codes, for example `INVALID_ARGUMENT` for an invalid request, `NOT_FOUND` for an unknown definition or instance, `ALREADY_EXISTS`, `UNAUTHENTICATED`, `PERMISSION_DENIED` and `INTERNAL`.

## Java Exception Hierarchy

For developers working on Lemline, workflow errors are carried by a single exception type:

```
java.lang.RuntimeException
└── com.lemline.core.errors.WorkflowException (sealed)
    └── com.lemline.core.errors.InternalException
```

`InternalException` holds an `InternalException.Error` with the standard error fields (`type`, `status`, `instance`, `title`, `details`). The error type is one of the values of `com.lemline.core.errors.WorkflowErrorType` (`configuration`, `validation`, `expression`, `authentication`, `authorization`, `timeout`, `communication`, `runtime`).

## Defining Custom Errors

Custom error types can be defined in workflow definitions:

<code-block lang="yaml" src="workflows/reference/typed-error.yaml"/>

## Error Handling Examples

### Basic Try-Catch

<code-block lang="yaml" src="workflows/lemline-ref-errors/basic-try-catch.yaml" include-lines="7-20"/>

### Matching Multiple Error Types

<code-block lang="yaml" src="workflows/reference/format-error-result.yaml"/>

### Using Retry Policies

<code-block lang="yaml" src="workflows/lemline-ref-errors/using-retry-policies.yaml" include-lines="8-12"/>

## Error Debugging 

When debugging errors:

- Read the `workflow.faulted` and `task.faulted` lifecycle events of the instance in the dashboard; they carry the error `type`, `status`, `title`, `instance` (position in the workflow) and `detail`.
- Run the runner with `--debug` to log the input of each task. Every log line carries the workflow namespace, name, version and instance id, so you can filter on the instance.

## Error Handling Best Practices

1. **Be specific with error types**: Catch specific error types rather than all errors
2. **Provide fallbacks**: Use default values or alternative paths for common errors
3. **Use conditional retries**: Only retry errors that have a chance of succeeding on retry
4. **Include context in errors**: Use descriptive error messages with relevant details
5. **Log error details**: Log sufficient context for debugging
6. **Use appropriate error status codes**: Match HTTP status codes to semantics
7. **Define custom error types**: Create domain-specific error types for your application
8. **Organize errors hierarchically**: Use consistent type URIs that reflect hierarchy

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Implementing Retry Mechanisms](lemline-howto-retry.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Debugging Workflows](lemline-howto-debug.md)
- [Resilience Patterns](dsl-resilience-patterns.md)