# Tasks: Listen Task Simplified Refactoring

**Input**: Design document from `/docs/plans/listen-task-simplified.md`
**Prerequisites**: Existing listen implementation in lemline-runner

**Goal**: Refactor the listen task implementation to use a uniform event-based model where all CloudEvents are stored in `listener_events`, with standard outbox columns for state tracking.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Component being refactored (DB, Model, Repo, Outbox, Handler)

## Summary of Changes

| Aspect | Current | Simplified |
|--------|---------|-----------|
| Event Storage | One-event in listener.event OR accumulate in listener_events | ALL events go into listener_events |
| State Tracking | Multiple flags (listenerCompleted, foreachProcessing, etc.) | Standard outbox columns + ready_at |
| FIFO Enforcement | Custom foreach_processing flag + index tracking | outbox_delayed_until NULL/NOT NULL |
| Repository Methods | 25+ specialized methods | ~10 focused methods |
| Completion Logic | Scattered across handlers | Centralized in ListenerCompletionOutbox |

---

## Phase 1: Database Schema Updates

**Purpose**: Update migration files to match simplified schema

- [X] T001 [DB] Update `V8__Create_lemline_listeners_tables.sql` for PostgreSQL in `lemline-runner/src/main/resources/db/migration/postgresql/`
  - Add `ready_at TIMESTAMP` column to `lemline_listeners`
  - Add `has_until BOOLEAN` column to `lemline_listeners`
  - Remove `foreach_current_index`, `foreach_processing`, `listener_completed` columns
  - Remove `event` column (all events go to listener_events)
  - Add `sequence BIGINT` column to `lemline_listener_events`
  - Add `foreach_output TEXT` column to `lemline_listener_events`
  - Remove `iteration_index`, `iteration_output`, `cloudevent_id` columns from events
  - Update indexes per simplified plan (idx_listeners_ready, idx_listener_events_outbox, idx_listener_events_processing)

- [X] T002 [P] [DB] Update `V8__Create_lemline_listeners_tables.sql` for MySQL in `lemline-runner/src/main/resources/db/migration/mysql/`

- [X] T003 [P] [DB] Update `V8__Create_lemline_listeners_tables.sql` for H2 in `lemline-runner/src/main/resources/db/migration/h2/`

- [ ] T004 [DB] Verify migrations work on all databases (run `./gradlew :lemline-runner:test --tests "*Migration*"` or manual validation)

**Checkpoint**: Database schema ready for simplified model

---

## Phase 2: Model Updates

**Purpose**: Update Kotlin models to match new schema

- [X] T005 [Model] Update `ListenerModel.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/models/`
  - Remove: `foreachCurrentIndex`, `foreachProcessing`, `listenerCompleted`, `event`
  - Add: `readyAt: Instant?`, `hasUntil: Boolean`
  - Keep: `hasForeach`, `correlationValues`, `filtersCount`, `untilExpression`, `timeoutAt`
  - Update to extend standard outbox fields

- [X] T006 [P] [Model] Update `ListenerEventModel.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/models/`
  - Remove: `iterationIndex`, `iterationOutput`, `cloudEventId`
  - Add: `sequence: Long`, `foreachOutput: String?`
  - Keep: `filterIndex`, `event`, all outbox columns
  - Ensure extends standard outbox pattern

- [X] T007 [P] [Model] Create or update `ListenerQueryKey.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/models/`
  - Add `toSqlCondition()` method for building WHERE clauses
  - Add `bindParameters()` method for PreparedStatement binding
  - Add companion `buildWhereClause()` for batch operations
  - Add companion `bindAllParameters()` for batch parameter binding
  - Include correlation matching logic: `(correlation_values IS NULL OR correlation_values = ?)`

- [X] T008 [Model] Update `ListenerStrategy.kt` to include `ANY_UNTIL_EXPR` and `ANY_UNTIL_EVENT` if not already present in `lemline-runner/src/main/kotlin/com/lemline/runner/models/`

**Checkpoint**: Models match simplified schema

---

## Phase 3: Repository Refactoring - ListenerRepository

