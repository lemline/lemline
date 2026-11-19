# OpenAPI Reference

This page describes the support of `call: openapi` tasks in Lemline.

## OpenAPI Support Status

Lemline does not execute `call: openapi` tasks yet. The Open Workflow DSL defines them (see [OpenAPI calls](dsl-call-openapi.md)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.openapi.*` runner settings.

## Alternative

Call the operation's URL directly with `call: http`, using the method, path, query parameters and body described in the OpenAPI document:

```yaml
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: ${ "https://petstore.example.com/v2/pet/" + (.petId | tostring) }
        output: content
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
