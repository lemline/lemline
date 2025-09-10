---
title: Who Is Lemline For?
---

# Who Is Lemline For?

Lemline is designed to serve a variety of technical roles and use cases in modern software development. Understanding
where you fit in these personas can help you navigate our documentation more effectively.

## Back-end Developers

### Profile

- Implement business logic and services
- Connect systems and handle data flow
- Work with APIs, events, and messaging
- Care about code quality and maintainability

### How Lemline Helps

- Declarative workflow definitions that are easy to read and maintain
- Simple integration with HTTP, gRPC, and other service types
- Built-in error handling and retry mechanisms
- Clear separation between workflow logic and implementation details

### Documentation Path

1. Start with the [Hello Workflow Tutorial](lemline-tutorial-hello.md)
2. Explore how-to guides for [defining workflows](lemline-howto-define-workflow.md)
   and [making HTTP calls](lemline-howto-http.md)
3. Learn about [passing data between tasks](lemline-howto-data-passing.md)
   and [writing expressions](lemline-howto-jq.md)
4. Reference the [DSL syntax](lemline-ref-dsl-syntax.md) as needed

## Architects Designing Event-Driven Systems

### Profile

- Design system architecture and integration patterns
- Make technology and framework selections
- Balance technical debt, performance, and maintainability
- Establish best practices and standards

### How Lemline Helps

- Database-optional design reduces infrastructure requirements
- Event-driven architecture aligns with modern system design
- Flexible deployment options from single instance to clustered
- Standards-based approach with the Serverless Workflow DSL

### Documentation Path

1. Start with [Why Lemline Exists](lemline-why-exists.md)
2. Understand [How Lemline Executes Workflows](lemline-explain-execution.md)
3. Compare [Performance vs Traditional Engines](lemline-observability-performance.md)
4. Explore [Scaling Lemline Runners](lemline-explain-scaling.md)
5. Review the [Examples Library](lemline-examples-order.md) for architectural patterns

## Platform Engineers

### Profile

- Build and maintain platforms for development teams
- Focus on reliability, observability, and scalability
- Manage infrastructure and operational concerns
- Implement CI/CD pipelines and monitoring

### How Lemline Helps

- Reduced database overhead means lower operational costs
- Horizontally scalable to handle varying workloads
- Built-in metrics for monitoring and alerting
- Consistent logging and observability patterns
- Native support for containerization and cloud deployment

### Documentation Path

1. Begin with [Runner Configuration](lemline-howto-config.md)
2. Learn about [connecting to brokers](lemline-howto-brokers.md)
3. Set up [observability](lemline-howto-observability.md) for your environment
4. Study the [infrastructure sizing guidelines](lemline-observability-sizing.md)
5. Reference [environment variables](lemline-ref-env-vars.md) and [metrics](lemline-ref-metrics.md)

## Integration Specialists

### Profile

- Connect disparate systems and services
- Work with multiple protocols and data formats
- Handle authentication and security concerns
- Build reliable and maintainable integrations

### How Lemline Helps

- First-class support for HTTP, OpenAPI, gRPC, and AsyncAPI
- Consistent authentication across different protocols
- Built-in retry and error handling for unreliable services
- Simplified event correlation for complex integration scenarios

### Documentation Path

1. Start with task-specific guides for [HTTP](lemline-howto-http.md), [OpenAPI](lemline-howto-openapi.md),
   and [gRPC](lemline-howto-grpc.md)
2. Learn about [OAuth2 configuration](lemline-howto-oauth2.md) and [secrets management](lemline-howto-secrets.md)
3. Explore [event handling](lemline-howto-events.md) for asynchronous integration
4. Review the [Multi-step API Orchestration](lemline-examples-api.md) example

## Finding Your Path

Regardless of your role, we recommend starting with:

1. [What Is Lemline?](lemline-what-is.md) - For a high-level overview
2. [Getting Started Fast](lemline-getting-started.md) - For quick hands-on experience
3. [Documentation Guide](lemline-doc-guide.md) - To navigate our documentation effectively

Then, follow the specific path that best matches your role and needs. If you're working across multiple roles, mix and
match guidance from different sections as appropriate for your project.
