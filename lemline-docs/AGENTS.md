<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-docs

Writerside documentation project for user-facing documentation.

## Key Files

| File/Directory | Description |
|----------------|-------------|
| `writerside.cfg` | Writerside configuration |
| `lemline.tree` | Documentation tree structure |
| `topics/` | Documentation topic files (Markdown/XML) |
| `images/` | Documentation images |
| `cfg/` | Additional Writerside configuration |

## For AI Agents

### Working In This Directory
- Uses JetBrains Writerside for documentation generation
- Topic files are Markdown or XML format
- Tree structure defined in `.tree` files

### Workflow examples

- Put complete workflow examples in `snippets/workflows/<topic>/` as YAML or JSON.
- Include them from the topic with `<code-block lang="yaml" src="workflows/<topic>/<example>.yaml"/>`.
  Use `include-lines` for an excerpt of a complete example and check the rendered excerpt when editing its source.
- Include all task destinations in examples shown in full. Named transitions resolve in the current task list.
- Keep configuration, payloads and other data inline. Place
  `<!-- workflow-example: data -->` immediately above an inline YAML or JSON data block.
- Put deliberately invalid workflow definitions under `snippets/counterexamples/`.
  Introduce the inclusion with `**Invalid workflow example.**` and a blank line.
- The schema in `validation/workflow-1.0.3.json` is the versioned
  [Open Workflow schema](https://open-workflow-specification.org/schemas/1.0.3/workflow.json).
  Its upstream license is in `validation/LICENSE-open-workflow`.
- Write `run.script.arguments` as a name/value map, the form Lemline reads. The schema specifies a list;
  the checker accepts the map for this property only.

Run from the repository root using a Python virtual environment:

```bash
python -m pip install -r lemline-docs/validation/requirements.txt
python lemline-docs/validation/check_workflows.py
./gradlew :lemline-core:test --tests '*DocumentationWorkflowTest'
```

<!-- MANUAL: -->
