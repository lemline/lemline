# Call Function Feature

The **Call task** is the primary mechanism for invoking services and functions in Serverless Workflow DSL 1.0. It's one
of the core task types that "must be supported by all runtimes."

## Two Categories of Calls

### 1. Built-in Protocol Calls

Reserved call types for standard service integration:

- `call: http` - REST/HTTP endpoints
- `call: openapi` - OpenAPI-documented services
- `call: grpc` - gRPC services
- `call: asyncapi` - Asynchronous messaging
- `call: a2a` - Application-to-application (AI agents)
- `call: mcp` - Model Context Protocol

### 2. Custom Function Calls

When `call` is **not** one of the reserved protocol names, it references a custom function:

```yaml
call: myFunctionName  # Named function
call: https://example.com/function.yaml  # URL reference
call: logMessage:1.0.0@default  # using default Catalog 
call: logMessage:1.0.0@myCompanyCatalog  # using custom Catalog 
```

## Syntax

```yaml
taskName:
    call: <functionReference>
    with:
        param1: value1
        param2: "${ .data.field }"  # Expressions supported
```

## Defining Reusable Functions

Functions are defined in the workflow's `use.functions` section:

```yaml
document:
    dsl: '1.0.0'
    namespace: samples
    name: example
    version: '0.1.0'

use:
    functions:
        getPetById:
            input:
                schema:
                    document:
                        type: object
                        properties:
                            petId:
                                type: integer
                        required: [ petId ]
            call: http
            with:
                method: get
                endpoint: https://petstore.swagger.io/v2/pet/{petId}

do:
    -   getPet:
            call: getPetById
            with:
                petId: 69
```

## Calling Functions from URLs

You can call functions directly using a URL pointing to a function definition file:

```yaml
do:
    -   log:
            call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
            with:
                message: Hello, world!
                level: information
```

### GitHub URL Transformation

Runtimes must automatically convert GitHub repository URLs to raw content URLs. This transformation ensures runtimes can
retrieve and process the actual content of the resource definitions in a machine-readable format.

For example, a function URL like:

```
https://github.com/serverlessworkflow/catalog/tree/main/functions/log/1.0.0/function.yaml
```

Is automatically transformed to:

```
https://raw.githubusercontent.com/serverlessworkflow/catalog/refs/heads/main/functions/log/1.0.0/function.yaml
```

This allows workflow authors to use standard GitHub URLs without worrying about the raw content format.

## Calling Functions from Catalogs

Catalogs are collections of reusable functions that can be referenced by name and version. Functions are referenced
using the format: `{functionName}:{functionVersion}@{catalogName}`

### Default Catalog

Runtimes may optionally provide a **default catalog** that workflows can use implicitly. The default catalog uses the
reserved name `default`:

```yaml
do:
    -   log:
            call: log:1.0.0@default
            with:
                message: Using the runtime's default catalog
```

The name `default` should not be used by catalogs explicitly defined in the workflow, unless the intent is to override
the runtime's default catalog. How resources in the default catalog are stored and resolved is entirely up to the
runtime implementation (database, files, configuration, or remote repository).

### Defining Custom Catalogs

Workflows can define custom catalogs under the `use.catalogs` section with an endpoint and optional authentication:

```yaml
use:
    catalogs:
        # A custom catalog with basic authentication
        myCompanyCatalog:
            endpoint:
                uri: https://github.com/mycompany/workflow-functions
                authentication:
                    basic:
                        username: user
                        password: ${CATALOG_PASSWORD}

do:
    -   step1:
            # Uses the custom catalog
            call: validateOrder:2.0.0@myCompanyCatalog
            with:
                orderId: "${ .order.id }"

    -   step2:
            # Uses the default catalog (official Serverless Workflow catalog)
            call: log:1.0.0@default
            with:
                message: Order validated successfully
```

### Catalog Endpoint Properties

| Property         | Type   | Required | Description                                          |
|------------------|--------|----------|------------------------------------------------------|
| `uri`            | string | yes      | The base URI of the catalog repository               |
| `authentication` | object | no       | Authentication configuration (basic, bearer, oauth2) |

### Authentication Options

```yaml
# Basic authentication
authentication:
    basic:
        username: user
        password: secret

# Bearer token
authentication:
    bearer:
        token: ${API_TOKEN}

# OAuth2
authentication:
    oauth2:
        authority: https://auth.example.com
        grant: client_credentials
        client:
            id: ${CLIENT_ID}
            secret: ${CLIENT_SECRET}
```