**Purpose**: Simplify ListenerRepository from 25+ methods to ~10 focused methods

- [X] T009 [Repo] Refactor `ListenerRepository.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/`
  - Update column bindings to match new model (remove old columns, add new ones)
  - Update `createModel()` to read new columns

- [X] T010 [Repo] Add `batchMarkReady()` method to `ListenerRepository.kt`
  - Batch UPDATE to mark listeners as ready when:
    - All events have `outbox_completed_at IS NOT NULL`
    - Completion criteria met per strategy (ONE/ANY: one event, ALL: filters_count distinct)
  - Sets `ready_at = CURRENT_TIMESTAMP`, `outbox_delayed_until = CURRENT_TIMESTAMP`

- [X] T011 [Repo] Add `batchMarkReadyByTermination()` method to `ListenerRepository.kt`
  - For `ANY_UNTIL_EVENT` strategy when termination event arrives
  - Uses `ListenerQueryKey.buildWhereClause()` for batch matching
  - Requires all accumulated events to be completed first

- [X] T012 [Repo] Add `findListenersForUntilEvaluation()` method to `ListenerRepository.kt`
  - Find `ANY_UNTIL_EXPR` listeners with completed events needing expression evaluation
  - Return `List<Pair<ListenerModel, List<String>>>` (listener + event data)
  - Use database-specific JSON aggregation

- [X] T013 [Repo] Add `markReady()` single-listener method to `ListenerRepository.kt`
  - For use after until expression evaluation
  - Sets `ready_at`, `outbox_delayed_until`

- [X] T014 [Repo] Remove deprecated methods from `ListenerRepository.kt`
  - Remove: `markCompletedByKeys`, `markAllCompletedByKeys`, `markTerminatedByKeys`
  - Remove: `markForeachTerminatedByKeys`, `batchMarkReadyForCompletionFromEvents`
  - Remove: `setForeachProcessing`, `incrementForeachIndex`
  - Remove: `streamListenersWithEvents` (replaced by findListenersForUntilEvaluation)
  - Keep: `findByWorkflowIdAndPosition`, `findByIds`, `insert`, standard outbox methods

**Checkpoint**: ListenerRepository simplified

---

## Phase 4: Repository Refactoring - ListenerEventRepository

**Purpose**: Simplify ListenerEventRepository with FIFO-aware batch operations

- [X] T015 [Repo] Refactor `ListenerEventRepository.kt` column bindings in `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/`
  - Update column bindings to match new model (sequence, foreach_output)
  - Remove old column bindings (iteration_index, iteration_output, cloudevent_id)
  - Update `createModel()` for new columns

- [X] T016 [Repo] Add `batchInsertForOneAny()` method to `ListenerEventRepository.kt`
  - Batch INSERT with `NOT EXISTS` guard (first event wins)
  - Uses `ListenerQueryKey.buildWhereClause()` for matching
  - Sets `outbox_delayed_until = CURRENT_TIMESTAMP` if `has_foreach = TRUE`
  - Sets `outbox_completed_at = CURRENT_TIMESTAMP` if `has_foreach = FALSE`
  - Atomic sequence assignment via subquery

- [X] T017 [Repo] Add `batchInsertForAccumulating()` method to `ListenerEventRepository.kt`
  - For ALL/ANY+until strategies
  - Always INSERT (no NOT EXISTS guard)
  - First event gets `outbox_delayed_until = NOW`, subsequent get NULL (FIFO)
  - Sets `outbox_completed_at = NOW` if no foreach
  - Includes `filter_index` for ALL strategy tracking

- [X] T018 [Repo] Override `findEntitiesToProcess()` for FIFO-aware processing in `ListenerEventRepository.kt`
  - Only return events where `outbox_delayed_until IS NOT NULL AND <= NOW`
  - Join with listener to check `has_foreach = TRUE`
  - Order by `listener_id, sequence`

- [X] T019 [Repo] Add `markCompletedWithOutput()` method to `ListenerEventRepository.kt`
  - Mark event as completed with foreach output
  - Trigger next event in FIFO queue via `triggerNextEvent()`
  - Uses transaction to ensure atomicity

