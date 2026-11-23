# Understanding the Open Workflow DSL

> **Runtime support:** this page describes the DSL. The Lemline runtime does not execute `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 / OpenID Connect authentication yet. See [Supported Features](lemline-ref-supported-features.md).


This document explains the Open Workflow Domain Specific Language (DSL), its design principles, and how Lemline implements it.

## What is Open Workflow DSL?

The Open Workflow DSL is a vendor-neutral, platform-independent workflow language hosted by the [Cloud Native Computing Foundation (CNCF)](https://www.cncf.io/projects/open-workflow-specification/). It provides a standard way to define workflow orchestration using a declarative approach.

Key characteristics of the DSL include:

- **Declarative**: Focuses on what needs to be done, not how
- **JSON/YAML based**: Easy to read, write, and process
- **Portable**: Works across different cloud providers and platforms
- **Event-driven**: First-class support for events and messaging
- **Extensible**: Supports custom extensions for vendor-specific features

## Design Principles

### 1. Workflow as Code

The Open Workflow DSL treats workflows as code, enabling:

- **Version control**: Track changes with git or other VCS
- **CI/CD integration**: Automate testing and deployment
- **Review processes**: Code review workflows for changes
- **Reusability**: Define once, use in multiple contexts

### 2. Cloud Native

The DSL is designed to work seamlessly in cloud native environments:

- **Container-friendly**: Runs well in containerized environments
- **Kubernetes integration**: Designed for Kubernetes deployments
- **Scalability**: Supports horizontal scaling
- **Resilience**: Built-in error handling and compensation

### 3. Event-Driven Architecture

Event-driven principles are core to the design:

- **Event consumption**: Rich patterns for consuming events
- **Event correlation**: Match and correlate related events
- **Event production**: Emit events to trigger other processes
- **Event-based coordination**: Coordinate workflows via events

### 4. Function Composition

The DSL enables composition of serverless functions and microservices:

- **Service orchestration**: Coordinate multiple services
- **Function chaining**: Connect function outputs to inputs
- **Parallel execution**: Run functions concurrently
- **Conditional execution**: Apply business logic to function invocation

## A workflow in Lemline

The `document` header identifies the workflow and declares its DSL version. The `do` list defines tasks in execution order. Each task receives the preceding task's output as its input by default.

```yaml
document:
  dsl: '1.0.3'
  namespace: orders
  name: price-order
  version: '1.0.0'
do:
  - price:
      set:
        total: '${ .quantity * .unitPrice }'
  - receipt:
      set:
        amountDue: '${ .total }'
```

With input `{"quantity":3,"unitPrice":4}`, this workflow returns `{"amountDue":12}`. Lemline validates the definition with the Java SDK schema and uses JQ to evaluate the expressions. See the [supported features and SDK differences](lemline-ref-supported-features.md) for the execution capabilities of the runtime.

## Comparison to Other Workflow Languages

### Open Workflow vs. AWS Step Functions

| Feature | Open Workflow | AWS Step Functions |
|---------|---------------------|-------------------|
| Syntax | YAML/JSON | JSON (Amazon States Language) |
| Portability | Vendor-neutral | AWS-specific |
| Expression Language | JQ-based | JSONPath subset |
| Event Support | Rich event patterns | Basic event integration |
| Error Handling | Try-catch with retry | Retry and fallback states |

### Open Workflow vs. Argo Workflows

| Feature | Open Workflow | Argo Workflows |
|---------|---------------------|----------------|
| Focus | Service orchestration | Container orchestration |
| Runtime | Cloud-agnostic | Kubernetes-based |
| Paradigm | Event-driven + sequential | DAG-based tasks |
| State Management | Built-in | Template-based |

### Open Workflow vs. BPMN

| Feature | Open Workflow | BPMN |
|---------|---------------------|------|
| Representation | Code-first (YAML/JSON) | Visual-first (XML) |
| Target Users | Developers | Business + Developers |
| Cloud Native | Yes, by design | Depends on implementation |
| Complexity | Lower | Higher |

## Best Practices

### Workflow Design Principles

1. **Single Responsibility**: Each workflow should have a clear, focused purpose
2. **Idempotency**: Design workflows to be safely retryable
3. **Data Minimalism**: Pass only necessary data between tasks
4. **Explicit Error Handling**: Define how errors should be managed
5. **Event Correlation**: Use correlation identifiers for event matching

### Organizing Complex Workflows

1. **Sub-workflows**: Break large workflows into manageable sub-workflows
2. **Domain Separation**: Group related tasks by domain or function
3. **State Machines**: Use state-based design for complex processes
4. **Event Choreography**: Consider choreography for loosely coupled systems

### Performance Considerations

1. **Parallel Execution**: Use `fork` for concurrent tasks
2. **Data Transformation**: Minimize unnecessary data transformations
3. **Service Integration**: Use appropriate integration patterns (sync/async)
4. **Checkpoint Frequency**: Balance persistence and performance

## Related Resources

- [Open Workflow Specification](https://open-workflow-specification.org/)
- [CNCF Open Workflow Project](https://github.com/open-workflow-specification/specification)
- [JQ Expressions Guide](lemline-howto-jq.md)
- [Workflow Definition Guide](lemline-howto-define-workflow.md)
- [Workflow Execution Model](lemline-explain-execution.md)
