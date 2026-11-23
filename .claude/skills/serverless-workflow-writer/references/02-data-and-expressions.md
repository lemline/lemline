# Data flow and expressions

In strict mode wrap jq in `${ ... }`. The next task receives the preceding task’s transformed output. An HTTP response can therefore replace the original order. Read stable request fields through `$workflow.input`. To retain selected intermediate results, merge them into `$context` with `export.as`.

Use `input.from` to shape incoming data and `output.as` to shape the result. Put an inline JSON Schema under `schema.document`. Quote a YAML scalar containing a jq object literal with a colon followed by a space.

[Lemline documentation](../../../../lemline-docs/topics/dsl-data-flow.md)
