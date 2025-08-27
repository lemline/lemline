# Lemline Implementation

## Error Handling Architecture

Lemline implements a 3-layer approach for handling error:

- **Infrastructure Error**: Failure during database access, brocker access
- **Lemline Error**: Failures during serialization, deserialisation, and the processing of the workflow definition
- **User Error**: Failure during an activity processing, non handled by the workflow definition 

Note: If a failure is handled by the workflow definition (try/catch), Lemline does not see it as an error but a "normal" processing of the workflow.


Generally speaking, Lemline will read a message and:
1. Deserialize the message
2. Retrieve the workflow definition (usually from local cache, if not present from database)
3. Init the workflow state, and run the workflow logic up to the next activity
4. Calls the activity
5. Update the workflow state and prepare the next message
6. Send the next message and acknowledge the previous message

The broker is supposely a high-availability infrastructure, but we should consider the case when we do not manage to send  or (neg)acknowledge a message

If Lemline encouters an error occurs before 6., it will send a the message to an error topic, with additional info (status, stacktrace).

Another process inside Lemline read those messages and save them to the `lemline_retries` table, with a status `FAILED` if the error is unrecoverable, or `PENDING` if the error could be transient
2. Lemline tries to send this message until it succeeds to do so
3. Once sending this message succeeds, the initial message is acknowledged
4. if an error occurs during acknowledgement, Lemline will retry until it succeeds



save the workflow state into the `lemline_retries` table, with a status `FAILED` if the error is unrecoverable, or `PENDING` if the error could be transient
2. If saving this message fails (eg. database down or network issue), then the message is negatively acknowledged
3. If saving this message succeeds, then the message is acknowledged
4. If Lemline does not manage to acknowledge or (neg-acknowledge) the message, it keeps trying until successfully doing it (in case or broker downtime or network issue)
 
In 1. the saved state of the message will be: 
- the unmodified state of the message if the error occurs before or during the activity processing
- the state updated if the error occurs during after the activity processing

## Error Status Definitions

- **Failed**: Messages that cannot be processed due to permanent issues (malformed data, missing definitions)
- **Pending**: Messages that failed due to transient issues (database connectivity, external service timeouts)

Failed messages should be human reviewed and potentially retried manually.

### Error Handling Strategy

Each step has specific error handling to ensure message reliability and system stability:

#### Deserialization Errors (Step 1)
- **Action**: Save the raw message payload to database as "failed"
- **Message Handling**: Acknowledge the message (remove from queue)
- **Rationale**: Malformed messages cannot be processed and should not block the queue

#### Workflow Definition Errors (Step 2)
- **Action**: If definition not found, throw error and rely on dead letter queue
- **Action**: If database access fails, save message for retry
- **Message Handling**: Negative acknowledgment for database failures, dead letter queue for missing definitions
- **Rationale**: Missing definitions require human intervention, database failures may be transient

#### Workflow Execution Errors (Step 3)
- **Action**: Save the current workflow state to database for "manual introspection"
- **Message Handling**: Acknowledge the message
- **Rationale**: Workflow logic is deterministic - retrying won't change the outcome

#### Activity Processing Errors (Step 4)
- **Action**: Save the message to database marked for "retry"
- **Message Handling**: Acknowledge the message
- **Rationale**: Activity failures may be transient and worth retrying

#### Serialization Errors (Steps 5 & 6)
- **Action**: Save the current state or original message to database
- **Message Handling**: Acknowledge the message
- **Rationale**: Serialization issues indicate data corruption, not transient failures

### Message Acknowledgment Strategy

- **Positive Acknowledgment**: Message is removed from the queue after successful processing or error handling
- **Negative Acknowledgment**: Only used when the error handling itself fails (e.g., database connection issues)

### Crash Recovery Design

To ensure message processing survives application crashes:
- **Deterministic IDs**: All database IDs and message IDs are deterministically generated from the original message ID
- **Benefit**: Prevents duplicate processing and ensures consistent state recovery after restarts
- **Implementation**: Uses hash-based ID generation to maintain consistency across application instances

### Key Principles

1. **No Message Loss**: Every message is either processed successfully or saved for review/retry
2. **Queue Health**: Failed messages are acknowledged to prevent queue blocking
3. **Audit Trail**: All errors are logged with sufficient context for debugging
4. **Recovery Ready**: System can resume processing from any point after a restart

## Retries

if a message need to be be retried, this content is saved into the lemline_retries table. Then all running lemline applications request this table regularly to 
- select the pending  

## Scheduling

## Child Workflows

## Wait tasks

## Fork




