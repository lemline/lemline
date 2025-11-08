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
5. ✅ Types: Add TypeScript/PropTypes if missing

## Focus Areas:

### For frontend scope

**📚 Required Reading Before Refactoring:**

- `/docs/frontend/architecture/decisions.md` - React app structure and patterns
- `/docs/frontend/development/guide-code-quality.md` - Code quality guidelines
- `/docs/frontend/theming/` - Theme and styling conventions

1. Component Architecture (React 19):
    - Split large components (>150 lines) into smaller, focused ones
    - Extract custom hooks for shared stateful logic
    - Create proper TypeScript interfaces for props
    - Follow component organization from ARCHITECTURE_DECISIONS.md

2. Code Organization:
    - Group related utilities into dedicated files
    - Establish clear separation between business logic and UI
    - Implement consistent file/folder structure per ARCHITECTURE_DECISIONS.md
    - Use API path constants from backend (ApiPaths) for consistent routing

3. Performance & Quality:
    - Remove unnecessary re-renders (memo, useCallback, useMemo)
    - Eliminate dead code and unused imports
    - Replace complex conditionals with early returns or lookup objects
    - Optimize bundle size with lazy loading and code splitting

4. Maintainability:
    - Add JSDoc comments for complex functions
    - Create constants for magic numbers and repeated strings
    - Implement error boundaries for robust error handling
    - Follow TypeScript strict mode guidelines

5. Guidelines & Best Practices (see BEST_PRACTICES.md):
    - **NEVER hardcode colors** - use theme variables from THEMING.md
    - **Use Catalyst UI components** - avoid building custom components
    - Use Headless UI for complex interactive components
    - Check accessibility (ARIA labels, keyboard navigation, screen reader support)
    - Fix all TypeScript errors before committing
    - Use React Hook Form for form management
    - Follow naming conventions from BEST_PRACTICES.md

### For backend scope

**📚 Read Relevant guidelines Before Refactoring:**

- `/docs/backend/reactive/guide-http-best-practices.md` - Comprehensive endpoint best practices
- `/docs/backend/reactive/guide-setup.md` - Reactive setup and architecture
- `/docs/backend/reactive/guide-mutations.md` and `/docs/backend/reactive/guide-queries.md` - Quarkus reactive patterns with Mutiny
- `/docs/backend/reactive/guide-extensions.md` - Kotlin-friendly reactive extensions
- `/docs/backend/testing/guide-reactive.md` - Testing reactive code
- `/docs/backend/monitoring/` - Metrics documentation requirements (metrics-*.md files)
- `/docs/backend/database/guide-setup.md` - Schema change guidelines
- `/docs/backend/testing/guide-endpoints.md` - Testing patterns and best practices

1. Service Architecture (Quarkus + Kotlin):
    - Split large services (>150 lines) into smaller, domain-focused ones
    - Extract cross-cutting concerns into interceptors (@Logged, @Timed)
    - **ALWAYS create DTOs** - NEVER expose entity objects directly (see guide-http-best-practices.md)
    - DTOs location: `lemline-common/src/main/kotlin/com/lemline/common/dto/`
    - Mappers location: `lemline-backend/src/main/kotlin/com/lemline/mapper/`
    - Implement repository pattern using Hibernate Reactive Panache

2. Code Organization:
    - Group related endpoints into cohesive JAX-RS resource classes
    - Establish clear separation: Resource → Service → Repository layers
    - Package structure: `com.lemline.{resource, service, domain, repository}`
    - **Use ApiPaths constants** - NEVER hardcode API paths (see guide-http-best-practices.md)
    - Example: `@Path(ApiPaths.Users.BASE)` not `@Path("/api/v1/users")`
    - Use ApiPaths helper functions for Location URIs: `ApiPaths.Users.byId(id)`
    - Extract common reactive operations into extension functions

3. Reactive Patterns (Mutiny - NOT Coroutines):
    - Return `Uni<T>` or `Multi<T>` from all endpoints and repository methods
    - Use Kotlin-friendly extensions: `.mapIt`, `.flatMapIt`, `.orNotFound()` (see reactive/guide-extensions.md)
    - Apply `@WithSession` for GET operations, `@WithTransaction` for POST/PUT/DELETE
    - Avoid blocking operations in reactive chains (see reactive/guide-blocking-io.md)
    - Use `@TestReactiveTransaction` with `await()` helper in tests (see testing/guide-reactive.md)
    - **Why Mutiny over Coroutines**: Hibernate Reactive transactions require Mutiny

4. Performance & Quality:
    - Optimize database queries (avoid N+1, use JPA relationships with `@ManyToOne`, `@OneToMany`)
    - Replace complex conditionals with when expressions or strategy pattern
    - Use lazy initialization and proper JPA fetch strategies (`FetchType.LAZY`)
    - Leverage reactive streams for backpressure handling

5. Maintainability:
    - Add KDoc comments with @throws, @param, @return annotations
    - **Use ApiPaths constants** for all URLs - NEVER hardcode path strings
    - Use sealed classes for domain errors and result types
    - Apply Kotlin idioms (scope functions, null safety, data classes)
    - Log with context (userId, resourceId, operation) - NEVER log sensitive data (passwords, tokens)
    - Extract enum parsing to reusable extension functions (see AuthType.kt, IntegrationCategory.kt)

6. API Contract & Security (see guide-http-best-practices.md):
    - Define comprehensive OpenAPI schemas: `@Operation`, `@APIResponse`, `@Parameter`
    - Implement bean validation (`@Valid`, `@NotNull`, `@NotBlank`, custom validators)
    - **Add authorization annotations**: `@RolesAllowed(ADMIN_ROLE)`, `@PermitAll`, `@RolesAllowed(USER_ROLE)`
    - **Add email verification**: `@RequiresEmailVerification` for sensitive operations
    - Test ALL authorization scenarios: 401 Unauthorized, 403 Forbidden, success cases
    - Create consistent error responses using `ErrorResponse` DTO
    - Set Location header for 201 Created responses using ApiPaths helper functions
    - Use proper HTTP status codes: 200 OK, 201 Created, 204 No Content, 400 Bad Request, etc.

7. Testing & Observability:
    - **Add business metrics** for all operations, document in `/docs/backend/monitoring/` (metrics-*.md files)
    - Examples: `registry.counter("integration.creation.attempt").increment()`
    - Structure code for testability (CDI, interfaces)
    - Create integration tests with `@QuarkusTest`
    - Use `UniAsserter` with `await()` helper for reactive tests (see testing/guide-reactive.md)
    - Test validation, authorization, error cases, and edge cases

8. Kotlin-Specific Patterns to Apply:
    - Replace if-else chains with when expressions
    - Convert null checks to scope functions (`?.let`, `?:` elvis operator)
    - Extract common logic to extension functions (e.g., `String.toAuthType()`)
    - Use data classes for DTOs with `@Serializable` annotation
    - Use companion objects for enum utility functions (fromDisplayName, validValues)
    - ALWAYS use idiomatic Kotlin

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

- [ ] No TypeScript or Kotlin errors
- [ ] All tests pass
- [ ] Bundle size impact checked
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
