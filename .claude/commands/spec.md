# Spec Command

Create a detailed technical specification for a new feature in this Quarkus + Kotlin reactive application.

**Feature request:** {{placeholderText}}

## Process

1. **Review documentation** to understand project standards:
    - `/docs/backend/reactive/guide-http-best-practices.md` - Complete endpoint creation checklist
    - `/docs/backend/reactive/guide-mutations.md` and `/docs/backend/reactive/guide-queries.md` - Reactive patterns (@WithSession, @WithTransaction)
    - `/docs/backend/testing/guide-reactive.md` - Testing reactive code
    - `/docs/backend/monitoring/` - Metrics documentation and naming conventions (metrics-*.md files)
    - `/docs/backend/database/guide-setup.md` - Migration workflow
    - `/docs/backend/database/guide-schema-changes.md` - Migration examples and rollback patterns

2. **Read the codebase** to understand existing patterns:
    - Explore similar entities, DTOs, resources, repositories
    - Check authorization patterns (UserResource.kt, AuthResource.kt)
    - Review existing migrations (db/migration/)

3. **Create specification with sensible defaults:**
    - **Authorization**: Default to @RolesAllowed(USER_ROLE); use ADMIN_ROLE for admin ops; @PermitAll only for
      auth/public endpoints
    - **Session/Transaction**: Use @WithSession for reads (GET), @WithTransaction for writes (POST/PUT/PATCH/DELETE)
    - **Data model**: UUID primary keys, created_at/updated_at timestamps, snake_case DB names
    - **REST**: Follow /api/v1/[resource] pattern with standard HTTP methods
    - **DTOs**: Always use DTOs (never expose entities), put in lemline-common
    - **Reactive**: All endpoints return Uni<Response>, use .mapIt/.flatMapIt/.toResponse()/.orNotFound()
    - **Metrics**: Add business-relevant metrics and document in /docs/backend/METRICS.md
    - **OpenAPI**: Add @Operation, @APIResponse annotations with descriptions
    - **Testing**: Include authorization tests (401/403/success) and functional tests

4. **Write specification to `/docs/features/spec_[feature_name].md`** containing:
   ```markdown
   # Feature: [Name]

   ## Overview
   Brief description and business value

   ## API Endpoints

   ### POST /api/v1/[resource]
   - **Authorization**: @RolesAllowed(USER_ROLE)
   - **Session/Transaction**: @WithTransaction
   - **Request**: `CreateXRequest`
   - **Response**: `XResponse` (201 Created)
   - **Errors**: 400, 401, 403, 409
   - **Metrics**: `x.created` counter
   - **OpenAPI**: @Operation(summary = "Create new X")

   ### GET /api/v1/[resource]/{id}
   - **Authorization**: @RolesAllowed(USER_ROLE)
   - **Session/Transaction**: @WithSession
   - **Response**: `XResponse` (200 OK)
   - **Errors**: 401, 403, 404
   - **OpenAPI**: @Operation(summary = "Get X by ID")

   [... all endpoints ...]

   ## Data Model

   ### Entity: X
   ```kotlin
   @Entity
   @Table(name = "x_table")
   class X : PanacheEntityBase() {
       @Id
       @Column(name = "id")
       var id: UUID = UUID.randomUUID()
       // ... fields ...
   }
   ```

   ### DTOs (lemline-common)
   ```kotlin
   data class CreateXRequest(...)
   data class XResponse(...)
   ```

   ## Database Migration

   **V[N]__[description].sql**
   ```sql
   CREATE TABLE x_table (...);
   CREATE INDEX idx_x_field ON x_table(field);
   ```

   **Rollback:**
   ```sql
   DROP TABLE IF EXISTS x_table;
   ```

   ## Implementation Files

    - `lemline-common/src/main/kotlin/com/lemline/common/dto/XDto.kt`
    - `lemline-backend/src/main/kotlin/com/lemline/domain/X.kt`
    - `lemline-backend/src/main/kotlin/com/lemline/repository/XRepository.kt`
    - `lemline-backend/src/main/kotlin/com/lemline/mapper/XMapper.kt`
    - `lemline-backend/src/main/kotlin/com/lemline/resource/XResource.kt`
    - `lemline-backend/src/test/kotlin/com/lemline/XResourceTest.kt`
    - `lemline-backend/src/test/kotlin/com/lemline/XAuthorizationTest.kt`
    - `lemline-backend/src/main/resources/db/migration/V[N]__[description].sql`

   ## Metrics

   Document in `/docs/backend/monitoring/metrics-[domain].md`:
    - `x.created` - Counter for X creation attempts
    - `x.created.success` - Counter for successful creations
    - [... other metrics ...]

   ## Key Implementation Details
    - Any business logic decisions
    - Validation rules
    - Edge cases to handle
    - Security considerations

   ## Testing Checklist
   Reference `/docs/backend/reactive/guide-http-best-practices.md` and `/docs/backend/testing/guide-endpoints.md` for complete checklist:
    - [ ] Authorization tests (401, 403, success for each role)
    - [ ] Input validation tests (400 for invalid data)
    - [ ] Happy path tests (200/201/204)
    - [ ] Error cases (404, 409)

   ## Open Questions
    - [Only if genuinely ambiguous]
   ```

5. **Present the specification** to the user with:
    - Summary of the proposed solution
    - Key decisions made (and why)
    - Any questions (only if truly needed)
    - Ask: "Should I proceed with implementation?"

## Best Practice Defaults

**When in doubt:**

- Require authentication (USER_ROLE minimum)
- Use @WithSession for reads (GET), @WithTransaction for writes (POST/PUT/PATCH/DELETE)
- Add pagination for list endpoints (limit/offset query params)
- Include standard timestamps (created_at, updated_at)
- Use optimistic locking for updates (version field)
- Add indexes for foreign keys and frequently queried fields
- Return 404 for not found, 409 for conflicts, 400 for validation errors
- Add business metrics and document in /docs/backend/monitoring/metrics-[domain].md
- Include OpenAPI descriptions (@Operation, @APIResponse)
- Test both happy path and error cases (see /docs/backend/reactive/guide-http-best-practices.md and /docs/backend/testing/guide-endpoints.md)
- Follow existing naming conventions in the codebase

**Only ask questions when:**

- Business logic is fundamentally ambiguous
- Multiple architectural approaches have significant tradeoffs
- The request conflicts with existing patterns
- Security model is unclear
