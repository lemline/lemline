---
title: Function Call
---

# Function Call Task (`call`)

## Purpose

The generic `Call` task enables the execution of a specified, named function within a workflow. This allows seamless integration with custom business logic, reusable workflow components, or functions defined externally (e.g., in a [Resource Catalog](dsl-resource-catalog.md)).

*Note: This describes the generic function call using a function name. For specific protocol interactions like HTTP, gRPC, OpenAPI, or AsyncAPI, refer to their dedicated task pages.*

## Usage Example

```yaml
document:
  dsl: '1.0.0'
  namespace: custom_functions
  name: call-custom-example
  version: '1.0.0'
  # Assume 'validateAddress' is defined elsewhere (e.g., in a catalog or built-in)
use:
  functions:
    - name: validateAddress
      # ... definition or reference ...
do:
  - checkAddress:
      call: validateAddress # Name of the function to call
      with: # Arguments passed to the function
        street: "${ .customer.address.street }"
        city: "${ .customer.address.city }"
        zipCode: "${ .customer.address.zip }"
      # Output of the 'validateAddress' function becomes output of this task
      then: processValidationResult
  - processValidationResult:
      # ... uses output from checkAddress ...
```

In this example, the `checkAddress` task calls a function named `validateAddress`, passing arguments derived from the current context or input using the `with` property.

## Additional Examples

### Example: Calling a Function with No Arguments

```yaml
do:
  - triggerProcessing:
      # Assumes 'startBackgroundJob' function requires no specific arguments
      call: startBackgroundJob 
      # No 'with' property needed
      then: monitorJob
  - monitorJob:
      # ...
```

### Example: Calling a Catalog Function

```yaml
# Assumes 'globalUtils' catalog is imported via workflow.use.catalogs
# Assumes 'logMessage:1.0' function exists in that catalog
do:
  - recordInfo:
      call: logMessage:1.0@globalUtils # Function:Version@CatalogName
      with:
        level: INFO
        message: "Processed item ${ .itemId }"
```

## Configuration Options

### `call` (String, Required)

Specifies the **name** of the function to execute. This name must correspond to:

*   A function defined in the workflow's `use.functions` section.
*   A function available from an imported [Resource Catalog](dsl-resource-catalog.md).
*   A built-in function provided by the runtime (like `http`, `grpc`, etc., although using their specific task pages is recommended).

### `with` (Object, Optional)

A simple key/value map defining the arguments to pass to the called function. Values can be static or derived using [Runtime Expressions](dsl-runtime-expressions.md).

### Authentication

The generic function `call` task itself does not have a dedicated `authentication` property within its `with` block. If the function being called requires authentication to perform its internal operations, that logic must be handled *inside* the function's implementation (potentially using secrets or context passed via `with`).

If the function *definition* itself is hosted in a protected location (like a secured [Resource Catalog](dsl-resource-catalog.md)), authentication would be configured on the catalog's `endpoint` definition, not on the `call` task.

**Error Handling**: If authentication fails while trying to *access the function definition* from a protected catalog, the runtime should raise an `Authentication` or `Authorization` error related to accessing the catalog endpoint. Errors related to authentication *within* the called function's logic are the responsibility of the function itself to handle or raise appropriately.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the `Call` task is available for use within runtime expressions in the `with` arguments.
*   The `rawOutput` of the `Call` task is the result returned by the executed function.
*   Standard `output.as` and `export.as` process this function result.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/> 