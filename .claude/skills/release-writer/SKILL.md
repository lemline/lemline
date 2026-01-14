---
name: release-writer
description: Generate user-oriented release notes for open-source projects, following best practices that help users understand what changed, why it matters, and how to adopt new features.
---

# Release Notes Writing Skill

## Purpose

Generate user-oriented release notes for open-source projects, following best practices that help users understand what changed, why it matters, and how to adopt new features.

## When to Use This Skill

- Writing release notes for a new version
- Drafting changelog entries from git commits or PR descriptions
- Converting technical changes into user-friendly documentation
- Preparing announcements for GitHub releases

## Core Principles

### 1. User-First Perspective

Release notes serve **users**, not developers. Every entry should answer:
- **What changed?** (the feature/fix)
- **Why does it matter?** (the benefit to users)
- **How do I use it?** (configuration, examples)

❌ Bad: "Refactored MessageHandler to use Strategy pattern"
✅ Good: "Message processing is now 40% faster and supports custom handlers"

### 2. Semantic Versioning Context

Align your notes with [SemVer](https://semver.org/):
- **MAJOR (X.0.0)**: Breaking changes requiring user action
- **MINOR (0.X.0)**: New features, backward compatible
- **PATCH (0.0.X)**: Bug fixes, backward compatible

### 3. Completeness Over Brevity

Include ALL user-facing changes. Users rely on release notes to:
- Decide whether to upgrade
- Understand migration requirements
- Discover new capabilities

## Release Note Structure

Use this template for consistency:

```markdown
# {version} Release Notes

## Summary
<!-- For MINOR/MAJOR releases: 2-3 sentences highlighting the theme -->

## New Features

### {Feature Name}
<!-- What it does, why it matters -->

#### Why {Feature Name}?
<!-- Use cases and benefits -->

#### Configuration
```yaml
# Example configuration
```

#### Example
```bash
# Usage example
```

## Bug Fixes

- **{Component}**: {Description of fix} — {Impact on users}

## Improvements

- **{Area}**: {What improved} — {Measurable benefit if available}

## Dependencies

| Dependency | Version | Notes |
|------------|---------|-------|
| {name}     | {ver}   | {why} |

## Breaking Changes

<!-- ALWAYS include this section, even if empty -->
{Description of breaking change and migration path}

OR

None.

## Migration Guide
<!-- For MAJOR versions or significant changes -->

### Before
```yaml
# Old configuration
```

### After
```yaml
# New configuration
```

## Full Changelog

Compare: https://github.com/{org}/{repo}/compare/{prev-tag}...{new-tag}
```

## Section Guidelines

### New Features

**Structure each feature with:**
1. **Name**: Clear, descriptive title (not internal code names)
2. **Description**: What it does in user terms
3. **Why section**: Explains use cases and benefits
4. **Configuration**: Complete, copy-pasteable examples
5. **Architecture notes**: Only if users need to understand (e.g., for deployment)

**Use tables for feature comparisons:**
```markdown
| Feature | Description |
|---------|-------------|
| **SQL-Only Implementation** | Uses Flyway migrations — no extension required |
| **Message Deduplication** | Unique index prevents duplicate messages |
```

### Bug Fixes

**Format:** `{Component}: {What was fixed} — {User impact}`

Good examples:
- "Fix PGMQ message ordering: Sort results by msg_id to ensure FIFO order"
- "Fix native image compilation: Resolve Netty buffer initialization conflicts"

**Include:**
- The symptom users experienced
- What was wrong (briefly)
- The fix's effect

### Improvements

Distinguish from features — improvements enhance existing functionality:
- Performance gains (with metrics if available)
- Developer experience enhancements
- Internal optimizations with user-visible benefits

### Breaking Changes

**ALWAYS include this section.** Even "None." tells users they can upgrade safely.

For actual breaking changes:
1. Describe what changed
2. Explain why
3. Provide migration path with before/after examples
4. Estimate migration effort

### Dependencies

List changes that users might care about:
- New required dependencies
- Removed dependencies (less to install!)
- Major version bumps of significant libraries
- Security-related updates

## Writing Style Guidelines

### Voice and Tone

- **Active voice**: "Added retry logic" not "Retry logic was added"
- **Present tense for states**: "Workflows now support..."
- **Past tense for actions**: "Fixed race condition in..."
- **Direct**: Avoid hedging ("should", "might", "could")

### Technical Clarity

- Define acronyms on first use
- Link to documentation for complex concepts
- Use consistent terminology throughout
- Include version numbers for dependencies

### Code Examples

**Always provide:**
- Complete, runnable examples
- Realistic values (not just "example" or "foo")
- Comments explaining non-obvious parts
- Both minimal and realistic configurations

```yaml
# ✅ Good example
lemline:
  messaging:
    type: pgmq
    pgmq:
      host: localhost
      port: 5432
      database: lemline
      queue: lemline-commands
      visibility-timeout: 30  # seconds before message redelivery
```

```yaml
# ❌ Bad example
config:
  value: example
```

### Formatting Conventions

- Use `**bold**` for component/feature names in lists
- Use `backticks` for code, commands, config keys
- Use tables for structured comparisons
- Use headers (##, ###) for navigation
- Use horizontal rules (---) sparingly, only between major sections

## Open-Source Best Practices

### 1. Credit Contributors

For community-contributed features:
```markdown
### New Feature X
Contributed by @username in #PR_NUMBER
```

### 2. Link to Issues and PRs

Help users find more context:
```markdown
- Fix memory leak in worker pool (#123)
- Add WebSocket support (requested in #89)
```

### 3. Deprecation Notices

Warn users before removing features:
```markdown
## Deprecations

- `legacyMode` configuration is deprecated and will be removed in v2.0
  - Migration: Use `compatibilityMode` instead
  - Timeline: Removal planned for Q2 2025
```

### 4. Security Notices

Highlight security-relevant changes:
```markdown
## Security

- **CVE-2024-XXXX**: Fixed XSS vulnerability in dashboard
  - Severity: Medium
  - Affected versions: 1.2.0 - 1.2.5
  - Recommendation: Upgrade immediately
```

### 5. Upgrade Difficulty Indicator

Help users plan upgrades:
```markdown
## Upgrade Notes

**Difficulty: Easy** — No configuration changes required
**Difficulty: Moderate** — Configuration updates needed (see Migration Guide)
**Difficulty: Complex** — Database migration required, plan maintenance window
```

## Generating Release Notes from Git

### From Git Log

```bash
# Get commits since last tag
git log v0.5.1..HEAD --oneline --no-merges

# Get commits with full messages
git log v0.5.1..HEAD --pretty=format:"- %s%n%b"
```

### From GitHub PRs

```bash
# Using GitHub CLI
gh pr list --state merged --base main --search "merged:>=2024-01-01"
```

### Categorizing Changes

Use conventional commit prefixes to auto-categorize:
- `feat:` → New Features
- `fix:` → Bug Fixes
- `perf:` → Improvements
- `docs:` → Documentation (usually not in release notes)
- `chore:` → Dependencies / Internal (selective inclusion)
- `BREAKING CHANGE:` → Breaking Changes

## Checklist Before Publishing

- [ ] Version number matches tag
- [ ] All user-facing changes documented
- [ ] Code examples tested and working
- [ ] Breaking changes section present (even if "None")
- [ ] Links (changelog, PRs, issues) are valid
- [ ] Consistent formatting throughout
- [ ] No internal jargon without explanation
- [ ] Migration path provided for breaking changes
- [ ] Dependencies section updated if relevant
- [ ] Security issues clearly marked if applicable

## Examples

See real-world examples in the Lemline project:
- Feature-heavy release: v0.5.0, v0.5.2
- Bug fix release: v0.5.1
- Architecture changes: v0.3.0, v0.4.0

## Anti-Patterns to Avoid

❌ **Commit message dumps**: Raw git logs are not release notes
❌ **Internal-only changes**: "Refactored tests" doesn't help users
❌ **Missing context**: "Fixed bug" without explaining what bug
❌ **Jargon overload**: "Implemented CQRS with event sourcing" (explain benefits)
❌ **No examples**: Features without usage examples
❌ **Hidden breaking changes**: Burying them in other sections
❌ **Inconsistent formatting**: Mixing styles within a release
❌ **Stale links**: Links to old documentation or dead URLs
