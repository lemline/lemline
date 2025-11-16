---
description: Generate, validate, or optimize CNCF Serverless Workflows
---

# Workflow Command

Generate, validate, or optimize CNCF Serverless Workflows using the workflow specification.

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
1. Use the **workflow-expert** agent to create a CNCF Serverless Workflow
2. Reference `/docs/workflows/SW_Reference.md` for schema compliance
3. Use proper JQ expressions from `/docs/workflows/jq_manual.yaml`
4. Include:
   - WorkflowDocument with dsl, namespace, name, version
   - Appropriate task types (Call, Fork, Switch, Set, Try, Wait)
   - Authentication if HTTP calls are involved
   - Error handling with Try/Catch
   - Retry policies for external calls
5. Save to `workflows/generated/<workflow-name>.json`
6. **Validate using validation tools:**
   - **JSON Schema validation**: Validate structure against CNCF spec
     ```bash
     # If you have a JSON schema validator installed
     jsonschema -i workflows/generated/<workflow-name>.json /docs/workflows/workflow-schema.json
     ```
   - **JQ expression validation**: Test JQ expressions
     ```bash
     # Test each JQ expression with sample data
     echo '{"data": {"userId": 123}}' | jq '.data.userId'
     ```
   - **Manual validation**: Check against `/docs/workflows/SW_Reference.md`
7. Show validation results and the workflow to the user

### 2. Validate Workflow (`/workflow validate <file-path>`)

**Example:** `/workflow validate workflows/my-workflow.json`

**Steps:**
1. Read the workflow file using the **Read** tool
2. **Use validation tools to check the workflow:**
   - **JSON Schema validation**:
     ```bash
     # Validate against JSON schema (if available)
     jsonschema -i <file-path> /docs/workflows/workflow-schema.json
     ```
   - **JQ expression testing**:
     ```bash
     # Extract and test each JQ expression
     cat <file-path> | jq '.do[].call.with.body' 2>&1
     ```
   - **Syntax checking**:
     ```bash
     # Validate JSON syntax
     cat <file-path> | jq empty
     ```
3. Use the **workflow-expert** agent to perform semantic validation:
   - WorkflowDocument structure (required fields: dsl, namespace, name, version)
   - Task syntax for each task type (Call, Fork, Switch, etc.)
   - JQ expression validity (runtime expressions like `${ .data.userId }`)
   - Authentication references (if using reusable auth)
   - Retry policy format (backoff, limit, jitter)
   - Timeout format (duration as object: `{ seconds: 30 }`)
4. Report combined validation results:
   - ✅ Valid sections (from both tools and agent)
   - ❌ Errors with line numbers and fix suggestions
   - ⚠️ Warnings (best practices, performance issues)

### 3. Optimize Workflow (`/workflow optimize <file-path>`)

**Example:** `/workflow optimize workflows/slow-workflow.json`

**Steps:**
1. Read the workflow file
2. Use the **workflow-expert** agent to analyze and optimize
3. Look for:
   - Sequential tasks that can be parallelized (use Fork)
   - Redundant Set tasks
   - Missing retry policies on external calls
   - Inefficient JQ expressions
   - Missing timeouts
   - Better error handling opportunities
4. Create optimized version in `workflows/optimized/<workflow-name>-optimized.json`
5. Show side-by-side comparison of changes
6. Explain performance/reliability improvements

### 4. Fix Workflow (`/workflow fix <file-path>`)

**Example:** `/workflow fix workflows/broken-workflow.json`

**Steps:**
1. Read the workflow file
2. Use the **workflow-expert** agent to identify and fix errors
3. Common fixes:
   - Correct JQ expression syntax (`${ .data }` not `${data}`)
   - Fix duration format (object not ISO 8601: `{ seconds: 5 }` not `PT5S`)
   - Fix retry reference (string not object: `retry: retryPolicy` not `retry: { use: retryPolicy }`)
   - Fix catalog syntax (`function@catalog` not `catalog#function`)
   - Add missing required fields (dsl, namespace, name, version)
4. Save fixed version to `workflows/fixed/<workflow-name>-fixed.json`
5. Explain what was broken and how it was fixed

## Examples

### Example 1: Generate HTTP Workflow

```bash
/workflow generate create a workflow that calls a REST API to fetch weather data
```

**Expected Output:**
- Creates `workflows/generated/fetch-weather-data.json`
- Includes Call task with HTTP endpoint
- Adds authentication (Bearer token or API key)
- Includes retry policy with exponential backoff
- Adds error handling with Try/Catch
- Validates against spec

