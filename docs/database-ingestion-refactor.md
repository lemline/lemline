Refactoring Plan: Simplify Database Message Architecture

Overview

Consolidate database persistence logic by replacing multiple message types (IngestionMessage, CompletedMessage) with a
single sealed class DatabaseMessage, and moving all persistence decisions to DatabaseMessageHandler.

Goals

1. Simplify message schema (single DatabaseMessage type)
2. Clear separation: InstanceMessageHandler = routing, DatabaseMessageHandler = persistence
3. Non-blocking workflow channel (resilient to DB failures)
4. Type-safe exhaustive pattern matching

Pre-Implementation

1. Create Feature Branch

git checkout -b refactor/simplify-database-messages

2. Run Full Test Suite (Baseline)

./gradlew :lemline-runner:test
Record baseline: Currently 536/539 tests passing

3. Document Current Behavior

Create test assertions for critical flows:

- Wait task persistence
- Retry task persistence
- Child workflow creation
- Parent completion
- Schedule completion
- Infrastructure failures

Phase 1: Create New Types (Additive)

Step 1.1: Create DatabaseMessage Sealed Class

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessage.kt

// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.runner.messaging.commands.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**

* Messages sent to the database channel for persistence.
*
* This sealed class represents all types of messages that require database operations,
* keeping the workflow channel non-blocking even when the database is unavailable.
  */
  @ExperimentalTime
  @ExperimentalSerializationApi
  @Serializable
  sealed class DatabaseMessage {

  /**
    * Regular workflow state requiring persistence.
    *
    * Sent when a workflow reaches a pause point (Waiting, Retrying, RunningChildWorkflow)
    * or terminal state (Completed, Failed) that requires database persistence.
      */
      @Serializable
      @SerialName("workflow_persistence")
      data class WorkflowPersistence(
      val instance: InstanceMessage
      ) : DatabaseMessage()

  /**
    * Infrastructure failure with workflow context.
    *
    * Sent when runner infrastructure fails (DB access, definition retrieval, etc.)
    * but we still have the InstanceMessage for context.
    *
    * @property retryable true = save to RetryOutbox, false = save to FailureModel
      */
      @Serializable
      @SerialName("infrastructure_failure")
      data class InfrastructureFailure(
      val instance: InstanceMessage,
      val errorClass: String,
      val errorMessage: String?,
      val errorStackTrace: String,
      val reason: String,
      val retryable: Boolean
      ) : DatabaseMessage()

  /**
    * Message deserialization failure.
    *
    * Sent when we cannot parse the incoming message into an InstanceMessage.
    * Only contains the raw payload and error information.
      */
      @Serializable
      @SerialName("deserialization_failure")
      data class DeserializationFailure(
      val payload: String,
      val errorClass: String,
      val errorMessage: String?,
      val errorStackTrace: String
      ) : DatabaseMessage()

  companion object {
  fun fromJsonString(jsonString: String): DatabaseMessage =
  com.lemline.common.json.LemlineJson.decodeFromString(jsonString)
  }

  fun toJsonString(): String =
  com.lemline.common.json.LemlineJson.encodeToString(this)
  }

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Step 1.2: Add Helper Extension Functions

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageExtensions.kt

// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.runner.messaging.commands.InstanceMessage

/**

* Creates an InfrastructureFailure message from an exception.
  */
  fun InstanceMessage.toInfrastructureFailure(
  error: Throwable,
  reason: String,
  retryable: Boolean
  ): DatabaseMessage.InfrastructureFailure = DatabaseMessage.InfrastructureFailure(
  instance = this,
  errorClass = error::class.qualifiedName ?: "Unknown",
  errorMessage = error.message,
  errorStackTrace = error.stackTraceToString(),
  reason = reason,
  retryable = retryable
  )

/**

* Creates a DeserializationFailure message from an exception.
  */
  fun createDeserializationFailure(
  payload: String,
  error: Throwable
  ): DatabaseMessage.DeserializationFailure = DatabaseMessage.DeserializationFailure(
  payload = payload,
  errorClass = error::class.qualifiedName ?: "Unknown",
  errorMessage = error.message,
  errorStackTrace = error.stackTraceToString()
  )

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Phase 2: Update DatabaseMessageHandler (Parallel Implementation)

