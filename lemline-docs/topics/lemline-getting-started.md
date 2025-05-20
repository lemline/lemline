---
title: Getting Started Fast
---

# Getting Started Fast

This guide will help you quickly set up Lemline and run your first workflow. For more detailed learning, check out the [Hello, Workflow! tutorial](lemline-tutorial-hello.md).

## Installing Lemline

### Option 1: Download the Prebuilt Binary

```bash
# Create a directory for Lemline
mkdir -p ~/lemline && cd ~/lemline

# Download the latest release
curl -L https://github.com/lemline/lemline/releases/latest/download/lemline-runner-VERSION-runner.jar -o lemline-runner.jar

# Make it executable
chmod +x lemline-runner.jar
```

### Option 2: Build from Source

```bash
# Clone the repository
git clone https://github.com/lemline/lemline.git
cd lemline

# Build with Gradle
./gradlew :lemline-runner:build

# The runner JAR will be in lemline-runner/build/libs/
```

## Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

```yaml
id: hello-workflow
name: Hello Workflow
version: '1.0'
specVersion: '1.0'
start: Greeting
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: Greeting
    type: set
    data:
      message: "Hello, Lemline!"
    next: LogMessage
  - name: LogMessage
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

This simple workflow:
1. Sets a message variable
2. Logs the message to the console
3. Ends the workflow

## Running Your Workflow

```bash
# Run the workflow using the Lemline runner
java -jar lemline-runner.jar workflow run hello.yaml

# Or if you have the Lemline CLI installed:
lemline workflow run hello.yaml
```

You should see output including your "Hello, Lemline!" message.

## Exploring Next Steps

Now that you've run your first workflow, here are some quick follow-up steps:

### Modify the Workflow

Try changing the message in the workflow and running it again to see how changes affect the output.

### Add More Tasks

Extend the workflow with additional set tasks or try using a different task type like wait or switch.

### Connect to HTTP Services

For a more useful example, try creating a workflow that makes an HTTP request:

```yaml
id: http-workflow
name: HTTP Request Workflow
version: '1.0'
specVersion: '1.0'
start: MakeRequest
functions:
  - name: httpGet
    type: http
    operation: GET
    url: https://jsonplaceholder.typicode.com/todos/1
tasks:
  - name: MakeRequest
    type: call
    function: httpGet
    next: DisplayResult
  - name: DisplayResult
    type: set
    data:
      result: "$WORKFLOW.MakeRequest.result"
    end: true
```

## Try a Ready-to-Run Sample Project

Lemline comes with several example workflows that demonstrate different features:

```bash
# List available examples
java -jar lemline-runner.jar examples list

# Run a specific example
java -jar lemline-runner.jar examples run star-wars
```

## What's Next?

Now that you've had a quick start with Lemline, you might want to:

- Follow the more detailed [Hello, Workflow! tutorial](lemline-tutorial-hello.md)
- Learn how to [define workflows](lemline-howto-define-workflow.md) in more depth
- Explore [HTTP calls](lemline-howto-http.md) and other service integrations
- Understand [passing data between tasks](lemline-howto-data-passing.md)

For a more conceptual understanding, check out [How Lemline Executes Workflows](lemline-explain-execution.md) in the Explanations section.