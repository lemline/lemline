---
description: Intelligent refactoring with context-aware strategies
---

# Refactor Command

Parse arguments as: /refactor [scope]

## Quick Rules:

1. If scope starts with "/" — treat as package path
   Example: `/refactor /processor` → refactor Processor-related code
2. If scope starts with "@" — treat as file path
   Example: `/refactor @lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt`
3. If no scope - refactor current files based on your recent changes since last commit
   Example: `/refactor` → analyze git diff and refactor changed files

## Objectives:

- Extract reusable components from duplicated code
- Simplify complex functions (>30 lines) into smaller, testable units
- Improve naming consistency for classes, functions, and variables
- Consolidate related functionality into cohesive modules
- Add KDoc documentation when code is not self-explanatory
- Split large files into focused, single-responsibility classes

## Priority: Focus on these improvements IN ORDER:

1. **Critical**: Fix bugs, memory leaks, coroutine leaks, security issues
2. **Performance**: Reduce database queries, optimize batch operations, fix N+1 problems
3. **Structure**: Extract classes, organize imports, follow Kotlin conventions
4. **Readability**: Better names, add KDoc comments, simplify logic

## Lemline-Specific Patterns:

### Core Module (lemline-core)
- `Processor.kt` should delegate to specialized handlers
- `NodeInstance` subclasses should be focused and single-purpose
- JQ expression evaluation should be isolated from business logic
- State management classes should be immutable where possible

### Runner Module (lemline-runner)
- Repositories should use `suspend` functions consistently
- Outbox processors should follow the `OutboxRelay` pattern
- Message handlers should be thin wrappers around business logic
- Configuration classes should use Quarkus config patterns

### Common Patterns
- Use `IDV7` for all UUIDs (time-sortable)
- Use data classes for DTOs and models
- Use sealed classes for state enums where appropriate
- Avoid nullable types where defaults make sense

## Constraints

- Preserve all existing functionality
- Maintain backward compatibility
- Follow existing code style guidelines (see CLAUDE.md)
- Ensure all tests pass after refactoring
- Keep database migrations compatible

## When Refactoring Cannot Proceed:

- No test coverage for critical paths → Suggest adding tests first
- Scope too large (>20 files) → Ask to narrow scope
- Conflicting changes in working directory → Suggest commit or stash
- Unknown scope → List available options and ask for clarification

## Verification Checklist

- [ ] No Kotlin compilation errors: `./gradlew build`
- [ ] All tests pass: `./gradlew test`
- [ ] No new warnings in build output
- [ ] KDoc updated for changed public APIs
- [ ] Changes follow existing patterns in CLAUDE.md

## Output Format:

1. Start with: "Refactoring [scope]..."
2. Show analysis: "Found X issues: [list them]"
3. For complex changes (>5 files or >100 lines):
    - Create plan in `/docs/tmp/refactor-plan-[timestamp].md`
    - Ask for confirmation before proceeding
4. List changes with impact level:
    - Breaking: [change]
    - Major: [change]
    - Safe: [change]
5. End with: "Refactoring complete. [X] improvements made."

## Examples

### Example 1: Refactor Processor

```bash
/refactor /processor
```

**Analysis:**
- `Processor.kt` has 500+ lines
- Multiple responsibilities: navigation, execution, state management
- Opportunities: Extract NavigationHandler, StateManager

### Example 2: Refactor by File

```bash
/refactor @lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt
```

**Analysis:**
- Exception handling could be simplified with sealed class
- Duplicate code in message creation
- Extract message builders to separate class

### Example 3: Refactor Recent Changes

```bash
/refactor
```

**Analysis:**
- Git diff shows changes in 3 files
- New code duplicates existing pattern in Repository
- Suggest extracting common base class
