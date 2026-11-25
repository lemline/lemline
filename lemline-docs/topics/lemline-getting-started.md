---
title: Getting Started Fast
---

# Getting Started Fast

This guide will help you quickly set up Lemline and run your first workflow. For more detailed learning, check out
the [Hello, Workflow! tutorial](lemline-tutorial-hello.md).

## Set up SQLite

Follow [Setting Up A Local Environment](lemline-tutorial-setup.md) to install Lemline, create the SQLite configuration and migrate the database. Run the commands below from that project directory. In PowerShell, use `.\bin\lemline.exe`. With the JAR, use `java -jar lemline.jar`.

## Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

This simple workflow sets a `message` value, which becomes the workflow output, and ends.

## Running Your Workflow

Use the same project directory in both terminals so the CLI and the runner share the configuration and SQLite files.

```bash
# Register the definition
bin/lemline definition post -f hello.yaml

# In a separate terminal: start a runner and leave it running
bin/lemline listen --debug

# Start an instance
bin/lemline instance start examples hello-workflow
```

`instance start` prints the id of the new instance. With `--debug`, the runner logs the workflow output, including your "Hello, Lemline!" message.

## Exploring Next Steps

Now that you've run your first workflow, here are some quick follow-up steps:

### Modify the Workflow

Try changing the message in the workflow and running it again to see how changes affect the output.

### Add More Tasks

Extend the workflow with additional set tasks or try using a different task type like wait or switch.

### Connect to HTTP Services

For a more useful example, try creating a workflow that makes an HTTP request:

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

## Try a Ready-to-Run Sample Project

The repository's `examples/` directory contains Docker Compose setups and configuration files for Kafka, RabbitMQ, PostgreSQL and MySQL. See `examples/README.md` in the repository.

## What's Next?

Now that you've had a quick start with Lemline, you might want to:

- Follow the more detailed [Hello, Workflow! tutorial](lemline-tutorial-hello.md)
- Learn how to [define workflows](lemline-howto-define-workflow.md) in more depth
- Explore [HTTP calls](lemline-howto-http.md) and other service integrations
- Understand [passing data between tasks](lemline-howto-data-passing.md)

For a more conceptual understanding, check out [How Lemline Executes Workflows](lemline-explain-execution.md) in the
Explanations section.
