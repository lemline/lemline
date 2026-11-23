# Serialization Reference

This reference describes the data formats Lemline reads and writes. None of them is configurable: there are no `lemline.serialization.*` or schema-registry settings.

## Serialization Overview

| What | Format |
|------|--------|
| Workflow definitions | YAML or JSON |
| Workflow data (input, output, task results, context) | JSON values |
| Messages between runners (commands and events) | Protocol Buffers, binary on the broker |
| Workflow state stored in the database | Protocol Buffers as JSON (ProtoJSON) |
| Events emitted and received by workflows | CloudEvents in JSON |
| HTTP request bodies | JSON |
| JSON Schemas (`input.schema`, `output.schema`, `export.schema`) | JSON Schema draft 2020-12, written in JSON or YAML |

XML, Avro, CBOR and MessagePack are not supported, and there is no schema registry integration.

## Workflow Data

Workflow data is always a JSON value: object, array, string, number, boolean or `null`. Expressions (jq) read and produce JSON values.

- **Numbers** keep their decimal precision.
- **Dates and times** are strings; Lemline does not convert them to another type.
- **Binary data** is represented as a Base64 string, for example the body of an HTTP response returned with `output: raw`.

## HTTP Content Types

For `call: http` tasks:

- The request body is sent as JSON with `Content-Type: application/json`, for `post`, `put` and `patch` only.
- With `output: content` (the default), a response whose content type contains `json` is parsed as JSON, one that contains `text` becomes a string, and anything else becomes a Base64 string.

See [HTTP Protocol Reference](lemline-ref-http.md).

## Messaging

Runners exchange workflow commands and events as Protocol Buffers messages carrying the serialized workflow state, so any runner can continue any instance. The format is internal to Lemline: only Lemline runners and the gateway read these destinations.

CloudEvents emitted by `emit` tasks and consumed by `listen` tasks use the JSON format of the CloudEvents specification.

## Schema Validation

<code-block lang="yaml" src="workflows/reference/customer-input-schema.yaml" include-lines="6-14"/>

Data that does not match its schema raises a `validation` error. External schemas referenced by URL are fetched at validation time.

## Error Handling

| Situation | Result |
|-----------|--------|
| Invalid workflow definition | `lemline definition post` rejects the file |
| Data does not match a schema | `validation` error |
| An expression fails | `expression` error |
| A message cannot be decoded by a runner | The message goes to the dead-letter destination of its source |

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Message Brokers](lemline-howto-brokers.md)
- [Data Flow](dsl-data-flow.md)