Step 2.1: Add New Handler Method

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageHandler.kt

Add new method alongside existing handle():

/**

* Handles the new DatabaseMessage sealed class.
* This will replace the existing handle() method.
  */
  suspend fun handleNew(message: DatabaseMessage) {
  retry(
  label = "${message::class.simpleName}",
  maxAttempts = maxAttempts,
  totalBudgetMs = totalBudgetMs,
  singleAttemptTimeoutMs = singleAttemptTimeoutMs
  ) {
  when (message) {
  is DatabaseMessage.WorkflowPersistence -> {
  handleWorkflowPersistence(message.instance)
  }

           is DatabaseMessage.InfrastructureFailure -> {
               handleInfrastructureFailure(message)
           }

           is DatabaseMessage.DeserializationFailure -> {
               handleDeserializationFailure(message)
           }
       }
  }
  }

private suspend fun handleWorkflowPersistence(instance: InstanceMessage) {
when (val state = instance.workflowState) {
is WorkflowState.Waiting -> {
waitRepository.insert(WaitOutboxModel(
id = IDV7.random(),
instanceMessage = instance,
outboxScheduledFor = state.waitUntil
))
}

          is WorkflowState.Retrying -> {
              retryRepository.insert(RetryOutboxModel.from(
                  id = IDV7.random(),
                  instance = instance,
                  outboxScheduledFor = state.retryAt,
                  error = IllegalStateException("Task failed and will be retried"),
                  reason = "Task retry"
              ))
          }

          is WorkflowState.RunningChildWorkflow -> {
              handleRunningChildWorkflow(instance, state)
          }

          is WorkflowState.Completed -> {
              handleCompletion(instance, state)
          }

          is WorkflowState.Failed -> {
              val exception = InternalWorkflowException(state.error)
              failureRepository.insert(FailureModel.from(
                  id = IDV7.random(),
                  instance = instance,
                  error = exception,
                  reason = getFailureReason(exception)
              ))
          }

          is WorkflowState.ReadyForNextTask,
          is WorkflowState.Starting -> {
              error("Unexpected state in database handler: $state")
          }
      }

}

private suspend fun handleRunningChildWorkflow(
instance: InstanceMessage,
state: WorkflowState.RunningChildWorkflow
) {
failureRepository.withTransaction { conn ->
// Insert parent
val parentId = IDV7.random()
parentRepository.insert(
ParentOutboxModel(
id = parentId,
instanceMessage = instance,
outboxScheduledFor = null
),
conn
)

          // Create child + optional schedule
          val (child, schedule) = starter.getStartingMessages(
              workflowId = WorkflowId.random(),
              workflowNamespace = state.childConfig.namespace,
              workflowName = state.childConfig.name,
              optionalVersion = state.childConfig.version,
              workflowInput = state.childConfig.input,
              parentId = parentId,
              zoneId = null
          ) { error(it) }

          // Insert schedule if present
          schedule?.let { scheduleRepository.insert(it, conn) }

          // Emit child to workflow channel
          child?.let { instanceEmitter.send(it) }
      }

}

private suspend fun handleCompletion(
instance: InstanceMessage,
state: WorkflowState.Completed
) {
// Handle parent completion
instance.parentId?.let { parentId ->
parentRepository.findById(parentId)?.let { parent ->
// Validate parent state
val currentState = parent.instanceMessage.workflowState
if (currentState !is WorkflowState.RunningChildWorkflow) {
error("CRITICAL - Parent workflow ${parent.workflowId} is in unexpected state $currentState")
}

              // Update parent with child output
              val updatedParent = parent.copy(
                  instanceMessage = parent.instanceMessage.copy(
                      workflowState = currentState.copy(rawOutput = state.output)
                  ),
                  outBoxStatus = OutBoxStatus.SENT,
                  outboxScheduledFor = Clock.System.now()
              )

              // Send parent to workflow channel
              instanceEmitter.send(updatedParent.instanceMessage)
              parentRepository.update(updatedParent)

              logger.debug {
                  "Parent workflow ${updatedParent.workflowId} resumed after child completion"
              }
          } ?: error("CRITICAL - Unable to find parent $parentId")
      }

      // Handle schedule completion
      // TODO: Determine isScheduledAfter from workflow definition
      // For now, check if workflow exists in schedule table
      scheduleRepository.findByWorkflowId(instance.workflowId)?.let { schedule ->
          schedule.scheduleAfterCompletion()
          scheduleRepository.update(schedule)
          logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.outboxScheduledFor}" }
      }

}