### Example 2: Validate Existing Workflow

```bash
/workflow validate workflows/user-onboarding.json
```

**Expected Output:**
```
✅ Workflow is valid!

Structure:
  ✅ WorkflowDocument: dsl, namespace, name, version present
  ✅ Tasks: 5 tasks with correct syntax
  ✅ JQ Expressions: All valid
  ✅ Authentication: Bearer token configured
  ✅ Retry Policies: Configured for external calls

⚠️ Recommendations:
  - Consider adding timeout to 'sendEmail' task
  - 'fetchUser' and 'fetchOrders' could be parallelized with Fork
```

### Example 3: Optimize Sequential Workflow

```bash
/workflow optimize workflows/sequential-data-fetch.json
```

**Expected Output:**
```
Optimized workflow saved to: workflows/optimized/sequential-data-fetch-optimized.json

Changes:
  1. Parallelized 3 independent HTTP calls using Fork task
  2. Added retry policy to all external calls
  3. Added 30-second timeout to prevent hanging
  4. Simplified JQ expression in 'combineData' task

Performance Impact:
  - Execution time: ~15s → ~5s (67% faster)
  - Reliability: Added retry for transient failures
```

## Validation Tools Reference

The `/workflow` command can use various validation tools to ensure workflow correctness:

### 1. JSON Schema Validator

**Tool**: `jsonschema` (Python package)
**Install**: `pip install jsonschema`
**Usage**:
```bash
jsonschema -i workflows/my-workflow.json /docs/workflows/workflow-schema.json
```

**What it validates:**
- Correct JSON structure
- Required fields present (dsl, namespace, name, version)
- Field types (string, object, array)
- Enum values (task types)

### 2. JQ Expression Tester

**Tool**: `jq` (command-line JSON processor)
**Install**: `brew install jq` (macOS) or `apt-get install jq` (Linux)
**Usage**:
```bash
# Test a JQ expression
echo '{"data": {"userId": 123}}' | jq '.data.userId'

# Extract and test all JQ expressions from workflow
cat workflow.json | jq '.do[].call.with.body' 2>&1
```

**What it validates:**
- JQ syntax correctness
- Expression output with sample data
- Filter chains and transformations

### 3. JSON Syntax Checker

**Tool**: `jq` (also validates JSON syntax)
**Usage**:
```bash
# Returns exit code 0 if valid JSON, non-zero if invalid
cat workflow.json | jq empty
```

**What it validates:**
- Valid JSON syntax
- No trailing commas
- Proper quote escaping
- Bracket/brace matching

### 4. Workflow-Specific Validators (Future)

**Custom validators to implement:**

- **CNCF Spec Validator**: Validates against official CNCF Serverless Workflow JSON schema
- **JQ Expression Validator**: Tests JQ expressions with sample workflow context
- **Authentication Validator**: Checks authentication references and formats
- **Retry Policy Validator**: Validates retry configurations
- **Workflow Linter**: Best practices and performance recommendations

**Implementation location**: `lemline-backend/src/main/kotlin/com/lemline/domain/workflow/tools/`

## Important Conventions

**CNCF Specification Compliance:**
- Always validate against `/docs/workflows/SW_Reference.md`
- Use JQ syntax from `/docs/workflows/jq_manual.yaml`
- Duration format: `{ seconds: 5 }` NOT `"PT5S"`
- Retry references: `retry: policyName` NOT `retry: { use: policyName }`
- Catalog syntax: `function@catalog` NOT `catalog#function`

**Lemline Workflow Patterns:**
- Use workflow types from `lemline-common` when generating Kotlin/TypeScript
- Save workflows in `workflows/` directory with descriptive names
- Include authentication for all external HTTP calls
- Add retry policies with exponential backoff
- Use Try/Catch for error handling
- Set reasonable timeouts (30s for HTTP calls, 5m for long operations)

**Documentation References:**
- `/docs/workflows/SW_DSL.md` - Workflow concepts
- `/docs/workflows/SW_Reference.md` - Complete spec reference
- `/docs/workflows/jq_manual.yaml` - JQ expression language
- `/docs/workflows/examples/` - Example workflows
- `.claude/agents/workflow-expert.md` - Workflow expert agent

## Notes

- Use the **workflow-expert** agent for all workflow-related tasks
- For AI-generated workflows, use `/agent ai-engineer` with `/workflow generate`
- Workflows are stored as JSON (not YAML) in Lemline
- Always validate after generation or modification
