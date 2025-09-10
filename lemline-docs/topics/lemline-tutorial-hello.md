---
title: "Tutorial: Hello, Workflow!"
---

# Tutorial: Hello, Workflow!

In this tutorial, you'll build and run your first workflow with Lemline using `set` and `log` operations. By the end, you'll have a working understanding of basic workflow creation and execution.

## Learning Objectives

By completing this tutorial, you will learn:

- How to install and set up the Lemline runtime
- How to create a basic workflow definition
- How to use the `set` task to manipulate data
- How to use a function to log information
- How to run and verify workflow execution

## Prerequisites

- Basic familiarity with YAML syntax
- JDK 17 or higher installed
- Command-line terminal access
- No prior experience with Lemline or workflow engines required

## 1. Setting Up Your Environment

First, let's install Lemline and prepare your workspace.

```bash
# Create a project directory
mkdir hello-lemline
cd hello-lemline

# Download the Lemline runner
curl -L https://github.com/lemline/lemline/releases/latest/download/lemline-runner-VERSION-runner.jar -o lemline-runner.jar

# Make it executable
chmod +x lemline-runner.jar

# Create a directory for your workflows
mkdir workflows
```

## 2. Creating Your First Workflow

Create a file named `workflows/hello.yaml` with the following content:

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

Let's break down what this workflow does:

- It defines a unique `id` and human-readable `name`
- It specifies the workflow and spec versions
- It sets the starting task as `Greeting`
- It defines a `logFunction` that will be used to output information
- It creates two tasks:
  - `Greeting`: Sets a `message` variable with a greeting
  - `LogMessage`: Calls the log function to output the message

## 3. Running Your Workflow

Now, let's run the workflow:

```bash
java -jar lemline-runner.jar workflow run workflows/hello.yaml
```

You should see output that includes your "Hello, Lemline!" message along with workflow execution details.

## 4. Enhancing Your Workflow

Let's make this workflow more interesting by adding data manipulation and conditional logic.

Update `workflows/hello.yaml` to the following:

```yaml
id: hello-workflow
name: Hello Workflow
version: '1.0'
specVersion: '1.0'
start: GetUserName
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: GetUserName
    type: set
    data:
      user: "Workflow Author"
      time: "$WORKFLOW.startTime"
    next: CreateGreeting
  - name: CreateGreeting
    type: set
    data:
      morning: ".time < '12:00'"
      greeting: ".morning ? 'Good morning' : 'Hello'"
      message: ".greeting + ', ' + .user + '!'"
    next: LogMessage
  - name: LogMessage
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

This enhanced workflow:
1. Sets a `user` variable and captures the workflow start time
2. Creates a conditional greeting based on the time
3. Constructs a personalized message
4. Logs the message to the console

Run the enhanced workflow:

```bash
java -jar lemline-runner.jar workflow run workflows/hello.yaml
```

## 5. Adding User Input

Let's modify the workflow to accept user input. Create a file named `input.json`:

```json
{
  "user": "Your Name"
}
```

Update your workflow to use this input:

```yaml
id: hello-workflow
name: Hello Workflow
version: '1.0'
specVersion: '1.0'
start: GetUserName
input:
  - $WORKFLOW.input.user
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: GetUserName
    type: set
    data:
      user: "$WORKFLOW.input.user || 'Workflow Author'"
      time: "$WORKFLOW.startTime"
    next: CreateGreeting
  - name: CreateGreeting
    type: set
    data:
      morning: ".time < '12:00'"
      greeting: ".morning ? 'Good morning' : 'Hello'"
      message: ".greeting + ', ' + .user + '!'"
    next: LogMessage
  - name: LogMessage
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

Run the workflow with your input:

```bash
java -jar lemline-runner.jar workflow run workflows/hello.yaml --input input.json
```

## 6. Exploring the Results

Let's add a final task to output all the workflow data for inspection:

```yaml
id: hello-workflow
name: Hello Workflow
version: '1.0'
specVersion: '1.0'
start: GetUserName
input:
  - $WORKFLOW.input.user
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: GetUserName
    type: set
    data:
      user: "$WORKFLOW.input.user || 'Workflow Author'"
      time: "$WORKFLOW.startTime"
    next: CreateGreeting
  - name: CreateGreeting
    type: set
    data:
      morning: ".time < '12:00'"
      greeting: ".morning ? 'Good morning' : 'Hello'"
      message: ".greeting + ', ' + .user + '!'"
    next: LogMessage
  - name: LogMessage
    type: call
    function: logFunction
    data:
      log: ".message"
    next: ShowState
  - name: ShowState
    type: call
    function: logFunction
    data:
      log: "$WORKFLOW"
    end: true
```

Run this final version to see the complete workflow state:

```bash
java -jar lemline-runner.jar workflow run workflows/hello.yaml --input input.json
```

## What You've Learned

In this tutorial, you've learned how to:

- Set up and run the Lemline workflow engine
- Create a basic workflow definition file
- Use the `set` task to manage data
- Create conditional logic in your workflow
- Use functions to perform actions
- Pass input to a workflow
- Inspect workflow state

## Next Steps

Now that you've completed your first workflow, you might want to:

- Learn about [HTTP calls](lemline-howto-http.md) to connect to external services
- Explore [data passing between tasks](lemline-howto-data-passing.md) in more depth
- Try the [Database-Less Order Processing](lemline-tutorial-order-processing.md) tutorial
- Learn about [event handling](lemline-howto-events.md) for more complex workflows

For more comprehensive information about the concepts introduced in this tutorial, see:
- [Task Set Reference](lemline-ref-task-types.md)
- [Data Flow in Workflows](lemline-explain-execution.md)
- [Runtime Expressions and jq](lemline-explain-jq.md)