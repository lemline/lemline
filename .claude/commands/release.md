---
description: Generate user-oriented release notes for a new version using the release-writer skill.
---

## Usage

```
/release <version> [--from <tag>] [--draft]
```

## Arguments

- `<version>` - The version number (e.g., `0.7.1`, `1.0.0`)

## Options

- `--from <tag>` - Previous version tag to compare from (default: latest tag)
- `--draft` - Copy the empty template without reading git history

## Examples

```bash
/release 0.7.1
/release 1.0.0 --from v0.9.5
/release 0.8.0 --draft
```

## Instructions

1. **Read the release-writer skill** (`.claude/skills/release-writer/SKILL.md`): the four-part
   format, the user-first rule, and how to classify commits.
2. **Read the reference** (`lemline-example.md`, the published 0.7.0 notes) and start from the
   template (`release-notes.md`).
3. Follow the workflow below.

---

## Workflow

### Step 1: Gather changes

```bash
git describe --tags --abbrev=0                      # previous tag, if --from is not given
git log <previous-tag>..HEAD --oneline --no-merges
git log <previous-tag>..HEAD --pretty=format:"### %h %s%n%b%n---" --no-merges
```

Open the body, and if needed the diff, of every commit whose subject does not say what a user sees.

### Step 2: Classify by effect

Commit prefixes are not used consistently here: classify each commit by what the user sees, with
the table in `SKILL.md` (New Features, Improvements, Bug Fixes, Before you upgrade block, or
nothing). Drop tests, CI, internal docs, agent tooling and refactors without a visible effect.

### Step 3: Write it from the user's point of view

Each entry says what users can now do, or what stopped going wrong, in their words. The mechanism
stays out.

**Before (developer):** `Speed up gateway workflow starts: dispatch the outbox row immediately`

**After (user):** `**Faster starts from the gateway**: a workflow started through the gateway begins right away, instead of waiting up to the next database check.`

### Step 4: Assemble the four sections

`## Summary` (with the Before you upgrade block when an upgrade needs action), `## New Features`,
`## Improvements`, `## Bug Fixes`, then the `**Full changelog**` line. No other section, no
version title.

### Step 5: Check

Run the checklist in `SKILL.md`. Verify every example against the docs of this version.

---

## Output

Save the notes as `RELEASE-{version}.md` at the repository root and show them to the user. Once
the release exists, they are published with:

```bash
gh release edit v{version} --notes-file RELEASE-{version}.md
```

See `docs/release-procedure.md` for the whole release.
