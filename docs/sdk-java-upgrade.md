# Java SDK 7.35.3 migration

Lemline uses Open Workflow's Java SDK **7.35.3.Final**, the latest published release verified on 2026-11-17:

- [Upstream release](https://github.com/open-workflow-specification/sdk-java/releases/tag/7.35.3.Final)
- [Maven Central metadata](https://repo.maven.apache.org/maven2/io/serverlessworkflow/serverlessworkflow-api/maven-metadata.xml)

The published artifacts still use `io.serverlessworkflow:serverlessworkflow-*` and Java 17. The new `org.openworkflow.sdk` coordinates and Java 21 requirement on upstream's development branch belong to the upcoming 8.x release.

## Workflow definition changes

Validation follows the schema shipped with the pinned SDK. Validate stored definitions before upgrading workers; some definitions accepted by 7.0.0.Final need edits even though `document.dsl` remains `1.0.0`.

Shell arguments are now an ordered list. Replace argument maps:

```yaml
# Before
shell:
  command: echo
  arguments:
    Hello: World

# After
shell:
  command: echo
  arguments: [Hello, World]
```

Lists preserve duplicate arguments, empty strings, and spaces within one argument. Each entry can be an expression. `shell.stdin` is also supported, including with `await: false`; input is staged in a temporary file removed when the child exits. A process or host crash can leave that file for the operating system's temporary-file cleanup.

Quote static HTTP query values (`page: '1'`, not `page: 1`). Headers and query parameters accept either an object with expression values or an expression producing the complete object:

```yaml
headers: '${ .headers }'
query:
  page: '1'
  search: '${ .search }'
```

`set` accepts an expression as well as an object. `for.in` accepts a literal array as well as an expression. Error filters use `catch.errors.with.detail` (singular); raised error titles and details can be expressions. The SDK now models `evaluate.language` and `evaluate.mode` directly; Lemline continues to support JQ with strict evaluation by default and explicit loose evaluation.

`catch.then` controls the transition after a caught error. Wait durations, listen timeout durations, and retry delays, limits, and jitter bounds accept runtime expressions. Timer `schedule.every` and `schedule.after` remain static because they are registered before a workflow input or runtime scope exists; expressions there are rejected during definition loading.

Event-triggered workflows now honor `schedule.read`: `data` (the SDK default), `envelope`, or `raw`, using the same decoding as listen tasks. Existing definitions that expect full CloudEvent envelopes must explicitly set `schedule.read: envelope`. The selected representation is also used by collection `until` expressions and input-schema validation.

## Serialization and dependencies

The SDK separates model types, serialization, and implementation modules. Lemline registers the SDK's Jackson mixins on its JSON and YAML mappers, omits absent bean properties, and preserves explicit null map values and empty arrays (including `listen.to.any: []`). Native reflection indexing also includes `serverlessworkflow-types`.

Lemline declares its JQ dependency directly, retaining 1.2.0 and JQ 1.6 behavior. JSON Schema validation uses networknt 2.0.7, matching the SDK. Recursive workflow-schema preloading remains disabled, and data-schema format assertions remain enabled. External HTTP and file `$ref` resolution is explicitly enabled to retain the previous validator's behavior.

Compatibility adapters preserve multiline literal error messages and omit the SDK's default `schedule.read: data` for timer schedules. Schema YAML parsing preserves property names such as `on` in arrays. External schemas are fetched for each validation, as before, avoiding stale HTTP schemas when servers omit `Last-Modified` or do not support `HEAD`.

HTTP schema URLs are fetched unchanged and follow server redirects, preserving GitHub `/raw/` URLs and query parameters instead of using the SDK's URL rewriting.

## Durable messages and deployment

Shell events now write `ordered_arguments` (field 6) and optional `stdin` (field 7). The old `arguments` map (field 2) remains deprecated and readable so already persisted shell events can be resumed. No field number is reused and no database migration is needed.

Older workers cannot execute the new shell fields correctly. Stop or drain old workers before starting upgraded workers; do not mix versions while producing new shell events. Existing workflow definitions using argument maps still require the YAML/JSON migration above. Rolling back after producing new shell events requires draining those events first.

The dependency upgrade does not implement every new SDK task or protocol. Existing Lemline execution support remains authoritative.