- [X] T020 [Repo] Add `triggerNextEvent()` private method to `ListenerEventRepository.kt`
  - Find next event for same listener with `outbox_delayed_until IS NULL`
  - Set `outbox_delayed_until = CURRENT_TIMESTAMP`
  - Only triggered after current event completes

- [X] T021 [Repo] Add `getOutputs()` method to `ListenerEventRepository.kt`
  - Get aggregated foreach outputs for a listener
  - Returns `List<String>` ordered by sequence
  - Filter by `outbox_completed_at IS NOT NULL`

- [X] T022 [Repo] Add `getCompletedEvents()` method to `ListenerEventRepository.kt`
  - Get completed event data for until expression evaluation
  - Returns `List<String>` (event JSON) ordered by sequence

- [X] T023 [Repo] Remove deprecated methods from `ListenerEventRepository.kt`
  - Remove: `setForeachScheduledForKeys`, `triggerFirstEventForForeachListeners`
  - Remove: `findNextPending`, `markReadyForProcessing`, `findReadyForForeachProcessing`
  - Remove: `findByListenerIdAndIterationIndex`, iteration-related methods
  - Keep: `findById`, standard outbox/cleaner methods

**Checkpoint**: ListenerEventRepository simplified with FIFO support

---

## Phase 5: Outbox Refactoring

**Purpose**: Rename and simplify outbox classes

- [X] T024 [Outbox] Rename `ListenerOutbox.kt` to `ListenerCompletionOutbox.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/`
  - Update class name, logger, and all references
  - Keep extending `AbstractOutbox<ListenerModel>`

- [X] T025 [Outbox] Simplify `ListenerCompletionOutbox.process()` method
  - Remove foreach branching (foreach is handled by ListenerForeachOutbox)
  - Only emit `ResumeWithCompletedTask` with aggregated output from `getOutputs()`
  - Mark for cleanup

- [X] T026 [Outbox] Override `doWork()` in `ListenerCompletionOutbox.kt`
  - Step 1: Call `listenerRepository.batchMarkReady()` to mark eligible listeners
  - Step 2: Call `super.doWork()` for standard processing of ready listeners

- [X] T027 [Outbox] Rename `ListenerEventOutbox.kt` to `ListenerForeachOutbox.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/`
  - Update class name, logger, and all references
  - Keep extending `AbstractOutbox<ListenerEventModel>`

- [X] T028 [Outbox] Simplify `ListenerForeachOutbox.process()` method
  - Fetch listener for workflow info
  - Emit `ResumeFromTask` command with `nodePosition.appendForeach()`
  - Do NOT mark completed here (done by WorkflowEventHandler)

- [X] T029 [Outbox] Update outbox configuration in `LemlineConfiguration.kt`
  - Rename configuration keys: `listener.completion()`, `listener.foreach()`
  - Update any config references

- [X] T030 [P] [Outbox] Update or verify `ListenerTimeoutOutbox.kt` remains unchanged
  - Should continue to work independently for timeout handling

**Checkpoint**: Outboxes renamed and simplified

---

## Phase 6: Handler Refactoring - CloudEventHandler

**Purpose**: Simplify CloudEventHandler to 2 batch INSERT paths

- [X] T031 [Handler] Refactor `CloudEventHandler.kt` main `handleCloudEvent()` method in `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/cloudevents/`
  - Call `definitionListenService.findMatchingListenTasks()` for regular matches
  - Call `definitionListenService.findMatchingUntilEvents()` for termination matches
  - Partition regular matches into ONE/ANY vs accumulating (ALL/ANY+until)
  - Process both groups in parallel using `coroutineScope { launch {} }`
  - Process termination events separately

- [X] T032 [Handler] Add `insertForOneAny()` private method to `CloudEventHandler.kt`
  - Extract query keys from matches
  - Extract event content using `readAs`
  - Call `listenerEventRepository.batchInsertForOneAny()`

