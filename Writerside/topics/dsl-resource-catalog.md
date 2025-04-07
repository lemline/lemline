---
title: Resource Catalogs
---

# Resource Catalogs

## Purpose

A **Resource Catalog** is an external collection of reusable components, primarily [Functions](dsl-call-function.md) in current usage, that can be referenced and imported into Serverless Workflows.

The main goals of using catalogs are:

*   **Reusability**: Define common components (like functions) once and use them across multiple workflows.
*   **Modularity**: Keep complex or domain-specific logic separate from the main workflow definition.
*   **Versioning**: Manage different versions of reusable components independently.
*   **Consistency**: Ensure that multiple workflows use the same implementation of shared logic.
*   **Discovery**: Provide a central place to find available reusable components.

## Defining and Using Catalogs

Catalogs are defined in the workflow's `use.catalogs` section. Each catalog requires a unique name within the workflow and an `endpoint` specifying its location.

### Workflow Structure

```yaml
document:
  dsl: '1.0.0'
  namespace: my_workflows
  name: workflow-using-catalog
  version: '1.0.0'
use:
  catalogs: # Defines named catalogs
    <catalogName1>: # User-defined name for the catalog
      endpoint: # Definition of where the catalog is located
        uri: <catalog_root_uri> # URI (e.g., URL, file path) of the catalog root
        authentication: <authentication_definition_or_ref> # Optional authentication for the endpoint
    <catalogName2>:
      endpoint: # ... another catalog definition ...
do:
  - # ... tasks ...
```

**Key Properties (from `dsl-reference.md`):**

*   **`use.catalogs`** (Map<String, Catalog>): A map where each key is a user-defined name for the catalog (e.g., `sharedUtils`, `publicApis`) and the value is a `Catalog` object definition.
*   **`Catalog` Object**: Defines a catalog resource.
    *   **`endpoint`** (Object, Required): Defines the location and access method for the catalog. Contains:
        *   `uri` (String | Object, Required): The URI (as a string or URI template object) pointing to the root of the catalog.
        *   `authentication` (String | Object, Optional): Authentication details (inline definition or reference by name) needed to access the catalog's `endpoint` itself.

### Calling Cataloged Functions

While catalogs can potentially hold various resources, the primary use case currently detailed is calling functions. To call a function defined within a catalog, you use a specific format in the `call` property of a task:

`{functionName}:{functionVersion}@{catalogName}`

*   `functionName`: The name of the function within the catalog.
*   `functionVersion`: The specific semantic version of the function to use.
*   `catalogName`: The name given to the catalog in the `use.catalogs` section.

**Example:**

```yaml
do:
  - processSharedLogic:
      # Calls version '1.2.0' of 'dataProcessor' function
      # from the catalog named 'sharedUtils'
      call: dataProcessor:1.2.0@sharedUtils
      with:
        config: "${ .processingConfig }"
        inputData: "${ .rawInput }"
```

## Catalog Structure

