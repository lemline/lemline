# {VERSION} Release Notes

## Summary

<!-- 
For MINOR/MAJOR releases: Write 2-4 sentences summarizing the release theme.
Delete this section for PATCH releases.
Example: "This release introduces PGMQ as a new messaging backend, enabling 
workflow orchestration using only PostgreSQL — no separate message broker required."
-->

---

## New Features

### {Feature Name}

<!-- Brief description of what the feature does -->

#### Why {Feature Name}?

<!-- 
Explain use cases and benefits. Help users understand when to use this.
Use bullet points for multiple scenarios.
-->

{Feature Name} is ideal for deployments where:

- {Use case 1}
- {Use case 2}
- {Use case 3}

#### Features

| Feature | Description |
|---------|-------------|
| **{Feature 1}** | {Description} |
| **{Feature 2}** | {Description} |
| **{Feature 3}** | {Description} |

#### Configuration

```yaml
# Example configuration — replace with actual config
your-app:
  feature:
    enabled: true
    option: value
```

#### Example

```bash
# Usage example
your-command --flag value
```

<!-- Repeat ### {Feature Name} for additional features -->

---

## Bug Fixes

<!-- Format: Component: Description of fix — Impact on users -->

- **{Component}**: {Description of what was fixed} — {How this affected users before}
- **{Component}**: {Description of what was fixed} — {How this affected users before}

---

## Improvements

<!-- Format: Area: What improved — Measurable benefit if available -->

- **{Area}**: {What improved} — {Benefit, e.g., "reducing test overhead by 40%"}
- **{Area}**: {What improved} — {Benefit}

---

## Dependencies

<!-- Only include if there are dependency changes users should know about -->

| Dependency | Version | Notes |
|------------|---------|-------|
| {package} | {version} | {Why added/updated/removed} |

---

## Breaking Changes

<!-- 
ALWAYS include this section, even if empty.
For actual breaking changes, provide migration guidance.
-->

None.

<!-- Or, if there are breaking changes:

### {Breaking Change Title}

**What changed:** {Description}

**Why:** {Reason for the change}

**Migration:**

Before:
```yaml
old-config: value
```

After:
```yaml
new-config: value
```

-->

---

## Full Changelog

Compare: https://github.com/{org}/{repo}/compare/{previous-tag}...{new-tag}
