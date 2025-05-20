---
title: How to define a workflow
---

# How to define a workflow

This guide explains how to define a Serverless Workflow in Lemline using the YAML format. We'll cover the basic structure, components, and best practices.

## Basic Workflow Structure

A Lemline workflow is defined in YAML and follows this basic structure:

```yaml
id: my-workflow
name: My First Workflow
version: '1.0'
specVersion: '1.0'
start: FirstTask
input:
  - $WORKFLOW.input
functions:
  # function definitions
tasks:
  # task definitions
```

Let's break down the key elements:

| Element | Description | Required? |
|---------|-------------|-----------|
| `id` | Unique identifier for the workflow | Yes |
| `name` | Human-readable name | Yes |
| `version` | Workflow version (semantic versioning recommended) | Yes |
| `specVersion` | Version of the Serverless Workflow spec (use '1.0') | Yes |
| `start` | Name of the first task to execute | Yes |
| `input` | Defines workflow input schema and defaults | No |
| `functions` | Defines functions that can be called by tasks | No |
| `tasks` | Defines the workflow tasks and their sequences | Yes |

## Defining Functions

Functions are reusable operations that can be called from tasks. They're defined in the `functions` section:

```yaml
functions:
  - name: logFunction
    type: custom
    operation: log
  - name: getWeather
    type: http
    operation: GET
    url: https://api.weather.com/forecast
```

Lemline supports these function types:

- `http`: HTTP/HTTPS calls
- `grpc`: gRPC service calls
- `openapi`: Calls to services defined by OpenAPI
- `asyncapi`: Interaction with message brokers defined by AsyncAPI
- `custom`: Built-in or user-defined functions

## Defining Tasks

Tasks define the actual work performed by the workflow. Each task has a name, type, and other properties:

```yaml
tasks:
  - name: FirstTask
    type: set
    data:
      message: "Hello, World!"
    next: SecondTask
  
  - name: SecondTask
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

Each task must include:
- `name`: Unique identifier within the workflow
- `type`: The task type (set, call, wait, etc.)
- Either `next` to specify the next task or `end: true` to end the workflow

## Task Types

Lemline supports various task types for different operations:

### Control Flow Tasks
- `do`: Groups a sequence of tasks
- `for`: Iterates over a collection
- `fork`: Executes tasks in parallel
- `switch`: Conditional branching
- `try`: Error handling with catch and retry

### Data Manipulation Tasks
- `set`: Sets or transforms data

### Integration Tasks
- `call`: Calls a function (HTTP, gRPC, OpenAPI, AsyncAPI)
- `emit`: Publishes an event
- `listen`: Waits for events
- `run`: Executes containers, scripts, or other workflows
- `wait`: Pauses execution for a specified duration

## Connecting Tasks

Tasks are connected using the `next` property, which specifies the name of the next task to execute:

```yaml
tasks:
  - name: FirstTask
    type: set
    data:
      message: "Hello, World!"
    next: SecondTask
  
  - name: SecondTask
    type: call
    function: logFunction
    data:
      log: ".message"
    next: ThirdTask
  
  - name: ThirdTask
    type: set
    data:
      finalMessage: "Workflow complete!"
    end: true
```

## Conditional Flows

You can create conditional flows using the `switch` task:

```yaml
- name: CheckWeather
  type: switch
  conditions:
    - condition: ".temperature > 30"
      next: HotWeatherTask
    - condition: ".temperature < 10"
      next: ColdWeatherTask
    - condition: true
      next: MildWeatherTask
```

## Data Flow and Expressions

Lemline uses JQ expressions for data manipulation within the `data` property:

```yaml
- name: ProcessData
  type: set
  data:
    fullName: ".firstName + ' ' + .lastName"
    uppercaseName: ".fullName | ascii_upcase"
    greeting: "'Hello, ' + .uppercaseName + '!'"
```

Expressions can access:
- The current task's input (root context)
- Previously set variables
- Workflow input via `$WORKFLOW.input`
- Runtime information via `$WORKFLOW.startTime`, etc.

## Input Validation

You can validate workflow input using JSON Schema:

```yaml
input:
  schema:
    type: object
    required: ["name", "age"]
    properties:
      name:
        type: string
      age:
        type: integer
        minimum: 0
```

## Best Practices

1. **Use descriptive names**: Choose clear, descriptive names for workflows, functions, and tasks
2. **Structure complex workflows**: Use the `do` task to group related tasks
3. **Error handling**: Always use `try` tasks for operations that might fail
4. **Data validation**: Validate inputs and outputs with schemas
5. **Version your workflows**: Use semantic versioning for workflow definitions
6. **Keep tasks focused**: Each task should do one thing well
7. **Document your workflows**: Use comments to explain complex logic or business rules

## Complete Example

Here's a complete example of a weather notification workflow:

```yaml
id: weather-notification
name: Weather Notification Workflow
version: '1.0'
specVersion: '1.0'
start: GetUserPreferences
input:
  schema:
    type: object
    required: ["userId"]
    properties:
      userId:
        type: string
functions:
  - name: getUserPreferences
    type: http
    operation: GET
    url: https://api.example.com/users/{userId}/preferences
  - name: getWeatherForecast
    type: http
    operation: GET
    url: https://api.weather.com/forecast?location={location}
  - name: sendNotification
    type: http
    operation: POST
    url: https://api.notifications.com/send
tasks:
  - name: GetUserPreferences
    type: try
    retry:
      maxAttempts: 3
      interval: PT2S
    catch:
      - error: "*"
        next: HandleError
    do:
      - name: FetchPreferences
        type: call
        function: getUserPreferences
        data:
          userId: "$WORKFLOW.input.userId"
    next: GetWeatherForecast
  
  - name: GetWeatherForecast
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "*"
        next: HandleError
    do:
      - name: FetchWeather
        type: call
        function: getWeatherForecast
        data:
          location: ".preferences.location"
    next: PrepareNotification
  
  - name: PrepareNotification
    type: set
    data:
      temperature: ".forecast.temperature"
      conditions: ".forecast.conditions"
      username: ".preferences.name"
      message: "'Hello, ' + .username + '! Today in ' + .preferences.location + ' it will be ' + .temperature + '°C with ' + .conditions"
    next: SendNotification
  
  - name: SendNotification
    type: try
    retry:
      maxAttempts: 3
      interval: PT5S
    catch:
      - error: "*"
        next: HandleError
    do:
      - name: DeliverNotification
        type: call
        function: sendNotification
        data:
          to: ".preferences.contactMethod"
          subject: "Weather Alert"
          body: ".message"
    end: true
  
  - name: HandleError
    type: set
    data:
      error: "Error processing weather notification"
      details: "$WORKFLOW.error"
    end: true
```

## Next Steps

- Learn how to [publish and run a workflow](lemline-howto-publish-run.md)
- Explore [data passing between tasks](lemline-howto-data-passing.md)
- Understand [how to handle errors](lemline-howto-try-catch.md)

For a complete reference of all workflow elements, see the [DSL Reference](lemline-ref-dsl-syntax.md).