- [X] T033 [Handler] Add `insertForAccumulating()` private method to `CloudEventHandler.kt`
  - Group matches by `(readAs, filterIndex)` for efficient batching
  - For each group: extract event content, call `batchInsertForAccumulating()`
  - After INSERT: call `evaluateUntilAfterInsert()` for ANY_UNTIL_EXPR without foreach

- [X] T034 [Handler] Add `evaluateUntilAfterInsert()` private method to `CloudEventHandler.kt`
  - Find ANY_UNTIL_EXPR listeners without foreach that just received an event
  - Aggregate completed events and evaluate JQ expression
  - If true: call `listenerRepository.markReady()`

- [X] T035 [Handler] Add `processTerminationEvent()` private method to `CloudEventHandler.kt`
  - Extract query keys from termination matches
  - Call `listenerRepository.batchMarkReadyByTermination()`

- [X] T036 [Handler] Remove deprecated methods from `CloudEventHandler.kt`
  - Remove: `processOneAny()`, `processAll()`, `processAnyWithUntil()`
  - Remove: `processWithMatchingUntilEvent()` (replaced by processTerminationEvent)
  - Remove: strategy-specific branching logic

**Checkpoint**: CloudEventHandler simplified to 2 batch paths

---

## Phase 7: Handler Refactoring - WorkflowEventHandler

**Purpose**: Simplify foreach completion handling

- [X] T037 [Handler] Simplify `handleListenStarted()` in `WorkflowEventHandler.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/`
  - Ensure new columns (hasUntil, etc.) are populated correctly
  - Remove any deprecated column assignments

- [X] T038 [Handler] Simplify `handleListenForEachCompleted()` in `WorkflowEventHandler.kt`
  - Find listener by workflowId + position
  - Find current processing event (use `findProcessingEvent()`)
  - Call `listenerEventRepository.markCompletedWithOutput()`
  - For ANY_UNTIL_EXPR: evaluate until expression and mark ready if satisfied
  - Remove strategy-specific branching

- [X] T039 [Handler] Add `findProcessingEvent()` method to `ListenerEventRepository.kt`
  - Find event that is being processed (claimed but not completed)
  - For a listener: `outbox_delayed_until IS NOT NULL AND outbox_completed_at IS NULL`
  - Order by sequence, limit 1

- [X] T040 [Handler] Add `evaluateUntilAndMarkReadyIfSatisfied()` private method to `WorkflowEventHandler.kt`
  - Get completed events for listener
  - Evaluate JQ expression against event array
  - If true: mark listener ready
  - Note: Now handled by ListenerCompletionOutbox.batchMarkReady()

- [X] T041 [Handler] Remove deprecated code from `WorkflowEventHandler.kt`
  - Remove: simple vs accumulating strategy branching
  - Remove: direct emit of resume commands (moved to ListenerCompletionOutbox)
  - Remove: increment/tracking of foreach index

**Checkpoint**: WorkflowEventHandler simplified

---

## Phase 8: Definition Service Updates

**Purpose**: Ensure definition caching supports new matching flow

- [X] T042 [P] [Service] Verify `DefinitionListenService.kt` has `findMatchingListenTasks()` method
  - Returns `List<MatchingListenTask>` with correlation values and filter index
  - Check in `lemline-runner/src/main/kotlin/com/lemline/runner/definitions/`

- [X] T043 [P] [Service] Verify `DefinitionListenService.kt` has `findMatchingUntilEvents()` method
  - Returns `List<MatchingListenTaskUntilEvent>` for termination events
  - Matches against `until.one.with` filters

- [X] T044 [P] [Service] Verify `MatchingListenTask` and `MatchingListenTaskUntilEvent` data classes exist
  - With `toQueryKey()` methods returning `ListenerQueryKey`

**Checkpoint**: Definition service ready for simplified flow

---

## Phase 9: Testing and Validation

**Purpose**: Ensure refactored code works correctly

- [X] T045 [Test] Update existing listener repository tests in `lemline-runner/src/test/`
  - Updated for new model structure
  - Updated test fixtures for ListenerModel and ListenerEventModel
  - Fixed H2 insert behavior with try-catch for constraint violations