## Key Features

| Feature                  | Example                                     |
|--------------------------|---------------------------------------------|
| **Named functions**      | `call: validateAddress`                     |
| **URL functions**        | `call: https://example.com/func.yaml`       |
| **Catalog functions**    | `call: log:1.0@catalog`                     |
| **Expression arguments** | `with: { street: "${ .customer.street }" }` |

## Function Definition Structure (in catalogs)

```yaml
input:
    schema:
        document:
            type: object
            properties:
                message: { type: string }
            required: [ message ]

output:
    schema:
        document:
            type: string

run:
    script:
        language: python
        arguments:
            msg: "${ .message }"
        code: |
            print(msg)
```

## Lemline Execution Architecture

### Design Principles

Function calls in Lemline are designed to be **synchronous inline operations** that:

1. Execute step-by-step through the normal message flow (not in-memory loops)
2. Share context naturally with the calling workflow
3. Require no database persistence (unlike child workflows with `RunWorkflow`)
4. Support recursion without stack overflow (call depth lives in message queue)

### Node Grafting Model

Functions are executed by **grafting** their task nodes into the calling workflow's execution path. This differs from
treating functions as separate sub-workflows.

**Key insight**: `CallFunction` is a **control-flow task** (like `Do`, `For`, `Try`) that navigates to children, not an
async activity that suspends and resumes.

### Position Scheme

Function invocations use the `_fn` token to mark the boundary between the calling context and the function body:

```
/do/0/calculate/_fn/do/0/step1
         │       │      │
         │       │      └── Position within the function's task tree
         │       └── Function body marker
         └── CallFunction node in parent workflow
```

For recursive functions, each invocation adds another `_fn` segment:

```
/do/0/factorial/_fn/do/1/recurse/_fn/do/1/recurse/_fn/do/0/baseCase
```

This position encodes the **complete call stack**: three levels of recursion into the `factorial` function.

### Tree Caching Strategy

Lemline maintains two separate caches:

| Cache | Contents | Lifecycle |
|-------|----------|-----------|
| **Workflow tree cache** | Static node trees for workflow definitions | Loaded once per workflow definition |
| **Function tree cache** | Node trees for function definitions | Lazily built when a function is first invoked by any instance |

Function trees are **not** grafted into the workflow tree at parse time. This avoids infinite expansion for
self-referencing recursive functions.

### Position Resolution

When resuming execution from a position like `/do/0/calc/_fn/do/1/recurse/_fn/do/0/base`, the resolver:

1. Splits the position by `_fn` tokens
2. Resolves each segment against the appropriate tree:
    - First segment (`/do/0/calc`) → workflow tree → finds `CallFunction` node
    - Second segment (`/do/1/recurse`) → function tree for `calc`'s target → finds nested `CallFunction`
    - Third segment (`/do/0/base`) → function tree → finds the actual task node
3. Returns the final node for execution

### Context Sharing

Because function execution stays within the same workflow instance:

- The `$context` variable is shared between caller and function
- Function exports to context are immediately visible to the caller
- No serialization/deserialization of context across boundaries

### Error Propagation

Grafted function nodes participate in normal error handling:

```yaml
do:
    - outer:
          try:
              - calc:
                    call: riskyFunction  # Errors here bubble up to outer's try/catch
          catch:
              # Catches errors from inside the function
```

Errors at position `/do/0/outer/try/do/0/calc/_fn/do/0/failingStep` naturally propagate up to the `try` block at
`/do/0/outer`.

### Comparison with RunWorkflow

| Aspect | CallFunction | RunWorkflow |
|--------|--------------|-------------|
| Execution model | Inline (same message flow) | Child workflow (separate instance) |
| Database persistence | None | Parent state saved to `lemline_parents` |
| Context sharing | Shared `$context` | Isolated (input/output only) |
| Error handling | Bubbles to caller's try/catch | Captured as child workflow failure |
| Position scheme | `_fn` segments in same position | Separate workflow with own positions |

## References

- [Serverless Workflow Specification - GitHub](https://github.com/serverlessworkflow/specification)
- [Serverless Workflow DSL Reference](https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md)
- [Serverless Workflow Catalog](https://github.com/serverlessworkflow/catalog)
- [Serverless Workflow 1.0.0 Release](https://serverlessworkflow.io/blog/releases/release-100/)
- [Serverless Workflow Official Site](https://serverlessworkflow.io/)