private suspend fun handleInfrastructureFailure(message: DatabaseMessage.InfrastructureFailure) {
if (message.retryable) {
// Save to retry outbox - will be retried later
retryRepository.insert(RetryOutboxModel.from(
id = IDV7.random(),
instance = message.instance,
outboxScheduledFor = Clock.System.now(), // TODO: Calculate backoff
error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
reason = message.reason
))
} else {
// Save to failure table - permanent error
failureRepository.insert(FailureModel.from(
id = IDV7.random(),
instance = message.instance,
error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
reason = message.reason
))
}
}

private suspend fun handleDeserializationFailure(message: DatabaseMessage.DeserializationFailure) {
failureRepository.insert(FailureModel.from(
id = IDV7.random(),
payload = message.payload,
error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
reason = DESERIALIZATION_FAILURE
))
}

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Step 2.2: Update Emitter to Support Both Types (Temporary)

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageEmitter.kt

Add overload:

suspend fun send(message: DatabaseMessage) {
val json = message.toJsonString()
channel.send(json)
logger.debug { "Sent DatabaseMessage to $DATABASE_OUT_CHANNEL: ${message::class.simpleName}" }
}

// Keep existing send methods for backward compatibility during migration

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Phase 3: Update InstanceMessageHandler (Incremental)

Step 3.1: Update handleWorkflowState Method

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/instances/InstanceMessageHandler.kt

Replace the current handleWorkflowState method:

/**

* Handles the different WorkflowState outcomes by pattern matching.
*
* @return InstanceMessage to emit for next step, or null if paused/terminal
  */
  private suspend fun InstanceMessage.handleWorkflowState(nextState: WorkflowState): InstanceMessage? {
  return when (nextState) {
  is WorkflowState.ReadyForNextTask -> {
  // Activity completed - continue execution
  logger.debug { "Activity completed at ${nextState.nodePosition}" }
  copy(workflowState = nextState)
  }

       is WorkflowState.Waiting -> {
           // Check if wait time already reached (optimization)
           if (nextState.waitUntil <= Clock.System.now()) {
               logger.debug { "Wait time already reached, continuing immediately" }
               copy(workflowState = nextState)
           } else {
               // Send to database for persistence
               logger.debug { "Starting wait task, resuming at ${nextState.waitUntil}" }
               databaseEmitter.send(DatabaseMessage.WorkflowPersistence(
                   instance = copy(workflowState = nextState)
               ))
               null  // Paused
           }
       }

       is WorkflowState.Retrying -> {
           // Check if retry time already reached (optimization)
           if (nextState.retryAt <= Clock.System.now()) {
               logger.debug { "Retry time reached, retrying immediately" }
               copy(workflowState = nextState)
           } else {
               // Send to database for persistence
               logger.debug { "Scheduling retry, retrying at ${nextState.retryAt}" }
               databaseEmitter.send(DatabaseMessage.WorkflowPersistence(
                   instance = copy(workflowState = nextState)
               ))
               null  // Paused
           }
       }

       is WorkflowState.RunningChildWorkflow -> {
           // Send to database for parent storage + child creation
           logger.debug { "Starting child workflow at ${nextState.nodePosition}" }
           databaseEmitter.send(DatabaseMessage.WorkflowPersistence(
               instance = copy(workflowState = nextState)
           ))
           null  // Paused
       }

       is WorkflowState.Completed -> {
           // Only persist if parent or scheduled workflow
           logger.debug { "Workflow completed with output: ${nextState.output}" }

           // TODO: Determine isScheduledAfter from workflow definition
           val needsPersistence = parentId != null // || isScheduledAfter

           if (needsPersistence) {
               databaseEmitter.send(DatabaseMessage.WorkflowPersistence(
                   instance = copy(workflowState = nextState)
               ))
           }
           null  // Terminal
       }

       is WorkflowState.Failed -> {
           // Send to database for failure persistence
           logger.error { "Workflow failed at ${nextState.nodePosition}: ${nextState.error}" }
           databaseEmitter.send(DatabaseMessage.WorkflowPersistence(
               instance = copy(workflowState = nextState)
           ))
           null  // Terminal
       }

       is WorkflowState.Starting -> {
           // This shouldn't happen during resume - treat as infrastructure failure
           logger.error { "Unexpected Starting state when resuming workflow" }
           databaseEmitter.send(
               toInfrastructureFailure(
                   error = IllegalStateException("Received Starting state during resume"),
                   reason = ILLEGAL_STATE_FAILURE,
                   retryable = false
               )
           )
           null  // Terminal
       }
  }
  }

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Step 3.2: Update Error Handling - Deserialization

