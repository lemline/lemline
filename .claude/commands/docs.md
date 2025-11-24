# Docs Command

Reorganize for Human + AI Consumption the documentation relative to: **{{placeholderText}}**

Examples of placeholder text:

- "orchestrators and execution flow in lemline-core"
- "messaging and outbox patterns in lemline-runner"
- "repository patterns in lemline-runner"
- "the entire lemline-core/docs directory"

## Objectives

1. **AI Token Efficiency**: No document should exceed 200 lines (target 100-150)
2. **Single Responsibility**: Each file covers ONE focused topic
3. **Self-Contained Guides**: Minimal cross-references, readable standalone
4. **Clear Separation**: Principles (why) vs Guides (how)
5. **Strong Index**: README.md/index file for navigation

## Current Documentation Structure

Lemline uses **module-level documentation** instead of a central `/docs/dev/` folder:

```
lemline-core/docs/
├── README.md                    # Index + quick reference
├── core-overview.md             # Module structure, DSL parsing
├── core-nodes.md                # Node tree, NodePosition
├── core-orchestrators.md        # StepByStep and Full orchestrators
├── core-processors.md           # NodeProcessor pattern
├── core-fork.md                 # Parallel branches
├── core-errors.md               # Exceptions, retry policies
├── core-states.md               # TaskState, WorkflowState
├── core-expressions.md          # JQ expressions, scope
└── core-execution-model.md      # Formal execution model

lemline-runner/docs/
├── README.md                    # Index + quick reference
├── runner-configuration.md      # Config system, database/messaging setup
├── runner-messaging.md          # Dual-channel design, commands/events
├── runner-tables.md             # Database tables, outbox pattern
├── runner-repositories-guide.md # Repository patterns, transactions
├── runner-logging.md            # Logging strategy, MDC
└── runner-cli.md                # CLI commands

docs/
├── adr/                         # Architecture Decision Records
└── (other project-wide docs)
```

**Key principles**:

- **Module-specific docs** go in `lemline-{module}/docs/`
- **Naming convention**: `{module}-{topic}.md` (e.g., `core-orchestrators.md`, `runner-messaging.md`)
- Each module has a **README.md index** with quick reference tables
- **No nested folders** within module docs (flat structure)

## Document Guidelines

### README.md (Module Index)

Every module docs folder must have a README.md:

````markdown
# Lemline {Module} Documentation

The `lemline-{module}` module [one sentence description].

## Documentation Index

| Document | Description | When to Read |
|----------|-------------|--------------|
| [{module}-topic1.md]({module}-topic1.md) | Topic description | Use case |
| [{module}-topic2.md]({module}-topic2.md) | Topic description | Use case |

## Quick Reference

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `ClassName` | `package/` | Brief purpose |

### Common Tasks

| Task | Documentation |
|------|---------------|
| Do something | [{module}-topic.md]({module}-topic.md#section) |

## Commands

```bash
./gradlew :lemline-{module}:test
./gradlew :lemline-{module}:build
```
````

**Target**: 50-80 lines, **max 100 lines**

### Topic Document ({module}-{topic}.md)

````markdown
# Lemline {Module} - {Topic}

This document covers {topic} for the lemline-{module} module.

## Overview

[Brief overview paragraph]

## Key Files

| File | Purpose |
|------|---------|
| `path/File.kt` | Brief purpose |

---

## Main Content

[Organized with ## headers for major sections]

---

## Best Practices / Common Patterns

[If applicable]

---

## Troubleshooting

[If applicable - common issues table]

| Issue | Check |
|-------|-------|
| Problem | Solution |
````

**Target**: 80-150 lines, **max 200 lines**

## Specific Instructions

### Step 1: Audit

```bash
# List all docs and line counts in a module
find lemline-{module}/docs -name "*.md" -exec wc -l {} \; | sort -n

# Find files exceeding 200 lines
find lemline-{module}/docs -name "*.md" -exec wc -l {} \; | awk '$1 > 200 {print}'

# Check README exists
test -f lemline-{module}/docs/README.md && echo "README exists" || echo "Missing README"
```

### Step 2: Create New Document

```bash
# Create new topic document
touch lemline-{module}/docs/{module}-{topic}.md

# Follow naming convention:
# lemline-core/docs/core-{topic}.md
# lemline-runner/docs/runner-{topic}.md
```

### Step 3: Move Content

**For content from `/docs/dev/` to module docs**:

- Identify which module owns the content
- Rename following `{module}-{topic}.md` convention
- Use `git mv` to preserve history when possible

**For splits (1 file → many files)**:

- Create new files with focused content
- Add comment at top: `<!-- Split from: {original-file}.md -->`
- Update README.md index

### Step 4: Update README.md Index

After adding/moving docs, update the module's README.md:

- Add entry to Documentation Index table
- Add relevant entries to Quick Reference
- Add to Common Tasks if applicable

### Step 5: Validate

```bash
# No doc exceeds 200 lines
find lemline-{module}/docs -name "*.md" -exec wc -l {} \; | awk '$1 > 200 {print $2 ": " $1 " lines (EXCEEDS MAX)"}'

# Verify README.md exists
test -f lemline-{module}/docs/README.md || echo "Missing README.md"

# Check all docs follow naming convention
ls lemline-core/docs/*.md | grep -v "^lemline-core/docs/core-" | grep -v README
ls lemline-runner/docs/*.md | grep -v "^lemline-runner/docs/runner-" | grep -v README
```

### Step 6: Update Cross-References

```bash
# Find references to old paths
grep -r "$subject" CLAUDE.md .claude/ lemline-*/docs/ --color

# Verify no broken links remain
grep -r "\[.*\](.*\.md)" lemline-{module}/docs/ | while read line; do
  # Check if linked file exists
  echo "$line"
done
```

## Line Count Limits

| File Type           | Target | Max | If Exceeds Max                   |
|---------------------|--------|-----|----------------------------------|
| README.md (index)   | 50-80  | 100 | Reduce quick start, link more    |
| {module}-{topic}.md | 80-150 | 200 | Split into multiple focused docs |

**Philosophy**: If a file exceeds max, split into multiple focused documents.

## Example: Adding New Documentation

**Scenario**: Document a new "activities" feature in lemline-core

1. **Create file**: `lemline-core/docs/core-activities.md`

2. **Write content** following the topic document template

3. **Update index**: Add to `lemline-core/docs/README.md`:
   ```markdown
   | [core-activities.md](core-activities.md) | Activity runners (HTTP, Shell, Script) | Implementing activities |
   ```

4. **Validate**:
   ```bash
   wc -l lemline-core/docs/core-activities.md  # Should be < 200
   ```

## When to Use This Command

**Use when**:

- Any single file exceeds 150 lines
- Adding documentation for a new feature
- Content doesn't fit existing documents
- Reorganizing after significant changes

**Don't use when**:

- Docs are already well-organized
- File is <150 lines and focused
- Minor updates to existing docs
