---
title: How to publish and run a workflow
---

# How to publish and run a workflow

This guide covers the process of publishing a workflow definition to Lemline's registry and then running workflow instances. You'll learn how to use both the CLI and API for workflow management.

## Prerequisites

Before you begin, ensure that:
- You have [installed Lemline](lemline-getting-started.md)
- You have [defined a workflow](lemline-howto-define-workflow.md)
- You have a running Lemline instance with a configured database

## Understanding the Workflow Lifecycle

In Lemline, workflows follow this lifecycle:

1. **Define**: Create a workflow definition in YAML format
2. **Publish**: Register the workflow definition with Lemline's registry
3. **Start**: Instantiate and run instances of the workflow
4. **Monitor**: Track the workflow's execution status
5. **Manage**: Cancel, suspend, or resume workflow instances as needed

## Publishing a Workflow Definition

### Using the CLI

The most straightforward way to publish a workflow is using the Lemline CLI:

```bash
lemline definition post /path/to/my-workflow.yaml
```

This command:
1. Validates the workflow definition against the Serverless Workflow schema
2. Adds the workflow to Lemline's registry
3. Makes it available for instantiation

If successful, you'll see a confirmation message with the workflow ID, name, and version:

```
Workflow published successfully:
ID: my-workflow
Name: My Workflow
Version: 1.0
```

### Publishing Multiple Workflows

You can publish multiple workflows at once using a directory:

```bash
lemline definition post --directory /path/to/workflows
```

This will publish all `.yaml` and `.yml` files in the specified directory.

### Republishing a Workflow

To update an existing workflow definition, use the same command:

```bash
lemline definition post /path/to/my-workflow.yaml --update
```

The `--update` flag tells Lemline to replace the existing definition if one exists with the same ID and version.

### Using the REST API

You can also publish workflows using Lemline's REST API:

```bash
curl -X POST \
  http://localhost:8080/api/v1/definitions \
  -H "Content-Type: application/yaml" \
  --data-binary @/path/to/my-workflow.yaml
```

## Running a Workflow Instance

Once a workflow is published, you can start instances of it.

### Using the CLI

To start a workflow instance with the CLI:

```bash
lemline instance start my-workflow --version 1.0
```

This starts a new instance of the workflow with default inputs.

#### Providing Input Data

Most workflows require input data. You can provide this using JSON:

```bash
lemline instance start my-workflow --input '{"userId": "user123", "action": "login"}'
```

For larger inputs, use a file:

```bash
lemline instance start my-workflow --input-file /path/to/input.json
```

#### Specifying Workflow Version

If you have multiple versions of a workflow, specify the version:

```bash
lemline instance start my-workflow --version 2.0
```

#### Running Directly from a File

You can also run a workflow directly from a file without publishing it first:

```bash
lemline workflow run /path/to/my-workflow.yaml --input '{"userId": "user123"}'
```

This is useful for testing and development but doesn't register the workflow in the registry.

### Using the REST API

To start a workflow instance via the REST API:

```bash
curl -X POST \
  http://localhost:8080/api/v1/instances \
  -H "Content-Type: application/json" \
  -d '{
    "workflowId": "my-workflow",
    "version": "1.0",
    "input": {
      "userId": "user123",
      "action": "login"
    }
  }'
```

A successful response will include the instance ID:

```json
{
  "instanceId": "8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a",
  "workflowId": "my-workflow",
  "version": "1.0",
  "status": "RUNNING"
}
```

## Checking Instance Status

### Using the CLI

To check the status of a workflow instance:

```bash
lemline instance get 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a
```

This returns details about the instance, including its current status, start time, and current position.

To list all instances of a specific workflow:

```bash
lemline instance list my-workflow
```

### Using the REST API

```bash
curl -X GET \
  http://localhost:8080/api/v1/instances/8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a
```

## Running Workflows in Production

For production environments, consider these best practices:

### 1. Use a Proper Database

Configure Lemline with a production-grade database:

```yaml
# In lemline.yaml
lemline:
  database:
    type: postgresql
    url: jdbc:postgresql://localhost:5432/lemline
    username: lemline_user
    password: ${LEMLINE_DB_PASSWORD}
```

### 2. Configure a Message Broker

For reliable event handling, configure a message broker:

```yaml
# In lemline.yaml
lemline:
  messaging:
    type: kafka
    bootstrap.servers: kafka:9092
    topics:
      workflow-events: lemline-events
```

### 3. Deploy Multiple Instances

For high availability and throughput, deploy multiple Lemline runner instances behind a load balancer. Each instance should connect to the same database and message broker.

### 4. Implement Health Checks

Regularly check the health of your Lemline instances:

```bash
curl http://localhost:8080/q/health
```

### 5. Monitor Workflow Activity

Use Lemline's metrics endpoint to monitor workflow execution:

```bash
curl http://localhost:8080/q/metrics
```

### 6. Use Environment-Specific Configurations

Maintain separate configurations for development, testing, and production environments:

```bash
java -jar lemline-runner.jar -Dquarkus.profile=prod
```

## Troubleshooting

### Workflow Won't Publish

If you encounter errors when publishing a workflow:

1. **Validation Errors**: Check that your workflow adheres to the Serverless Workflow schema
2. **Database Connectivity**: Ensure your database is running and accessible
3. **Version Conflicts**: If the workflow already exists with the same version, use the `--update` flag

### Workflow Instance Won't Start

If a workflow instance fails to start:

1. **Input Validation**: Ensure your input data matches the workflow's input schema
2. **Workflow Availability**: Verify the workflow is published with the specified version
3. **Resource Issues**: Check if Lemline has sufficient resources (memory, connections)

### Instance Stuck in RUNNING State

If an instance seems stuck:

1. **Check Logs**: Look for errors in the Lemline logs
2. **External Dependencies**: Verify that any external services the workflow calls are functioning
3. **Wait Tasks**: Determine if the workflow is legitimately waiting for a timer or event

## Next Steps

- Learn how to [monitor workflow execution](lemline-howto-monitor.md)
- Explore how to [cancel, resume, or suspend workflows](lemline-howto-lifecycle.md)
- Understand [how to debug faults in task execution](lemline-howto-debug.md)