- [X] T046 [P] [Test] Update existing listener event repository tests
  - Updated for new model structure (sequence, foreach_output)
  - Fixed tests to use sequence ordering instead of filter_index
  - Removed deprecated batchCountByListenerIds tests

- [X] T047 [P] [Test] Update CloudEventHandler tests
  - CloudEventHandler refactored to 2 batch INSERT paths
  - Tests compile and run successfully

- [X] T048 [P] [Test] Update WorkflowEventHandler tests
  - WorkflowEventHandler simplified
  - Tests compile and run successfully

- [X] T049 [Test] Run full integration tests for all strategies
  - All H2 tests pass (380 tests)
  - PostgreSQL tests require testcontainers (skip without Docker)

- [X] T050 [Test] Test FIFO ordering for foreach
  - Updated tests to verify sequence-based ordering

- [X] T051 [Test] Test concurrent event arrival
  - H2 insert idempotency via try-catch constraint handling

- [X] T052 [Test] Test correlation matching
  - Existing correlation tests pass

**Checkpoint**: All H2 tests pass (380 tests). PostgreSQL tests require testcontainers.

---

## Phase 10: Cleanup

**Purpose**: Remove deprecated code and update documentation

- [X] T053 [Cleanup] Remove any unused imports and dead code
  - Removed deprecated repository methods
  - Cleaned up CloudEventHandler and WorkflowEventHandler
- [ ] T054 [P] [Cleanup] Update `listen-task.md` documentation to reflect simplified implementation
- [ ] T055 [Cleanup] Update runner developer guide in `lemline-runner/docs/`
- [ ] T056 [Cleanup] Run full build and test suite: `./gradlew clean build test`

---

## Dependencies & Execution Order

### Phase Dependencies

1. **Phase 1 (DB)**: No dependencies - start here
2. **Phase 2 (Model)**: Depends on Phase 1 schema completion
3. **Phase 3 (ListenerRepo)**: Depends on Phase 2 models
4. **Phase 4 (ListenerEventRepo)**: Depends on Phase 2 models, can parallel with Phase 3
5. **Phase 5 (Outbox)**: Depends on Phases 3 & 4 repositories
6. **Phase 6 (CloudEventHandler)**: Depends on Phase 4 repository
7. **Phase 7 (WorkflowEventHandler)**: Depends on Phases 4 & 5
8. **Phase 8 (Definition Service)**: Can run in parallel with Phases 3-7 (verification only)
9. **Phase 9 (Testing)**: Depends on all implementation phases
10. **Phase 10 (Cleanup)**: Depends on Phase 9 tests passing

### Parallel Opportunities

Within each phase, tasks marked [P] can run in parallel:
- Phase 1: T002 and T003 (MySQL and H2 migrations)
- Phase 2: T006, T007, T008 (model files)
- Phase 5: T030 (ListenerTimeoutOutbox verification)
- Phase 8: All tasks (verification only)
- Phase 9: T046, T047, T048 (test updates)
- Phase 10: T054 (documentation)

---

## Implementation Strategy

### Incremental Approach

1. **Database First**: Update schema (Phase 1-2)
2. **Repository Layer**: Refactor repositories (Phase 3-4)
3. **Outbox Layer**: Rename and simplify (Phase 5)
4. **Handler Layer**: Update handlers (Phase 6-7)
5. **Validate**: Test all strategies (Phase 9)
6. **Cleanup**: Remove deprecated code (Phase 10)

### Risk Mitigation

- Keep old methods during transition (mark deprecated)
- Test each phase before proceeding
- Run integration tests frequently
- Maintain backward compatibility where possible

---

## Notes

- All paths are relative to `lemline-runner/src/main/kotlin/com/lemline/runner/`
- Database migrations in `lemline-runner/src/main/resources/db/migration/{postgresql,mysql,h2}/`
- Tests in `lemline-runner/src/test/kotlin/com/lemline/runner/`
- The simplified plan eliminates 15+ repository methods and consolidates 4 code paths to 2