Replace the deserialize method:

/**

* Deserializes the message payload. Returns the InstanceMessage
*
* This function is designed to throw only CompensationException with additional actions
  */
  override suspend fun Message<String>.deserialize(): InstanceMessage = try {
  InstanceMessage.fromMessage(this)
  } catch (e: Exception) {
  logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }

  // Send deserialization failure to database channel
  databaseEmitter.send(createDeserializationFailure(
  payload = payload,
  error = e
  ))

  throw CompensationException(DESERIALIZATION_FAILURE)
  }

Remove the old deserializationFailed method (no longer needed)

Step 3.3: Update Error Handling - Definition Retrieval

Replace the findWorkflowDefinition method:

/**

* Retrieves a workflow definition based on the provided name and version.
*
* This method first attempts to fetch the workflow from a cache. If not found,
* it retrieves the definition from a repository, parses it, and stores it in the cache.
*
* If the workflow is still not found, the message is saved for manual inspection.
  */
  private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow = try {
  // Try to get from cache first, then from repository if not found
  DefinitionCache.getWorkflow(
  namespace = workflowNamespace,
  name = workflowName,
  version = workflowVersion
  ) ?: definitionRepository.findByNameAndVersion(
  workflowNamespace,
  workflowName,
  workflowVersion
  )
  ?.definition
  ?.let { DefinitionCache.parseAndPut(it) }
  } catch (e: Exception) {
  logger.error(e) { "Error during workflow definition retrieval" }

  // Send infrastructure failure to database channel (retryable - DB might recover)
  databaseEmitter.send(
  toInfrastructureFailure(
  error = e,
  reason = getFailureReason(e),
  retryable = true
  )
  )

  throw CompensationException(getFailureReason(e))
  } ?: run {
  val errorMsg = "Workflow $workflowNamespace:$workflowName:$workflowVersion not found"
  logger.error { "$errorMsg. Storing in failure table for manual inspection." }

  val error = IllegalStateException(errorMsg)

  // Send infrastructure failure to database channel (not retryable - won't exist on retry)
  databaseEmitter.send(
  toInfrastructureFailure(
  error = error,
  reason = DEFINITION_MISSING,
  retryable = false
  )
  )

  throw CompensationException(DEFINITION_MISSING)
  }

Step 3.4: Update Error Handling - Workflow Execution

Replace the executeStep method:

/**

* Executes one step of the workflow using WorkflowOrchestrator.
*
* This method calls the functional WorkflowOrchestrator to execute one activity,
* then pattern matches on the returned WorkflowState to determine the next action.
*
* @return InstanceMessage to emit for next step, or null if paused/terminal
  */
  private suspend fun InstanceMessage.executeStep(workflow: Workflow): InstanceMessage? {
  return try {
  // Execute using WorkflowOrchestrator
  val nextState = WorkflowOrchestrator.resume(
  workflow = workflow,
  state = workflowState,
  executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
  )

       // Handle the outcome
       handleWorkflowState(nextState)
  } catch (e: Exception) {
  logger.error(e) { "Failed to execute workflow step" }

       // Send infrastructure failure to database channel (not retryable - logic error)
       databaseEmitter.send(
           toInfrastructureFailure(
               error = e,
               reason = WORKFLOW_EXECUTION_FAILURE,
               retryable = false
           )
       )

       throw CompensationException(WORKFLOW_EXECUTION_FAILURE)
  }
  }

