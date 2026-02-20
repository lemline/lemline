<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner

Quarkus runtime with dual-channel messaging, CLI, and configuration.

## Before Working Here

**Invoke skill:** `runner-dev`

The skill provides: messaging architecture, outbox pattern, repository patterns, CLI commands, database migrations.

## Critical Rules

- Use `suspend` functions with Kotlin coroutines (NOT Mutiny Uni)
- Use native SQL with repositories (NOT Hibernate ORM)
- Support all 3 databases: PostgreSQL, MySQL, H2
- Use `FOR UPDATE SKIP LOCKED` for outbox queries
