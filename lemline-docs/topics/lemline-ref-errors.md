# Error Reference

This reference documents all standard error types, error codes, and error handling capabilities in Lemline.

## Standard Error Types

Lemline implements the standardized error types defined in the Serverless Workflow specification, with the base URI `https://serverlessworkflow.io/spec/1.0.0/errors/`:

| Error Type | URI | Default Status | Description |
|------------|-----|----------------|-------------|
| Configuration | `https://serverlessworkflow.io/spec/1.0.0/errors/configuration` | 400 | Errors in workflow definition or structure |
| Validation | `https://serverlessworkflow.io/spec/1.0.0/errors/validation` | 400 | Input/output data schema validation failures |
| Expression | `https://serverlessworkflow.io/spec/1.0.0/errors/expression` | 400 | Expression evaluation errors (JQ syntax or execution) |
| Authentication | `https://serverlessworkflow.io/spec/1.0.0/errors/authentication` | 401 | Authentication failures |
| Authorization | `https://serverlessworkflow.io/spec/1.0.0/errors/authorization` | 403 | Authorization/permission failures |
| Timeout | `https://serverlessworkflow.io/spec/1.0.0/errors/timeout` | 408 | Timeout violations (workflow or task) |
| Communication | `https://serverlessworkflow.io/spec/1.0.0/errors/communication` | 500 | Errors communicating with external services |
| Runtime | `https://serverlessworkflow.io/spec/1.0.0/errors/runtime` | 500 | General runtime execution errors |

## Error Structure

All errors in Lemline follow the RFC 7807 Problem Details format:

