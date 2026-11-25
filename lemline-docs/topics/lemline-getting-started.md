---
title: Getting Started Fast
---

# Getting Started Fast

This guide will help you quickly set up Lemline and run your first workflow. For more detailed learning, check out
[Acknowledge the first order](lemline-tutorial-hello.md).

## Set up SQLite

Follow [Setting Up A Local Environment](lemline-tutorial-setup.md) to install Lemline, create the SQLite configuration, migrate the database and start the worker. Keep that worker running and run the commands below from a second terminal in the same project directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. In PowerShell, use `.\bin\lemline.exe`.

## Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

This simple workflow sets a `message` value, which becomes the workflow output, and ends.

## Running Your Workflow

Register the definition, then start an instance of it:

```bash
bin/lemline definition post -f hello.yaml
bin/lemline instance start examples shop-receipt
```

`instance start` prints the ID of the new instance. In the worker terminal, find that ID and the line `Workflow completed with output`. The output is `{"message":"Order received"}`.

## Exploring Next Steps

Now that you've run your first workflow, here are some quick follow-up steps:

### Modify the Workflow

Change the message and the `version` in `hello.yaml`, register it again, and start the new version, for example `bin/lemline instance start examples shop-receipt 0.1.1`. Posting a version that is already registered answers `Workflow already exists` unless you add `--force`, so give each change a new version number.

### Add More Tasks

Extend the workflow with additional set tasks or try using a different task type like wait or switch.

### Connect to HTTP Services

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). From the root of the Lemline source checkout, in a separate terminal:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

This workflow reads the available stock from the shop. Save it as `check-stock.yaml`:

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

From the project directory:

```bash
bin/lemline definition post -f check-stock.yaml
bin/lemline instance start examples check-stock
```

The HTTP response body becomes the workflow output: `{"sku":"SKU-1","available":100,"reserved":0}` on a fresh shop. Stop the shop from the checkout root when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Try a Ready-to-Run Sample Project

The repository's `examples/` directory contains Docker Compose setups and configuration files for Kafka, RabbitMQ, PostgreSQL and MySQL. See `examples/README.md` in the repository.

## What's Next?

Now that you've had a quick start with Lemline, you might want to:

- Follow the more detailed [Acknowledge the first order](lemline-tutorial-hello.md)
- Learn how to [define workflows](lemline-howto-define-workflow.md) in more depth
- Explore [HTTP calls](lemline-howto-http.md) and other service integrations
- Understand [passing data between tasks](lemline-howto-data-passing.md)

For a more conceptual understanding, check out [How Lemline Executes Workflows](lemline-explain-execution.md) in the
Explanations section.