Step 3.5: Update Error Handling - Serialization & Emission

Replace the emit method:

/**

* Emits the next message in a workflow to the messaging system.
*
* This method serializes the given message into a JSON string and sends it using the emitter.
* If the emission fails, the message is stored in the retry table for reprocessing.
  */
  override suspend fun InstanceMessage.emit() {
  // Serialize the message
  val payload = try {
  this.toJsonString()
  } catch (e: Exception) {
  logger.error(e) { "Failed to serialize message" }

       // Send infrastructure failure to database channel (not retryable - corrupted state)
       databaseEmitter.send(
           toInfrastructureFailure(
               error = e,
               reason = SERIALIZATION_FAILURE,
               retryable = false
           )
       )

       throw CompensationException(SERIALIZATION_FAILURE)
  }

  // Emit the message
  try {
  instanceEmitter.send(payload)
  } catch (e: Exception) {
  logger.warn(e) { "Failed to emit message to broker" }

       // Send infrastructure failure to database channel (retryable - broker might recover)
       databaseEmitter.send(
           toInfrastructureFailure(
               error = e,
               reason = MESSAGE_EMISSION_FAILURE,
               retryable = true
           )
       )

       throw CompensationException(MESSAGE_EMISSION_FAILURE)
  }
  }

Step 3.6: Remove Old Helper Methods

Delete these methods from InstanceMessageHandler:

- emitToRetry()
- emitAsFailed()
- saveAsFailed()
- saveForRetry()

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Phase 4: Update Subscriber to Use New Handler

Step 4.1: Update DatabaseMessageSubscriber

File: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageSubscriber.kt

Update the message consumption to use new handler:

@Incoming(DATABASE_IN_CHANNEL)
@Acknowledgment(Acknowledgment.Strategy.MANUAL)
suspend fun consume(message: Message<String>): CompletionStage<Void> {
return scope.future {
try {
// Deserialize using new DatabaseMessage
val databaseMessage = DatabaseMessage.fromJsonString(message.payload)

              // Use new handler
              databaseMessageHandler.handleNew(databaseMessage)

              message.ack()
              onComplete(message, databaseMessage)
          } catch (e: Exception) {
              logger.error(e) { "Failed to process database message" }
              message.nack(e)
              onFailure(message, e)
          }
      }.toCompletableFuture()

}

Verify: Compilation succeeds
./gradlew :lemline-runner:compileKotlin

Phase 5: Testing

Step 5.1: Run Unit Tests

./gradlew :lemline-runner:test --tests "*InstanceMessageHandlerTest"
./gradlew :lemline-runner:test --tests "*DatabaseMessageHandlerTest"

Fix any failures.

Step 5.2: Run Integration Tests

./gradlew :lemline-runner:test --tests "*WorkflowConsumer*"

Focus on:

- WorkflowConsumerInMemoryTest
- WorkflowConsumerRabbitMQTest
- WorkflowConsumerKafkaTest (may have timing issues, but logic should pass)

Step 5.3: Run Full Test Suite

./gradlew :lemline-runner:test

Target: 536/539 tests passing (same as baseline)

Step 5.4: Manual Testing (if possible)

Test each flow manually:

1. Wait task → creates WaitOutboxModel
2. Retry task → creates RetryOutboxModel
3. Child workflow → creates ParentOutboxModel + child message
4. Workflow completion with parent → updates parent
5. Deserialization failure → creates FailureModel
6. Definition not found → creates FailureModel
7. DB error during definition fetch → creates RetryOutboxModel

Phase 6: Cleanup

Step 6.1: Remove Old Message Types

