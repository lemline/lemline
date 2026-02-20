<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# buildSrc

Gradle convention plugins for consistent build configuration across all modules.

## Key Files

| File | Description |
|------|-------------|
| `kotlin-jvm.gradle.kts` | Shared Kotlin/JVM build conventions (compiler options, dependencies, test config) |

## For AI Agents

### Working In This Directory
- Convention plugins replace a root `build.gradle.kts`
- Changes here affect **all modules** build configuration
- Kotlin compiler options, dependency versions, and test frameworks configured here

### Dependencies
- Gradle Kotlin DSL
- No internal dependencies

<!-- MANUAL: -->
