<!--
================================================================================
SYNC IMPACT REPORT
================================================================================
Version change: N/A → 1.0.0 (initial constitution)
Modified principles: N/A (new document)
Added sections:
  - Core Principles (5 principles)
  - Performance Standards
  - Development Workflow
  - Governance
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md: ✅ compatible (Constitution Check section exists)
  - .specify/templates/spec-template.md: ✅ compatible (functional requirements section exists)
  - .specify/templates/tasks-template.md: ✅ compatible (test-first approach aligned)
Follow-up TODOs: None
================================================================================
-->

# Lemline Constitution

## Core Principles

### I. Code Quality First

All code MUST be readable, maintainable, and follow established patterns.

- **Consistency**: Follow Kotlin coding conventions with 4-space indentation and 120-character line limit
- **Immutability**: Prefer immutable data structures; use `val` over `var`, immutable collections over mutable
- **Single Responsibility**: Each class/function MUST have one clear purpose; split if doing multiple things
- **Explicit over Implicit**: Avoid magic; prefer explicit configuration and clear data flow
- **No Dead Code**: Remove unused code, commented-out blocks, and unreachable paths; version control preserves history
- **Security by Default**: Validate all external inputs, never commit credentials, follow OWASP guidelines

**Rationale**: Lemline is infrastructure software where bugs have cascading effects. Clear, maintainable code reduces defects and enables confident refactoring as the system evolves.

### II. Comprehensive Testing

All functionality MUST be covered by appropriate tests at the correct level.

- **Unit Tests**: Required for all business logic, expression evaluation, and state transitions
- **Integration Tests**: Required for database operations, messaging flows, and cross-component interactions
- **Contract Tests**: Required when adding or modifying external APIs (REST, messaging schemas)
- **Test Independence**: Each test MUST be self-contained and not depend on execution order or shared mutable state
- **Coroutines Testing**: Use Kotlin coroutines test utilities; all repository tests MUST use `suspend` functions directly
- **Coverage Threshold**: New code MUST maintain or improve existing coverage; critical paths (Processor, StepByStepRunner) require >80% coverage

**Rationale**: Lemline orchestrates business-critical workflows. Test coverage prevents regressions and enables safe evolution of the codebase. The event-driven architecture requires careful testing of async flows.

### III. User Experience Consistency

CLI commands and APIs MUST provide predictable, helpful interactions.

- **Consistent Output Formats**: All commands MUST support both human-readable (default) and JSON output formats
- **Helpful Error Messages**: Errors MUST include: what failed, why it failed, and how to fix it
- **Progressive Disclosure**: Show essential info by default, detailed info with flags (--verbose, --debug)
- **Idempotent Operations**: Commands that can be safely re-run MUST be idempotent where possible
- **Configuration Hierarchy**: Honor the defined precedence (CLI args > env vars > config files > defaults) consistently
- **OpenAPI Documentation**: All REST endpoints MUST have complete OpenAPI descriptions

**Rationale**: Lemline is an infrastructure tool used by developers and operators. A consistent, predictable interface reduces cognitive load and enables automation.

### IV. Performance by Design

Performance MUST be considered from design phase, not retrofitted.

- **Minimize Database Access**: Carry state in messages; database is for durability, not primary state management
- **Batch Operations**: Database writes MUST use batch inserts; avoid N+1 query patterns
- **Async by Default**: Use Kotlin coroutines (`suspend` functions) for all I/O operations
- **Connection Pooling**: Properly size connection pools; never hold connections during compute-bound work
- **Measurable Targets**: Performance-critical paths MUST have defined latency budgets (p50, p95, p99)
- **No Premature Optimization**: Optimize based on measurements, not assumptions; profile before optimizing

**Rationale**: Lemline's value proposition is high-throughput, low-latency workflow orchestration. The dual-channel architecture and stateless workers require disciplined performance practices to maintain their benefits.

### V. Simplicity and Minimalism

Complexity MUST be justified and minimized.

- **YAGNI**: Do not add features, abstractions, or configurability until actually needed
- **Three Strikes Rule**: Tolerate duplication twice; extract abstraction only on third occurrence
- **Minimal Dependencies**: Each new dependency MUST be justified; prefer standard library solutions
- **Flat Hierarchies**: Avoid deep inheritance; prefer composition and interfaces
- **Delete Freely**: Remove unused code, deprecated features, and backward-compatibility shims when safe
- **Documentation Sparingly**: Code should be self-explanatory; add comments only where the "why" is not obvious

**Rationale**: Every line of code is a liability. Simpler systems are easier to understand, test, debug, and evolve. Complexity compounds over time.

## Performance Standards

Performance requirements specific to Lemline's architecture:

| Component | Metric | Target | Measurement Method |
|-----------|--------|--------|-------------------|
| Message Processing | Throughput | >10,000 msg/sec per worker | Benchmark suite |
| Message Processing | Latency (p95) | <10ms per step | Micrometer metrics |
| Database Operations | Batch Insert | <50ms for 100 records | Integration tests |
| Outbox Relay | Cycle Time | <100ms from due to sent | Micrometer metrics |
| Startup Time (JVM) | Cold Start | <3 seconds | CI measurement |
| Startup Time (Native) | Cold Start | <100ms | CI measurement |
| Memory (JVM) | Heap Usage | <512MB baseline | Profiler |
| Memory (Native) | RSS | <128MB baseline | Container metrics |

**Regression Policy**: Performance regressions >10% on critical paths MUST be justified or fixed before merge.

## Development Workflow

### Code Review Requirements

- All changes MUST be reviewed before merge
- Reviewers MUST verify compliance with this Constitution
- Performance-impacting changes MUST include benchmark results
- Database schema changes MUST include migration scripts for all supported databases

### Quality Gates

1. **Pre-commit**: Linting and formatting checks pass
2. **CI Pipeline**: All tests pass on all supported databases
3. **Coverage**: No decrease in test coverage for modified files
4. **Performance**: No unexplained regressions in benchmark suite
5. **Documentation**: OpenAPI specs updated for API changes

### Commit Standards

- Use conventional commit format: `type(scope): description`
- Types: feat, fix, refactor, test, docs, perf, chore
- Reference issue numbers when applicable
- Keep commits atomic and focused

## Governance

### Authority

This Constitution represents the foundational principles for Lemline development. It supersedes ad-hoc decisions and establishes non-negotiable standards.

### Amendment Process

1. Propose amendment via pull request to this file
2. Document rationale for the change
3. Amendments require review and approval
4. Update version according to semantic versioning:
   - **MAJOR**: Principle removal or fundamental redefinition
   - **MINOR**: New principle added or existing principle materially expanded
   - **PATCH**: Clarifications, typo fixes, non-semantic refinements

### Compliance

- All pull requests MUST comply with these principles
- Violations MUST be flagged in code review
- Complexity deviations MUST be documented with justification
- Runtime guidance in `CLAUDE.md` provides implementation details aligned with these principles

### Exceptions

Exceptions to these principles require:

1. Written justification documenting why the exception is necessary
2. Scope limitation (temporary, specific component)
3. Plan for eventual compliance where feasible

**Version**: 1.0.0 | **Ratified**: 2025-12-08 | **Last Amended**: 2025-12-08
