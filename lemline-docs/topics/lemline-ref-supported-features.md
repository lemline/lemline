# Serverless Workflow DSL Supported Features

This reference documents which features of the [Serverless Workflow DSL 1.0 specification](https://serverlessworkflow.io/spec/1.0/) are currently supported in Lemline, and any implementation-specific details or limitations.

## Implementation Status Overview

Lemline implements a subset of the Serverless Workflow DSL 1.0 specification, with more features being added in each release. This document provides a comprehensive view of what's currently supported and what's planned for future releases.

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
| Workflow Timeouts | ✅ Supported | Global and operation-specific timeouts |

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
| OpenAPI Calls | ✅ Supported | Including schema validation |
| AsyncAPI Calls | ✅ Supported | Publish and subscribe operations |
| gRPC Calls | ✅ Supported | Proto-based service calls |
| GraphQL | ⚠️ Planned | On roadmap for future release |
| Expression Evaluation | ✅ Supported | Via `set` construct |
| Container Execution | ⚠️ Partial | Basic support via `run` construct |
| Script Execution | ⚠️ Partial | Basic JavaScript support |
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
| Basic Auth | ✅ Supported | For HTTP calls |
| Bearer Token | ✅ Supported | For HTTP calls |
| OAuth2 | ✅ Supported | Various grant types |
| API Keys | ✅ Supported | Header and query parameter options |
| mTLS | ⚠️ Planned | On roadmap for future release |
| OIDC | ✅ Supported | OpenID Connect integration |

## Extension and Integration Support

| Feature | Status | Notes |
|---------|--------|-------|
| Custom Extensions | ⚠️ Partial | Basic extension framework available |
| Function Catalog | ✅ Supported | For reusable functions |
| Service Discovery | ⚠️ Partial | Basic support via configuration |
| Metrics Integration | ✅ Supported | Prometheus, JMX, etc. |
| Tracing Integration | ✅ Supported | OpenTelemetry support |
| Kubernetes Integration | ✅ Supported | Native Kubernetes support |

## Specification Deviations

In some areas, Lemline introduces minor deviations from the Serverless Workflow specification to enhance usability or performance:

1. **Enhanced JQ Support**: Extended JQ features for more powerful expressions
2. **Simplified Control Flow**: More intuitive control flow constructs
3. **Expanded Error Types**: More granular error type hierarchy
4. **Additional Timeout Options**: More flexible timeout configurations
5. **Expanded Authentication Options**: Additional authentication mechanisms

These deviations maintain compatibility with the core specification while providing additional capabilities.

## Version Support

Lemline currently supports these versions of the Serverless Workflow specification:

- 1.0 (partial support)
- 0.8 (better coverage)
- 0.7 (extensive coverage)

## Implementation Roadmap

Features planned for upcoming releases:

1. **Complete 1.0 Support**: Full Serverless Workflow 1.0 specification coverage
2. **Enhanced GraphQL Support**: Native GraphQL integration
3. **Advanced Container Orchestration**: More sophisticated container execution options
4. **Additional Authentication Methods**: Expand authentication options
5. **Enhanced Extension Framework**: More powerful extension mechanisms
6. **Native Service Mesh Integration**: Direct integration with service mesh platforms

## Compatibility Notes

When migrating from other Serverless Workflow implementations to Lemline, consider:

1. **Expression Evaluation**: Verify JQ expressions for compatibility
2. **Error Handling Logic**: Test error handling behavior
3. **Event Processing**: Validate event processing requirements
4. **Authentication Setup**: Adapt authentication configurations
5. **Extension Usage**: Review any custom extensions

## Testing Specification Conformance

To verify specification conformance for your workflows:

```bash
# Validate workflow against specification
lemline validate -f my-workflow.yaml --spec-version 1.0

# Check for unsupported features
lemline validate -f my-workflow.yaml --strict
```

## Related Information

- [DSL Syntax Reference](lemline-ref-dsl-syntax.md)
- [Task Types Reference](lemline-ref-task-types.md)
- [Configuration Reference](lemline-ref-config.md)
- [Serverless Workflow DSL Specification](https://serverlessworkflow.io/spec/1.0/)