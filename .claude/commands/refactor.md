---
name: refactor
description: Intelligent refactoring with context-aware strategies
---

# Refactor Command

Parse arguments as: /refactor [scope]

## Quick Rules:

1. If scope starts with "/" — treat as URL path
   Example: `/refactor /api/workflow` → refactor all workflow API endpoints
2. If scope starts with "@" — treat as file path  
   Example: `/refactor @src/components/UserProfile.tsx`
3. If no scope - refactor current files based on your recent changes since last commit
   Example: `/refactor` → analyze git diff and refactor changed files

## Objectives:

- Extract reusable components from duplicated code
- Simplify complex functions (>20 lines) into smaller, testable units
- Improve naming consistency for variables, functions, and components
- Consolidate related functionality into cohesive modules
- Explain code within the documentation when the code is not self-explanatory
- Divide in multiple files if needed

## Priority: Focus on these improvements IN ORDER:

1. 🚨 Critical: Fix bugs, memory leaks, security issues
2. 🎯 Performance: Reduce re-renders, optimize loops
3. 📦 Structure: Extract components, organize imports
4. 🎨 Readability: Better names, add comments, simplify logic

## Constraints

- Preserve all existing functionality
- Maintain backward compatibility
- Follow existing code style guidelines

## When Refactoring Cannot Proceed:

- No test coverage for critical paths → Suggest adding tests first
- Scope too large (>20 files) → Ask to narrow scope
- Conflicting changes in working directory → Suggest commit or stash
- Unknown scope → List available options and ask for clarification

## Verification checklist

- [ ] No Kotlin errors
- [ ] All tests pass
- [ ] Performance metrics maintained
- [ ] Documentation updated

## Output format:

1. Start with: "🔄 Refactoring [scope]..."
2. Show analysis: "📊 Found X issues: [list them]"
3. For complex changes (>5 files or >100 lines):
    - Create plan in `/docs/tmp/refactor-plan-[timestamp].md`
    - Ask for confirmation before proceeding
4. List changes with impact level:
    - 🔴 Breaking: [change]
    - 🟡 Major: [change]
    - 🟢 Safe: [change]
5. End with: "✅ Refactoring complete. [X] improvements made."