```json
{
  "type": "https://serverlessworkflow.io/spec/1.0.0/errors/validation",
  "status": 400,
  "instance": "/do/0/validateInput",
  "title": "Input Validation Failed",
  "details": "The 'amount' field must be a positive number"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | URI reference identifying the problem type |
| `status` | Yes | HTTP status code (numeric) corresponding to the error |
| `instance` | Yes | JSON pointer to the location in the workflow where the error occurred |
| `title` | No | Short, human-readable summary of the problem |
| `details` | No | Detailed explanation specific to this error occurrence |

## Common Error Scenarios and Codes

### Configuration Errors (400)

| Error Code | Description | Example |
|------------|-------------|---------|
| CONFIG001 | Missing required property | "Required property 'name' is missing in workflow definition" |
| CONFIG002 | Invalid property value | "The value 'invalid' is not a valid value for property 'method', expected one of: GET, POST, PUT, DELETE" |
| CONFIG003 | Invalid reference | "Reference to non-existent authentication 'serviceAuth'" |
| CONFIG004 | Circular reference | "Circular reference detected: Task 'A' refers to task 'B' which refers back to 'A'" |
| CONFIG005 | Duplicate definition | "Duplicate task name: 'processOrder' is defined multiple times" |
| CONFIG006 | Invalid schema | "Invalid JSON Schema: $ref resolves to unknown schema" |
| CONFIG007 | Incompatible types | "Cannot assign string value to numeric property 'retryCount'" |

### Validation Errors (400)

| Error Code | Description | Example |
|------------|-------------|---------|
| VALID001 | Schema validation failure | "Input data does not match schema: 'quantity' must be a positive integer" |
| VALID002 | Required field missing | "Required field 'orderId' is missing in input data" |
| VALID003 | Type mismatch | "Expected type 'number' for property 'price', but got 'string'" |
| VALID004 | Value out of range | "Value 1001 exceeds maximum allowed value of 1000 for property 'quantity'" |
| VALID005 | Invalid format | "Value 'not-an-email' does not match format 'email' for property 'contact'" |
| VALID006 | Pattern mismatch | "Value 'ABC123' does not match pattern '^[0-9]{6}$' for property 'zipCode'" |
| VALID007 | Enum value mismatch | "Value 'SHIPPED' is not one of the allowed values for property 'status': PENDING, PROCESSING, COMPLETED" |

### Expression Errors (400)

| Error Code | Description | Example |
|------------|-------------|---------|
| EXPR001 | Syntax error | "Syntax error in JQ expression: Missing closing bracket" |
| EXPR002 | Undefined variable | "Variable '.customer' is not defined in the current context" |
| EXPR003 | Type error | "Cannot multiply a string by a number: '.qty * .price'" |
| EXPR004 | Divide by zero | "Division by zero in expression '.total / .count'" |
| EXPR005 | Invalid path | "Path '.items[100]' is out of bounds for array of length 10" |
| EXPR006 | Function error | "Unknown function 'unknown_function()'" |
| EXPR007 | Context error | "Empty context provided for expression evaluation" |

### Authentication Errors (401)

| Error Code | Description | Example |
|------------|-------------|---------|
| AUTH001 | Missing credentials | "No credentials provided for authentication 'apiAuth'" |
| AUTH002 | Invalid credentials | "Invalid API key provided" |
| AUTH003 | Expired token | "OAuth token has expired" |
| AUTH004 | Token retrieval failed | "Failed to obtain OAuth token: invalid_client" |
| AUTH005 | Authentication rejected | "Authentication was rejected by the server: invalid_grant" |
| AUTH006 | Missing secret | "Secret 'api.key' not found" |
| AUTH007 | OAuth error | "OAuth error: unauthorized_client" |

### Authorization Errors (403)

| Error Code | Description | Example |
|------------|-------------|---------|
| AUTHZ001 | Insufficient permissions | "Insufficient permissions to access resource '/restricted'" |
| AUTHZ002 | Scope error | "Required scope 'write' not granted" |
| AUTHZ003 | Resource access denied | "Access to resource 'orders/123' is denied" |
| AUTHZ004 | Rate limited | "API rate limit exceeded, retry after 60 seconds" |
| AUTHZ005 | IP restricted | "Access from IP address 192.168.1.1 is not allowed" |
| AUTHZ006 | Account suspended | "User account is suspended or inactive" |
| AUTHZ007 | Resource unavailable | "Resource 'reports' is not available for the current plan" |

### Timeout Errors (408)

| Error Code | Description | Example |
|------------|-------------|---------|
| TIMEOUT001 | Workflow timeout | "Workflow execution exceeded maximum allowed time of PT1H" |
| TIMEOUT002 | Task timeout | "Task 'processPayment' exceeded its timeout of PT30S" |
| TIMEOUT003 | HTTP request timeout | "HTTP request to 'https://api.example.com' timed out after 10 seconds" |
| TIMEOUT004 | Event wait timeout | "Waiting for event 'OrderApproved' timed out after PT1H" |
| TIMEOUT005 | Database timeout | "Database query timed out after 30 seconds" |
| TIMEOUT006 | Lock acquisition timeout | "Failed to acquire lock 'order-123' within 10 seconds" |
| TIMEOUT007 | Service call timeout | "Call to service 'inventory' timed out after 15 seconds" |

### Communication Errors (500)

| Error Code | Description | Example |
|------------|-------------|---------|
| COMM001 | Connection failed | "Failed to connect to 'api.example.com': Connection refused" |
| COMM002 | Service unavailable | "Service 'payment-gateway' returned status 503 Service Unavailable" |
| COMM003 | Invalid response | "Invalid response received from 'inventory-service': Not a valid JSON object" |
| COMM004 | DNS resolution failure | "Failed to resolve hostname 'api.example.com'" |
| COMM005 | TLS/SSL error | "TLS handshake failed with 'secure-api.example.com': certificate validation error" |
| COMM006 | Network error | "Network error: Connection reset" |
| COMM007 | Protocol error | "HTTP/2 protocol error: PROTOCOL_ERROR" |

### Runtime Errors (500)

| Error Code | Description | Example |
|------------|-------------|---------|
| RUNTIME001 | Internal error | "Internal error: Unexpected condition in workflow execution" |
| RUNTIME002 | Resource exhaustion | "Out of memory error during workflow execution" |
| RUNTIME003 | Concurrency error | "Concurrent modification of workflow state" |
| RUNTIME004 | State corruption | "Workflow state corrupted: Invalid node reference" |
| RUNTIME005 | System error | "System error: Failed to write to disk" |
| RUNTIME006 | Extension error | "Error in extension 'customTask': Failed to load extension class" |
| RUNTIME007 | Undefined operation | "Operation 'calculateTax' is not defined" |

## CLI Error Codes

Lemline CLI commands may return the following error codes:

| Exit Code | Description |
|-----------|-------------|
| 0 | Success (no error) |
| 1 | General error |
| 2 | Command line usage error |
| 3 | Configuration error |
| 4 | Validation error |
| 5 | Connection error |
| 6 | Authentication/authorization error |
| 7 | Workflow not found error |
| 8 | Instance not found error |
| 9 | Execution error |
| 10 | Timeout error |
| 11 | Conflict error |
| 20 | Internal error |

## HTTP API Error Responses

When using the HTTP API, error responses follow this format:

```json
{
  "timestamp": "2023-05-15T10:30:45.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Input validation failed",
  "path": "/workflows/instances",
  "details": {
    "type": "https://serverlessworkflow.io/spec/1.0.0/errors/validation",
    "instance": "/do/0/validateInput",
    "errors": [
      {
        "field": "order.quantity",
        "message": "must be a positive integer",
        "value": -5
      }
    ]
  }
}
```

## Java Exception Hierarchy

For developers extending Lemline, the Java exception hierarchy is:

```
java.lang.Exception
└── com.lemline.core.errors.WorkflowException
    ├── com.lemline.core.errors.ConfigurationException
    ├── com.lemline.core.errors.ValidationException
    ├── com.lemline.core.errors.ExpressionException
    ├── com.lemline.core.errors.AuthenticationException
    ├── com.lemline.core.errors.AuthorizationException
    ├── com.lemline.core.errors.TimeoutException
    ├── com.lemline.core.errors.CommunicationException
    └── com.lemline.core.errors.RuntimeException