Delete these files:

- lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/IngestionMessage.kt
- lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/CompletedMessage.kt

Step 6.2: Remove Old Handler Method

File: DatabaseMessageHandler.kt

Remove the old handle(message: DatabaseMessage) method (the one handling IngestionMessage/CompletedMessage).

Rename handleNew() to handle().

Step 6.3: Clean Up Imports

Remove unused imports from:

- InstanceMessageHandler.kt
- DatabaseMessageHandler.kt
- Any test files

Step 6.4: Update README (if exists)

Update lemline-runner/src/main/kotlin/com/lemline/runner/messaging/README.md to reflect new architecture.

Verify: Clean compilation
./gradlew clean :lemline-runner:build

Phase 7: Documentation

Step 7.1: Update Architecture Docs

File: docs/runner-architecture.md

Update sections:

- Database Channel Processing
- DatabaseMessage structure
- Flow diagrams showing DatabaseMessage variants

Step 7.2: Add KDoc Comments

Ensure all new methods have comprehensive KDoc:

- DatabaseMessage sealed class and variants
- handleWorkflowPersistence()
- handleInfrastructureFailure()
- handleDeserializationFailure()

Step 7.3: Update CHANGELOG

Add entry describing the refactoring and benefits.

Phase 8: Commit & Review

Step 8.1: Create Commits

Break into logical commits:

# Commit 1: Add new types

git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessage.kt
git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageExtensions.kt
git commit -m "Add DatabaseMessage sealed class for unified database persistence"

# Commit 2: Update DatabaseMessageHandler

git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageHandler.kt
git commit -m "Refactor DatabaseMessageHandler to handle all persistence logic"

# Commit 3: Update InstanceMessageHandler

git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/instances/InstanceMessageHandler.kt
git commit -m "Simplify InstanceMessageHandler to route messages based on WorkflowState"

# Commit 4: Update subscriber and cleanup

git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/DatabaseMessageSubscriber.kt
git rm lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/IngestionMessage.kt
git rm lemline-runner/src/main/kotlin/com/lemline/runner/messaging/database/CompletedMessage.kt
git commit -m "Remove legacy message types and update subscriber"

# Commit 5: Update documentation

git add docs/runner-architecture.md
git add lemline-runner/src/main/kotlin/com/lemline/runner/messaging/README.md
git commit -m "Update documentation to reflect simplified database message architecture"

Step 8.2: Final Verification

# Clean build

./gradlew clean build

# Run all tests

./gradlew test

# Check for compilation warnings

./gradlew :lemline-runner:compileKotlin --warning-mode all

Step 8.3: Create Pull Request

Create PR with description:

- Why: Simplify database persistence architecture
- What: Consolidated message types into DatabaseMessage sealed class
- Benefits: Clear separation of concerns, easier to extend, non-blocking
- Migration: Replaced IngestionMessage/CompletedMessage with DatabaseMessage
- Tests: All existing tests passing

Rollback Plan

If issues arise during any phase:

Quick Rollback

git checkout main lemline-runner/src/main/kotlin/com/lemline/runner/messaging/
./gradlew clean build

Partial Rollback

If new code compiles but tests fail:

1. Keep new DatabaseMessage types
2. Revert handler changes
3. Debug incrementally

Success Criteria

- ✅ All 536+ tests passing
- ✅ Clean compilation with no warnings
- ✅ All message flows working (wait, retry, child, completion, failures)
- ✅ Code is simpler and easier to understand
- ✅ Documentation updated
- ✅ No regression in functionality

Estimated Effort

- Phase 1-2: 2-3 hours (new types + handler)
- Phase 3: 2-3 hours (update InstanceMessageHandler)
- Phase 4: 30 minutes (subscriber)
- Phase 5: 1-2 hours (testing & fixes)
- Phase 6-7: 1 hour (cleanup & docs)
- Phase 8: 30 minutes (commit & review)

Total: 7-10 hours

Notes

- Work incrementally - commit after each phase
- Run tests frequently
- Keep main branch stable - all work in feature branch
- If stuck, consult the team or create discussion issue