To ensure portability and allow runtimes to consistently locate resources, catalogs hosted externally (especially in Git repositories) are expected to follow a specific file structure. The Serverless Workflow specification recommends a structure like this (refer to the [official catalog structure documentation](https://github.com/serverlessworkflow/catalog?tab=readme-ov-file#structure) for precise details):

```
my-catalog-repo/
├── functions/
│   ├── functionA/
│   │   ├── 1.0.0/
│   │   │   └── function.yaml  # Definition for v1.0.0
│   │   └── 1.1.0/
│   │       └── function.yaml  # Definition for v1.1.0
│   └── functionB/
│       └── 0.5.2/
│           └── function.yaml
├── authentications/ # Example for other potential resource types
│   └── mySharedAuth/
│       └── authentication.yaml 
└── README.md
```

### Runtime Resolution (Git Repositories)

When a catalog endpoint points to a Git repository (like GitHub or GitLab), runtimes are expected to resolve the *raw* content URLs for the definition files (e.g., `function.yaml`, `authentication.yaml`).

For example, if the catalog endpoint is `https://github.com/my-org/catalog/tree/main` and you call `log:1.0.0@myCatalog`, the runtime should look for the definition at a path like `functions/log/1.0.0/function.yaml` within that repository and fetch its raw content (e.g., from `https://raw.githubusercontent.com/my-org/catalog/main/functions/log/1.0.0/function.yaml`).

## Default Catalog

Runtimes *may* provide a **Default Catalog**. This is a special, implicitly available catalog that doesn't need to be explicitly defined in `use.catalogs`. It allows runtime administrators or platform providers to make common functions (or potentially other resources) readily available to all workflows without extra configuration.

To call a function from the default catalog, use the reserved name `default` as the catalog name:

`{functionName}:{functionVersion}@default`

**Example:**

```yaml
do:
  - logInfo:
      # Assumes 'logMessage:1.0' exists in the runtime's default catalog
      call: logMessage:1.0@default
      with:
        level: INFO
        text: "Task completed successfully."
```

How the runtime manages and resolves resources in the default catalog is implementation-specific (e.g., database, local files, pre-configured remote repository).

## Additional Examples

### Example: Defining Multiple Catalogs with Authentication

```yaml
document:
  dsl: '1.0.0'
  namespace: multi_catalog_example
  name: process-with-shared-and-private
  version: '1.0.0'
use:
  secrets: [ gitHubToken, privateCatalogKey ]
  catalogs:
    # Public, well-known catalog (e.g., official Serverless Workflow catalog)
    swPublic:
      endpoint:
        uri: https://github.com/serverlessworkflow/catalog
        # Maybe requires a token for higher rate limits or private access within org
        authentication:
          bearer: ${ $secrets.gitHubToken }
    # Internal, private catalog hosted on a company server
    internalTools:
      endpoint:
        uri: https://git.mycompany.com/workflow-tools
        authentication:
          basic:
            username: workflow-runner
            password: ${ $secrets.privateCatalogKey }
do:
  - initialLog:
      # Using a function from the public catalog
      call: log:0.5.2@swPublic
      with:
        message: "Starting process"
  - runInternalTool:
      # Using a function from the internal catalog
      call: data-validator:2.1.0@internalTools
      with:
        input: "${ .rawData }"
```

### Example: Using a Local File-Based Catalog

```yaml
# Assume catalog functions are defined in /opt/workflow-catalogs/local-utils
document:
  dsl: '1.0.0'
  namespace: local_dev
  name: test-local-catalog
  version: '1.0.0'
use:
  catalogs:
    local:
      endpoint:
        # Path accessible by the workflow runtime
        uri: file:///opt/workflow-catalogs/local-utils
do:
  - formatData:
      # Call function from the local file system catalog
      call: formatter:1.0.0@local
      with:
        value: "${ .inputString }"
```

## Creating and Publishing Resources

The process for creating and sharing resources involves defining the resource in a structured way and making it accessible via a catalog endpoint.

While the most concrete example provided by the specification is for [Functions](dsl-call-function.md), the general steps apply to any resource type potentially supported by a catalog:

1.  **Define the Resource**: Create a definition file (e.g., `function.yaml` for functions) that describes the resource according to the Serverless Workflow specification. This file typically includes:
    *   Metadata (like name, version, description).
    *   Input/Output schemas (if applicable, highly recommended for clarity and validation).
    *   The core configuration or logic of the resource (e.g., the `run` definition for a function, the properties of an authentication policy).

2.  **Structure the Catalog**: Place the definition file within a directory structure that follows the recommended [Catalog Structure](#catalog-structure). This typically involves grouping resources by type (e.g., `functions/`, `authentications/`) and then by name and version.

3.  **Host the Catalog**: Make the catalog directory structure accessible via a URI (e.g., host it in a Git repository, on a web server, or in a shared file system accessible by the runtime).

4.  **Publish (Optional)**: 
    *   For broader visibility and reuse, consider contributing your resource definitions to a public catalog, like the official [Serverless Workflow Catalog](https://github.com/serverlessworkflow/catalog).
    *   For internal use, ensure the catalog is hosted where your organization's workflows can access it (e.g., an internal Git server, artifact repository).

**Example: Function Definition File (`function.yaml`)**

```yaml
# function.yaml - Example structure
input:
  schema:
    document:
      type: object
      # ... input properties ...
output:
  schema:
    document:
      type: object
      # ... output properties ...
run: # Defines how the function executes
  script: # Example: running a script
    language: javascript
    code: |
      // ... function logic ...
    # ... arguments ...
```

For a detailed walkthrough of creating a *custom function* and its `function.yaml` file, refer to the DSL concepts documentation regarding function creation. The principles outlined there for definition and structure can be adapted for other potential resource types based on future catalog specifications. 