```

## Defining Custom Errors

Custom error types can be defined in workflow definitions:

```yaml
use:
  errors:
    - name: "paymentDeclined"
      type: "https://example.com/errors/payment-declined"
      status: 400
      title: "Payment Method Declined"
      details: "The payment processor rejected the transaction"
    
    - name: "inventoryShortage"
      type: "https://example.com/errors/inventory-shortage"
      status: 409
      title: "Insufficient Inventory"
```

## Error Handling Examples

### Basic Try-Catch

```yaml
- secureOperation:
    try:
      do:
        - riskyTask:
            # Task that might fail
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
            as: "timeoutError"
          do:
            - handleTimeout:
                # Error handling tasks
```

### Matching Multiple Error Types

```yaml
catch:
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
        status: 429
      do:
        # Handle rate limiting
  
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
        status: 503
      do:
        # Handle service unavailable
```

### Using Retry Policies

```yaml
try:
  retry:
    policy:
      strategy: backoff
      backoff:
        delay: PT1S
        multiplier: 2
      limit:
        attempt:
          count: 3
      when: "${ .error.status >= 500 }"
  do:
    # Tasks that might temporarily fail
```

## Error Debugging 

When debugging errors, you can use these CLI commands:

```bash
# View detailed error information for a workflow instance
lemline instances errors get <instance-id>

# View workflow execution history with error details
lemline instances history <instance-id>

# View detailed logs related to errors
lemline logs --level ERROR --workflow <workflow-id>
```

## Error Handling Best Practices

1. **Be specific with error types**: Catch specific error types rather than all errors
2. **Provide fallbacks**: Use default values or alternative paths for common errors
3. **Use conditional retries**: Only retry errors that have a chance of succeeding on retry
4. **Include context in errors**: Use descriptive error messages with relevant details
5. **Log error details**: Log sufficient context for debugging
6. **Use appropriate error status codes**: Match HTTP status codes to semantics
7. **Implement circuit breakers**: Prevent cascading failures
8. **Define custom error types**: Create domain-specific error types for your application
9. **Organize errors hierarchically**: Use consistent type URIs that reflect hierarchy

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Implementing Retry Mechanisms](lemline-howto-retry.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Debugging Workflows](lemline-howto-debug.md)
- [Resilience Patterns](dsl-resilience-patterns.md)