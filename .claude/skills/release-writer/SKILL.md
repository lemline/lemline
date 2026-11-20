---
name: release-writer
description: Generate user-oriented release notes for Lemline in the project's four-part format (Summary, New Features, Improvements, Bug Fixes), written from the user's point of view and ready to paste into the GitHub release.
---

# Release Notes Writing Skill

## Purpose

Turn the commits of a release into notes a Lemline **user** reads to decide whether to upgrade,
what they can now do, and what they must do first. The notes are pasted as-is into the GitHub
release body.

The reference is the published [0.7.0 notes](lemline-example.md). The empty template is
[release-notes.md](release-notes.md).

## The Format: Four Sections, in This Order

```markdown
## Summary
## New Features
## Improvements
## Bug Fixes
```

No other top-level section. In particular there is **no** "Dependencies", "Breaking Changes",
"Migration Guide" or "Architecture" section:

- **Breaking changes** go in a `> **Before you upgrade from X.Y.x**` block at the end of the
  Summary, so nobody can miss them (see below). If there are none, leave the block out.
- **Dependencies** appear only when a user notices them, and then as an Improvement written as a
  benefit ("Lemline follows the Open Workflow Specification Java SDK 7.35.3, so definitions
  written for other compliant runtimes behave the same way"). Internal libraries, build tools and
  dashboard libraries are left out.

The notes end with one line: `**Full changelog**: https://github.com/lemline/lemline/compare/<prev-tag>...<new-tag>`.
The notes have no `# {version} Release Notes` title: GitHub already shows the release name.

## The Rule: Write What the User Sees

Every entry answers, in this order:

1. **What can I now do, or what stopped going wrong?** In the user's words.
2. **How do I use it?** A short, copy-pasteable example, for the main features only.

The mechanism is left out unless the user must act on it. Remove or translate anything that only
makes sense to someone who has read the code:

| Instead of | Write |
|------------|-------|
| "Deliveries are deduplicated by CloudEvent `source` + `id`" | "The same event delivered twice never starts two instances." |
| "Gateway starts are dispatched immediately instead of waiting for the next outbox poll" | "A workflow started through the gateway begins right away, instead of waiting up to the next database check." |
| "The engine no longer converts JSON to text and back" | "The engine spends less time converting data during expressions and validation." |
| "A message is parked with reason `foreign_namespace`" | "A message that reaches the wrong runner is set aside, not executed." |

Config keys, DSL keywords, CLI commands and event types stay in `backticks`, because the user types
them. Class names, table names, module names, internal message formats and per-broker topology do
not appear; link to the docs for those.

## Section Guidelines

### Summary

- One sentence on the theme of the release, then **3–6 bullets**, one per headline capability,
  with what users can now do in **bold**.
- For a patch release, one or two sentences are enough.
- **Before you upgrade block** — only when an upgrade needs action. A numbered list, one action
  per item, each starting with the action in **bold** (stop runners, validate definitions, update
  consumers…), followed by what changed in one sentence and a link to the migration doc if any.
  Write what to do, not the internal reason.

  ```markdown
  > **Before you upgrade from 0.6.x**
  >
  > 1. **Stop or drain every 0.6.x runner before starting 0.7.0 runners.** The two versions cannot work side by side.
  > 2. **Validate your stored workflow definitions.** Shell `arguments` are now a list (`[Hello, World]`). See the [migration guide](…).
  ```

### New Features

- One `###` per feature, titled with what it lets the user do ("Start a workflow when events
  arrive", "Use secrets in workflows"), not with the internal or DSL name alone.
- One or two sentences of description, then an example for the main features. Bullets for the
  variants or rules that matter (what happens when a secret is missing, for example).
- Group small additions (new expression variables, new DSL options) under one
  "More possibilities in workflow definitions" feature with one bullet each.
- No "Why X?" sub-sections and no feature tables: the description already says what it is for.

### Improvements

Existing behaviour made better: faster, clearer, closer to the specification. One bullet each:
`- **{What the user notices}**: {what is better, in observable terms}.` Measured numbers when
available. Internal refactors, test changes and CI changes are left out unless the user notices
the effect.

### Bug Fixes

One bullet per component: `- **{Component}**: {what now works}. {What the user suffered before, if
not obvious}.` Describe the symptom, not the cause. Merge fixes of the same component into one
bullet. Fixes of bugs that never shipped in a previous release are left out.

## Gathering the Changes

```bash
git log <prev-tag>..<new-tag> --oneline --no-merges
git log <prev-tag>..<new-tag> --pretty=format:"### %h %s%n%b%n---" --no-merges
```

Read the body of every commit whose subject is unclear, and the diff when the body does not say
what the user sees. Commits are not prefixed consistently in this repository, so classify by
effect, not by prefix:

| The commit… | Goes to |
|-------------|---------|
| lets users do something they could not | New Features |
| makes existing behaviour faster, clearer or more compliant | Improvements |
| removes a symptom users could hit in the previous release | Bug Fixes |
| requires an action at upgrade (renamed destinations, rejected syntax, renamed event types, incompatible formats) | the Before you upgrade block, **and** its feature or fix entry if it has one |
| only changes tests, CI, internal docs, agent tooling or internal structure | nothing |

## Writing Style

- Active voice, present tense for what is now true: "A runner no longer takes events meant for
  another namespace's runner."
- One idea per sentence. Short sentences.
- Realistic example values (`acme-prod`, `paymentApiKey`), never `foo` or `example`.
- Every example must be valid for the released version: check the DSL keys against the docs.
- English only.

## Checklist Before Publishing

- [ ] Exactly the four sections, in order, plus the Full changelog line
- [ ] Every user-facing change is there; nothing internal is
- [ ] Every action required at upgrade is in the Before you upgrade block
- [ ] No class, table, module or internal message name; no unexplained jargon
- [ ] Examples are valid for this version
- [ ] Links resolve (docs on `main`, compare URL with the right tags)

## Anti-Patterns

❌ Commit message dumps
❌ Explaining the mechanism instead of the effect ("outbox poll", "deduplicated by id")
❌ Breaking changes buried in a section at the end
❌ A Dependencies table of library versions
❌ Internal-only changes ("Refactored tests", "Reorganized packages")
