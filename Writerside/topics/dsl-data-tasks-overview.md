# Data Tasks

Data tasks are used to manipulate and transform data within your workflow. They allow you to modify workflow state, transform data between tasks, and prepare data for subsequent operations.

## Available Tasks

| Task | Purpose |
|------|---------|
| [Set](dsl-task-set.md) | Assign values to workflow state variables |

## When to Use Data Tasks

Use data tasks when you need to:
- Initialize or update workflow state variables
- Transform data between tasks
- Prepare data for subsequent operations
- Store intermediate results
- Format data for output

## Example: Data Transformation

```yaml
document:
  id: data-transformation
  version: '1.0'
  specVersion: '0.8'
  name: Data Transformation Workflow
  start: TransformData
  states:
    - name: TransformData
      type: operation
      actions:
        - name: SetInitialData
          functionRef:
            refName: getInitialData
            arguments:
              input: ${ .input }
        - name: TransformToJSON
          functionRef:
            refName: transformToJSON
            arguments:
              data: ${ .result }
        - name: StoreResult
          functionRef:
            refName: storeData
            arguments:
              transformedData: ${ .result }
      end: true
``` 