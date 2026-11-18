# Connections Reference

This reference explains how workflows reuse endpoint settings in Lemline.

## Connections Overview

Lemline has no "connection" object: there is no `use.connections` section, no connection pools to configure, no connection health checks, failover or circuit breakers, and no `lemline.connections.*` runner settings.

Workflows reuse settings with the reusable components of the Open Workflow DSL that Lemline supports in the `use` section:

| `use` section | Reused in |
|---------------|-----------|
| `authentications` | `call: http` endpoints (`authentication.use`) |
| `secrets` | Expressions, through `$secrets` |
| `functions` | `call: <function name>` |
| `errors` | `raise` tasks (`error: <name>`) |
| `retries` | `try` / `catch` (`retry: <name>`) |

## Reusing an Endpoint's Authentication

```yaml
use:
  secrets:
    - USER_API_TOKEN
  authentications:
    userApi:
      bearer:
        token: ${ $secrets.USER_API_TOKEN }
do:
  - getUser:
      call: http
      with:
        method: get
        endpoint:
          uri: ${ "https://api.example.com/users/" + .userId }
          authentication:
            use: userApi
```

## Reusing a Call with a Function

Wrap a call in a function and call it by name. The `with` values of the calling task are the function's input:

```yaml
use:
  functions:
    getUser:
      do:
        - request:
            call: http
            with:
              method: get
              endpoint: ${ "https://api.example.com/users/" + .userId }
do:
  - fetchUser:
      call: getUser
      with:
        userId: ${ .id }
```

## Reusing a Retry Policy

```yaml
use:
  retries:
    standard:
      delay:
        seconds: 2
      backoff:
        exponential: {}
      limit:
        attempt:
          count: 5
do:
  - fetchData:
      try:
        - callApi:
            call: http
            with:
              method: get
              endpoint: https://api.example.com/data
      catch:
        retry: standard
```

## Broker and Database Connections

The runner's own connections to the message broker and the database are set in the runner configuration, not in workflows. See [Runner Configuration Reference](lemline-ref-config.md) and [Message Brokers](lemline-howto-brokers.md).

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Authentication Reference](lemline-ref-auth.md)
- [Runner Configuration Reference](lemline-ref-config.md)
