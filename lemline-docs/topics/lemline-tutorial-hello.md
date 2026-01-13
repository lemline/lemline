---
title: "Tutorial: Hello, Workflow!"
---

# Tutorial: Hello, Workflow!

In this tutorial, you'll build and run your first workflow with Lemline using `set` and `log` operations. By the end, you'll have a working understanding of basic workflow creation and execution.

## Learning Objectives

By completing this tutorial, you will learn:

- How to create a basic workflow definition
- How to use the `set` task to manipulate data
- How to use a function to log information
- How to run and verify workflow execution

## Prerequisites

- Basic familiarity with YAML syntax
- A working Lemline environment (complete the [Setting Up Your Environment](lemline-tutorial-setup.md) tutorial first)

## 1. Creating Your First Workflow

Create a file named `hello.yaml` with the following content:

```yaml
document:
  dsl: '1.0.0'
  namespace: tutorial
  name: hello-workflow
  version: '0.1.0'
do:
  - setGreeting:
      set:
        message: Hello, Lemline!
  - logMessage:
      call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
```

Let's break down what this workflow does:

- **document**: Defines metadata (DSL version, namespace, name, version)
- **do**: Contains the list of tasks to execute sequentially
- **setGreeting**: Uses `set` to create a `message` variable
- **logMessage**: Calls the standard log function to output the message

## 2. Running Your Workflow

Now, let's run the workflow:

<tabs group="platform">
<tab id="macos-run" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline workflow run hello.yaml
```

</tab>
<tab id="linux-run" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline workflow run hello.yaml
```

</tab>
<tab id="windows-run" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe workflow run workflows\hello.yaml
```

</tab>
<tab id="java-run" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar workflow run hello.yaml
```

</tab>
</tabs>

You should see output that includes your "Hello, Lemline!" message along with workflow execution details.

## 3. Enhancing Your Workflow

Let's make this workflow more interesting by adding more variables and string manipulation.

Update `hello.yaml` to the following:

```yaml
document:
  dsl: '1.0.0'
  namespace: tutorial
  name: hello-workflow
  version: '0.1.0'
do:
  - setUserInfo:
      set:
        user: Workflow Author
        role: Developer
  - createGreeting:
      set:
        message: ${ "Hello, " + .user + "! Welcome, " + .role + "." }
  - logMessage:
      call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
```

This enhanced workflow:
1. Sets `user` and `role` variables
2. Constructs a personalized greeting using string concatenation
3. Logs the final message

Run the enhanced workflow:

<tabs group="platform">
<tab id="macos-run2" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline workflow run hello.yaml
```

</tab>
<tab id="linux-run2" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline workflow run hello.yaml
```

</tab>
<tab id="windows-run2" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe workflow run hello.yaml
```

</tab>
<tab id="java-run2" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar workflow run hello.yaml
```

</tab>
</tabs>

## 4. Adding User Input

Let's modify the workflow to accept user input. Create a file named `input.json`:

```json
{
  "name": "Your Name"
}
```

Update your workflow to use this input:

```yaml
document:
  dsl: '1.0.0'
  namespace: tutorial
  name: hello-workflow
  version: '0.1.0'
input:
  schema:
    type: object
    properties:
      name:
        type: string
do:
  - createGreeting:
      set:
        message: ${ "Hello, " + .name + "!" }
  - logMessage:
      call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
```

Run the workflow with your input:

<tabs group="platform">
<tab id="macos-run3" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline workflow run hello.yaml --input input.json
```

</tab>
<tab id="linux-run3" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline workflow run hello.yaml --input input.json
```

</tab>
<tab id="windows-run3" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe workflow run hello.yaml --input input.json
```

</tab>
<tab id="java-run3" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar workflow run hello.yaml --input input.json
```

</tab>
</tabs>

## 5. Adding Conditional Logic

Let's add a conditional greeting based on the time of day using a `switch` task:

```yaml
document:
  dsl: '1.0.0'
  namespace: tutorial
  name: hello-workflow
  version: '0.1.0'
input:
  schema:
    type: object
    properties:
      name:
        type: string
      hour:
        type: integer
do:
  - chooseGreeting:
      switch:
        - when: ${ .hour < 12 }
          then: setMorningGreeting
        - when: ${ .hour < 18 }
          then: setAfternoonGreeting
        - otherwise:
          then: setEveningGreeting
  - setMorningGreeting:
      set:
        greeting: Good morning
  - setAfternoonGreeting:
      set:
        greeting: Good afternoon
  - setEveningGreeting:
      set:
        greeting: Good evening
  - createMessage:
      set:
        message: ${ .greeting + ", " + .name + "!" }
  - logMessage:
      call: https://raw.githubusercontent.com/serverlessworkflow/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
```

Update `input.json` with an hour value:

```json
{
  "name": "Your Name",
  "hour": 10
}
```

Run the workflow:

<tabs group="platform">
<tab id="macos-run4" title="macOS (ARM64)" group-key="macos">

```bash
bin/lemline workflow run hello.yaml --input input.json
```

</tab>
<tab id="linux-run4" title="Linux (x86_64)" group-key="linux">

```bash
bin/lemline workflow run hello.yaml --input input.json
```

</tab>
<tab id="windows-run4" title="Windows (x86_64)" group-key="windows">

```powershell
bin\lemline.exe workflow run hello.yaml --input input.json
```

</tab>
<tab id="java-run4" title="Java (Any OS)" group-key="java">

```bash
java -jar lemline.jar workflow run hello.yaml --input input.json
```

</tab>
</tabs>

## What You've Learned

In this tutorial, you've learned how to:

- Create workflow definitions using the Serverless Workflow DSL v1.0
- Use the `set` task to create and manipulate variables
- Use expressions with `${ }` for dynamic values
- Call external functions (like the log function)
- Pass input to a workflow using JSON files
- Use `switch` for conditional branching

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
