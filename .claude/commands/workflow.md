---
description: Generate, validate, or optimize CNCF Serverless Workflows
---

# Workflow Command

Generate, validate, or optimize CNCF Serverless Workflows using the Serverless Workflow DSL v1.0.

## Usage

```
/workflow [action] [description or file path]
```

**Actions:**
- `generate` - Create a new workflow from natural language description
- `validate` - Check workflow syntax against CNCF spec
- `optimize` - Refactor workflow for better performance/structure
- `fix` - Debug and fix workflow errors

## Instructions

Parse the user's command to determine the action:

### 1. Generate Workflow (`/workflow generate <description>`)

**Example:** `/workflow generate fetch user data from API and send email notification`

**Steps:**
1. Reference the DSL documentation:
   - `lemline-docs/topics/dsl-workflow-definition.md` - Workflow structure
   - `lemline-docs/topics/dsl-tasks-overview.md` - Task types
   - `lemline-docs/topics/dsl-runtime-expressions.md` - JQ expressions
2. Use proper JQ expressions for data transformation
3. Include:
   - WorkflowDocument with dsl, namespace, name, version
   - Appropriate task types (Call, Fork, Switch, Set, Try, Wait)
   - Authentication if HTTP calls are involved
   - Error handling with Try/Catch
   - Retry policies for external calls
4. Output the workflow in YAML format
5. **Validate the workflow:**
   ```bash
   # Validate JSON/YAML syntax
   cat workflow.yaml | yq empty

   # Test JQ expressions
   echo '{"data": {"userId": 123}}' | jq '.data.userId'
   ```
6. Show validation results and the workflow to the user

### 2. Validate Workflow (`/workflow validate <file-path>`)

**Example:** `/workflow validate workflow.yaml`

**Steps:**
1. Read the workflow file using the **Read** tool
2. **Validate the workflow structure:**
   - Check WorkflowDocument fields (dsl, namespace, name, version)
   - Validate task syntax for each task type
   - Check JQ expression syntax (runtime expressions like `${ .data.userId }`)
   - Verify authentication references
   - Check retry policy format (backoff, limit, jitter)
   - Verify timeout format (duration as object: `{ seconds: 30 }`)
3. **Test JQ expressions:**
   ```bash
   # Extract and test JQ expressions
   echo '{"input": {"test": "value"}}' | jq '.input.test'
   ```
4. Report validation results:
   - Valid sections
   - Errors with fix suggestions
   - Warnings (best practices, performance issues)

### 3. Optimize Workflow (`/workflow optimize <file-path>`)

**Example:** `/workflow optimize workflow.yaml`

**Steps:**
1. Read the workflow file
2. Analyze and optimize:
   - Sequential tasks that can be parallelized (use Fork)
   - Redundant Set tasks
   - Missing retry policies on external calls
   - Inefficient JQ expressions
   - Missing timeouts
   - Better error handling opportunities
3. Create optimized version
4. Show side-by-side comparison of changes
5. Explain performance/reliability improvements

### 4. Fix Workflow (`/workflow fix <file-path>`)

**Example:** `/workflow fix broken-workflow.yaml`

**Steps:**
1. Read the workflow file
2. Identify and fix common errors:
   - Correct JQ expression syntax (`${ .data }` not `${data}`)
   - Fix duration format (object not ISO 8601: `{ seconds: 5 }` not `PT5S`)
   - Fix retry reference (string not object: `retry: retryPolicy` not `retry: { use: retryPolicy }`)
   - Add missing required fields (dsl, namespace, name, version)
   - Fix task type syntax
3. Save fixed version
4. Explain what was broken and how it was fixed

## Examples

### Example 1: Generate HTTP Workflow

```bash
/workflow generate create a workflow that calls a REST API to fetch weather data
```

**Expected Output:**
```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: fetch-weather
  version: 1.0.0

use:
  authentications:
    weatherApiKey:
      bearer: ${ $secrets.WEATHER_API_KEY }

  retries:
    defaultRetry:
      delay: { seconds: 1 }
      backoff:
        exponential: {}
      limit:
        attempt:
          count: 3

do:
  - fetchWeather:
      call: http
      with:
        method: GET
        endpoint:
          uri: https://api.weather.com/v1/current
          authentication: weatherApiKey
        query:
          city: ${ .input.city }
      retry: defaultRetry
      timeout:
        after: { seconds: 30 }
      output:
        as: ${ .response.body }
      catch:
        errors:
          with:
            type: "*"
        as: error
        do:
          - handleError:
              set:
                error: ${ $error.message }
                success: false
```

### Example 2: Validate Existing Workflow

```bash
/workflow validate workflow.yaml
```

**Expected Output:**
```
Workflow is valid!

Structure:
  WorkflowDocument: dsl, namespace, name, version present
  Tasks: 5 tasks with correct syntax
  JQ Expressions: All valid
  Authentication: Bearer token configured
  Retry Policies: Configured for external calls

Recommendations:
  - Consider adding timeout to 'sendEmail' task
  - 'fetchUser' and 'fetchOrders' could be parallelized with Fork
```

### Example 3: Fix Workflow Errors

```bash
/workflow fix broken-workflow.yaml
```

**Expected Output:**
```
Found 3 errors:

1. Line 5: Invalid duration format
   Before: timeout: PT30S
   After:  timeout: { after: { seconds: 30 } }

2. Line 12: Invalid JQ expression
   Before: input: ${.data.userId}
   After:  input: ${ .data.userId }

3. Line 18: Missing required field 'dsl' in document
   Added: dsl: 1.0.0

Fixed workflow saved.
```

## Important Conventions

**CNCF Specification Compliance:**
- Duration format: `{ seconds: 5 }` NOT `"PT5S"`
- JQ runtime expressions: `${ .data }` with spaces inside brackets
- Retry references: `retry: policyName` NOT `retry: { use: policyName }`

**Lemline Workflow Patterns:**
- Use Try/Catch for error handling
- Include authentication for all external HTTP calls
- Add retry policies with exponential backoff
- Set reasonable timeouts (30s for HTTP calls, 5m for long operations)
- Use Fork for parallel execution of independent tasks

**Documentation References:**
- `lemline-docs/topics/dsl-workflow-definition.md` - Workflow structure
- `lemline-docs/topics/dsl-tasks-overview.md` - All task types
- `lemline-docs/topics/dsl-call-http.md` - HTTP calls
- `lemline-docs/topics/dsl-task-fork.md` - Parallel execution
- `lemline-docs/topics/dsl-task-try.md` - Error handling
- `lemline-docs/topics/dsl-runtime-expressions.md` - JQ expressions
- `lemline-docs/topics/dsl-error-handling.md` - Retry policies
- `lemline-docs/topics/dsl-workflow-examples.md` - Example workflows

## Notes

- Lemline implements Serverless Workflow DSL v1.0
- Workflows can be YAML or JSON format
- Always validate after generation or modification
- Test JQ expressions with sample data before using in workflows
