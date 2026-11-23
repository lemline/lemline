---
title: How to define a workflow
---

# How to define a workflow

## Define a workflow

Save the following definition as `greet.yaml`. The `document` header declares the DSL version and identifies the workflow. The `do` list contains named tasks in execution order.

```yaml
document:
  dsl: '1.0.3'
  namespace: tutorial
  name: greet
  version: '1.0.0'
input:
  schema:
    format: json
    document:
      type: object
      required: [name]
      properties:
        name:
          type: string
do:
  - greet:
      set:
        message: '${ "Hello, " + .name }'
```

With input `{"name":"Ada"}`, the workflow returns `{"message":"Hello, Ada"}`. The input schema checks the request before the first task runs. The `set` task uses a JQ expression to read the input.

## Register the definition

Run these commands in your configured Lemline project directory:

```bash
bin/lemline definition post -f greet.yaml
bin/lemline definition get
```

Use `document.version` to version your workflow independently of `document.dsl`. For changes to sequencing, data flow, or reusable functions, see the [DSL syntax reference](lemline-ref-dsl-syntax.md) and the [list of supported tasks](lemline-ref-supported-features.md).

## Next Steps

- Learn how to [publish and run a workflow](lemline-howto-publish-run.md)
- Explore [data passing between tasks](lemline-howto-data-passing.md)
- Understand [how to handle errors](lemline-howto-try-catch.md)

For a complete reference of all workflow elements, see the [DSL Reference](lemline-ref-dsl-syntax.md).
