# Call Tasks
<!-- Examples are validated -->

## Purpose & Concept

The core concept of a "Call" task in the Serverless Workflow DSL is to enable interaction with external services, functions, or APIs. These tasks are the primary mechanism for orchestrating communication beyond the workflow's internal state management and control flow.

Different types of `call` tasks cater to specific protocols or interaction patterns, providing a structured way to define how the workflow communicates with the outside world.

Think of `call` tasks as the workflow's way of making requests or invoking functionality that resides outside of its own definition. This is crucial for integrating with existing systems, leveraging microservices, or accessing third-party APIs.

## Types of Call Tasks

*   **`call: <functionName>`**:
    *   Invokes a named function defined either within the workflow (`use.functions`), imported from a [Resource Catalog](dsl-resource-catalog.md), or potentially provided by the runtime.
    *   This is the most generic call type, suitable for custom logic or reusable components.
    *   Configuration and arguments are passed via the `with` property.
    *   See: [Function Call Task](dsl-call-function.md)

*   **`call: http`**:
    *   Performs standard HTTP/HTTPS requests (GET, POST, PUT, etc.) to interact with web services or REST APIs.
    *   Essential for web-based integrations.
    *   Configuration (method, endpoint, headers, body, query parameters, etc.) is provided within the `with` property.
    *   See: [HTTP Call Task](dsl-call-http.md)

*   **`call: grpc`**:
    *   Interacts with services using the high-performance gRPC protocol, typically requiring a Protobuf definition.
    *   Suitable for efficient, low-latency communication between microservices.
    *   Configuration (proto definition, service details, method, arguments) is provided within the `with` property.
    *   See: [gRPC Call Task](dsl-call-grpc.md)

*   **`call: openapi`**:
    *   Interacts with RESTful APIs described by an OpenAPI specification document.
    *   Leverages the OpenAPI document for operation details, validation, and parameter handling, promoting type safety.
    *   Configuration (OpenAPI document reference, operationId, parameters) is provided within the `with` property.
    *   See: [OpenAPI Call Task](dsl-call-openapi.md)

*   **`call: asyncapi`**:
    *   Interacts with message brokers or event-driven services described by an AsyncAPI specification document.
    *   Used for publishing messages to channels or subscribing to messages from channels in event-driven architectures.
    *   Configuration (AsyncAPI document reference, operationId, message/subscription details) is provided within the `with` property.
    *   See: [AsyncAPI Call Task](dsl-call-asyncapi.md)

## Common Concepts

While each call type has specific configuration options detailed on its respective page, they share common structural patterns and workflow interactions:

*   **`with` property**: This object is universally used to contain the specific configuration parameters and arguments required by the call type. It acts as the payload defining the details of the external interaction.
*   **Data Flow**: They generally follow the standard [Task Data Flow](dsl-data-flow.md), where `input.from` prepares data for the task, the call is made using configurations from `with` (often utilizing the transformed input), and the result (`rawOutput`) is processed by `output.as` and `export.as`.
*   **Error Handling**: Failures during communication (e.g., network errors, non-success status codes, connection timeouts) typically raise a [`Communication`](dsl-error-handling.md#standard-error-types) error. Failures related to invalid requests based on specifications (like OpenAPI) might raise a `Validation` error. These errors can be caught and handled using a [Try Task](dsl-task-try.md).
*   **Authentication**: Most call types support an `authentication` property within their `with` block (or on referenced resources like `endpoint`), allowing secure interaction with protected services using mechanisms defined in the workflow's `use.authentications` section.

Please refer to the individual task pages linked above for detailed configuration options, examples, and specific behaviors. 