---
description: Generate user-oriented release notes for a new version using the release-writer skill.
---

## Usage

```
/release <version> [--from <tag>] [--draft]
```

## Arguments

- `<version>` - The version number (e.g., `0.5.3`, `1.0.0`)

## Options

- `--from <tag>` - Previous version tag to compare from (default: latest tag)
- `--draft` - Generate a draft template without fetching git history

## Examples

```bash
# Generate release notes for v0.5.3
/release 0.5.3

# Specify the previous version explicitly
/release 1.0.0 --from v0.9.5

# Generate a draft template to fill in manually
/release 0.6.0 --draft
```

## Instructions

When this command is invoked:

1. **Read the release-writer skill** at `SKILL.md` to understand the guidelines and best practices
2. **Follow the workflow below** to gather, categorize, and transform changes
3. **Generate release notes** following the structure in `release-notes.md`
4. **Reference the example** at `lemline-example.md` for style guidance

---

## Workflow

### Step 1: Gather Changes

```bash
# Get the previous tag if not specified
git describe --tags --abbrev=0

# List commits since last tag
git log <previous-tag>..HEAD --oneline --no-merges

# Get detailed commit messages
git log <previous-tag>..HEAD --pretty=format:"### %s%n%b%n---" --no-merges
```

### Step 2: Categorize by Conventional Commits

| Prefix | Section |
|--------|---------|
| `feat:` | New Features |
| `fix:` | Bug Fixes |
| `perf:` | Improvements |
| `refactor:` | Improvements (if user-visible) |
| `deps:` | Dependencies |
| `BREAKING CHANGE:` | Breaking Changes |
| `docs:`, `test:`, `ci:`, `chore:` | Evaluate individually |

### Step 3: Transform to User-Oriented Language

Apply the principles from `SKILL.md`:

- **What changed?** → The feature or fix
- **Why does it matter?** → The benefit to users
- **How do I use it?** → Configuration and examples

**Before (developer):** `fix: resolve race condition in PgmqIncomingConnector shutdown`

**After (user):** `Fix graceful shutdown: PGMQ connector now properly handles shutdown events to prevent error logs during teardown`

### Step 4: Generate Release Notes

Follow the structure from `SKILL.md`:

```markdown
# {version} Release Notes

## Summary
<!-- MINOR/MAJOR only: 2-4 sentences on release theme -->

## New Features

### {Feature Name}
{Description}

#### Why {Feature Name}?
- {Use case 1}
- {Use case 2}

#### Configuration
```yaml
# Complete, copy-pasteable example
```

## Bug Fixes

- **{Component}**: {What was fixed} — {User impact}

## Improvements

- **{Area}**: {What improved} — {Measurable benefit}

## Dependencies

| Dependency | Version | Notes |
|------------|---------|-------|
| {name} | {ver} | {why added/updated/removed} |

## Breaking Changes

None.

## Full Changelog

Compare: https://github.com/{org}/{repo}/compare/{prev-tag}...v{version}
```

### Step 5: Verify Against Checklist

From `SKILL.md`, confirm:

- [ ] All user-facing changes documented
- [ ] Code examples tested and working
- [ ] Breaking changes section present (even if "None")
- [ ] Links are valid
- [ ] Consistent formatting throughout
- [ ] No internal jargon without explanation

---

## Output

Save the generated release notes to `/tmp/RELEASE-{version}.md` and present to the user.
