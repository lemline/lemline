# Open Workflow DSL Supported Features

This reference documents which features of the [Open Workflow DSL v1.0.3](https://github.com/open-workflow-specification/specification/tree/v1.0.3) are currently supported in Lemline, and any implementation-specific details or limitations.

## Implementation Status Overview

Lemline implements a subset of the Open Workflow DSL 1.0.3 specification, with more features being added in each release. This document provides a comprehensive view of what's currently supported and what's planned for future releases.

## Core Features Support

| Feature | Status | Notes |
|---------|--------|-------|
| YAML Definition Format | ✅ Supported | Primary format for workflow definitions |
| JSON Definition Format | ⚠️ Partial | Basic JSON support available |
| Workflow Metadata | ✅ Supported | Full support for namespace, name, version |
| Workflow Input/Output | ✅ Supported | Including schema validation |
| States Definition | ✅ Supported | Via task constructs |
| Expression Language | ✅ Supported | JQ expressions fully supported |
| Workflow Variables | ✅ Supported | Context and scope variables |
| Error Handling | ✅ Supported | Try/catch and retry mechanisms |
| Workflow Timeouts | ⚠️ Partial | Only `listen` task timeouts are enforced; workflow-level timeouts and timeouts on other tasks are ignored. HTTP calls use fixed limits (15 s connect, 30 s per request) |

## Control Flow Support

| Feature | Status | Notes |
|---------|--------|-------|
| Sequential Execution | ✅ Supported | Via `do` construct |
| Parallel Execution | ✅ Supported | Via `fork` construct |
| Switch/Choice | ✅ Supported | Via `switch` construct |
| Loops | ✅ Supported | Via `for` and `while` constructs |
| Error Handling Flow | ✅ Supported | Via `try`/`catch` constructs |
| Event-Based Execution | ✅ Supported | Via `listen` construct |
| Subworkflows | ✅ Supported | Via `run` construct |
| Dynamic Evaluation | ✅ Supported | JQ expressions in most contexts |

## Task Types Support

| Task Type | Status | Notes |
|-----------|--------|-------|
| HTTP Calls | ✅ Supported | Full REST API support |
| OpenAPI Calls | ❌ Not supported | Parsed, but no runtime executor; use `call: http` |
| AsyncAPI Calls | ❌ Not supported | Parsed, but no runtime executor; use `emit` and `listen` |
| gRPC Calls | ❌ Not supported | Parsed, but no runtime executor |
| GraphQL | ⚠️ Planned | On roadmap for future release |
| Expression Evaluation | ✅ Supported | Via `set` construct |
| Container Execution | ❌ Not supported | The SDK parses `run.container`; Lemline has no container executor |
| Script Execution | ⚠️ Partial | JavaScript and Python. See the SDK differences below. |
| Event Emission | ✅ Supported | Via `emit` construct |
| Wait/Delay | ✅ Supported | Via `wait` construct |
| Custom Function Calls | ✅ Supported | Via function catalog |

## Event Features Support

| Feature | Status | Notes |
|---------|--------|-------|
| Event Consumption | ✅ Supported | Various strategies supported |
| Event Correlation | ✅ Supported | Including complex conditions |
| Event Filtering | ✅ Supported | JQ-based filtering |
| Event Timeouts | ✅ Supported | For event waiting operations |
| CloudEvents Format | ✅ Supported | Full CloudEvents compliance |
| Event Batching | ✅ Supported | For efficient processing |

## Error Handling Support

| Feature | Status | Notes |
|---------|--------|-------|
| Error Definition | ✅ Supported | Custom error types |
| Try/Catch | ✅ Supported | Comprehensive error catching |
| Retry Policies | ✅ Supported | Various backoff strategies |
| Custom Error Handling | ✅ Supported | Error-specific handling logic |
| Compensation | ✅ Supported | For transaction rollback |
| Error Propagation | ✅ Supported | Across workflow boundaries |

## Authentication Support

| Feature | Status | Notes |
|---------|--------|-------|
| Basic Auth | ✅ Supported | For `call: http` |
| Bearer Token | ✅ Supported | For `call: http` |
| OAuth2 | ❌ Not yet supported | A call using it fails with a `configuration` error |
| API Keys | ⚠️ No dedicated policy | Pass the key in `headers` or `query`, read from `$secrets` |
| mTLS | ❌ Not supported | |
| OIDC | ❌ Not supported | |
| Digest | ❌ Not supported | |

## Extension and Integration Support

| Feature | Status | Notes |
|---------|--------|-------|
| Custom Extensions | ⚠️ Partial | Basic extension framework available |
| Function Catalog | ✅ Supported | For reusable functions |
| Service Discovery | ⚠️ Partial | Basic support via configuration |
| Metrics Integration | ✅ Supported | Prometheus, JMX, etc. |
| Tracing Integration | ✅ Supported | OpenTelemetry support |
| Kubernetes Integration | ✅ Supported | Native Kubernetes support |

## SDK schema and version support

Use `document.dsl: '1.0.3'` for new definitions. Lemline validates YAML and JSON with the [schema shipped by Java SDK 7.35.3.Final](https://github.com/open-workflow-specification/sdk-java/blob/7.35.3.Final/types/src/main/resources/schema/workflow.yaml). An existing workflow declaring `1.0.0` goes through the same validator.

| Feature | Official 1.0.3 tag | Lemline with the pinned SDK |
|---------|--------------------|-----------------------------|
| `run.script.arguments` | Ordered array of strings | Map of argument names to values |
| `run.script.stdin` | Optional string | Rejected during validation |
| `run.shell.arguments` and `stdin` | Ordered array and optional string | Both supported |
| `evaluate`, `schedule.read`, `catch.then` | Absent from the tagged schema | Supported |

The SDK also accepts a literal array in `for.in` and makes the source optional for emitted events. The tag accepts an expression for `for.in` and requires an explicit event source. In error filters, use `catch.errors.with.detail` (singular) with Lemline; the tagged schema uses `details`.

## Implementation Roadmap

Features planned for upcoming releases:

1. **Complete 1.0 Support**: Full Open Workflow 1.0 specification coverage
2. **Enhanced GraphQL Support**: Native GraphQL integration
3. **Advanced Container Orchestration**: More sophisticated container execution options
4. **Additional Authentication Methods**: Expand authentication options
5. **Enhanced Extension Framework**: More powerful extension mechanisms
6. **Native Service Mesh Integration**: Direct integration with service mesh platforms

## Compatibility Notes

When migrating from other Open Workflow implementations to Lemline, consider:

1. **Expression Evaluation**: Verify JQ expressions for compatibility
2. **Error Handling Logic**: Test error handling behavior
3. **Event Processing**: Validate event processing requirements
4. **Authentication Setup**: Adapt authentication configurations
5. **Extension Usage**: Review any custom extensions

## Testing Specification Conformance

Lemline has no standalone validation command. `lemline definition post` parses each definition file and reports the files it cannot read; run the workflow in a test environment (for example with `lemline listen --mock-config <file>`) to check its behavior.

## Related Information

- [DSL Syntax Reference](lemline-ref-dsl-syntax.md)
- [Task Types Reference](lemline-ref-task-types.md)
- [Configuration Reference](lemline-ref-config.md)
- [Open Workflow Specification v1.0.3](https://github.com/open-workflow-specification/specification/tree/v1.0.3)
