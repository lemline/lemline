# [ADR-0010] Emitting Events Architecture

## Status

Proposed

## Context

The Serverless Workflow DSL v1.0 includes an `emit` task that allows workflows to publish CloudEvents to external systems. This capability enables workflows to broadcast notifications about business events, facilitating event-driven architectures and integration with downstream consumers.

Example use case from the specification:
> "A workflow handling order processing might emit an event signaling the successful placement of an order, triggering downstream processes like inventory management or shipping."

The emit task definition in the DSL looks like:

```yaml
do:
  - emitOrderPlaced:
      emit:
        event:
          with:
            source: https://petstore.com
            type: com.petstore.order.placed.v1
            data:
              client:
                firstName: Cruella
                lastName: de Vil
              items:
                - breed: dalmatian
                  quantity: 101
```

CloudEvents properties supported:
- `source` (required): URI identifying event context
- `type` (required): Event type description
- `id` (optional): Unique event identifier (auto-generated if not provided)
- `time` (optional): Event timestamp (defaults to current time)
- `subject` (optional): Event subject in producer context
- `datacontenttype` (optional): Content type (defaults to `application/json`)
- `dataschema` (optional): Schema URI that data adheres to
- `data` (optional): Event payload

### Key Design Questions

1. **Synchronous vs Asynchronous**: Should emit be fire-and-forget or wait for acknowledgment?
2. **Reliability**: Should emitted events be persisted to ensure delivery?
3. **Channel Design**: Should CloudEvents use the existing events channel or a dedicated channel?
4. **CloudEvents Transport**: HTTP protocol binding vs message broker?
5. **Integration with Core**: How to implement in the exception-driven control flow model?

## Decision

We will implement the emit task feature with the following architecture:

### 1. Core Module Changes (lemline-core)

#### 1.1 EmitTask Model

The `EmitTask` model already exists in the Serverless Workflow SDK (`io.serverlessworkflow.api.types.EmitTask`).
It is already:
- Imported in `Node.kt`
- Recognized as an activity in `Node.isActivity()`

The SDK provides the complete CloudEvents structure via `EmitTask.emit.event.with` containing:
- `source` (required): URI identifying event context
- `type` (required): Event type description
- `id` (optional): Auto-generated if not provided
- `time` (optional): Defaults to current time
- `subject` (optional): Event subject
- `datacontenttype` (optional): Defaults to `application/json`
- `dataschema` (optional): Schema URI
- `data` (optional): Event payload

#### 1.2 EmitProcessor

Add a processor for the emit task that signals the runner to emit the CloudEvent:

```kotlin
// In lemline-core/src/main/kotlin/com/lemline/core/processors/EmitProcessor.kt
class EmitProcessor(node: Node<EmitTask>) : NodeProcessor<EmitTask, NodeState>(node) {

    override suspend fun execute(input: JsonElement, scope: Scope): JsonElement {
        val cloudEvent = buildCloudEvent(input, scope)
        throw EmitStartedException(cloudEvent)
    }

    private fun buildCloudEvent(input: JsonElement, scope: Scope): CloudEvent {
        // Access SDK types: node.task.emit.event.with
        // Evaluate expressions in source, type, data, etc.
        // Return fully resolved CloudEvent
    }
}
```

Register in `Node.kt`:

```kotlin
// Add to the processor when block
is EmitTask -> EmitProcessor(this as Node<EmitTask>)
```

#### 1.3 AsyncTaskException for Emit

Add a new async exception to signal the emit boundary:

```kotlin
// In lemline-core/src/main/kotlin/com/lemline/core/errors/AsyncTaskException.kt
sealed class AsyncTaskException : Exception() {
    // Existing exceptions...

    /**
     * Thrown when an emit task starts to signal the runner to publish a CloudEvent.
     * The workflow continues immediately after the event is published (fire-and-forget).
     */
    data class EmitStartedException(
        val cloudEvent: CloudEvent
    ) : AsyncTaskException()
}
```

#### 1.4 WorkflowEvent for Emit

Add a new suspension event type:

```kotlin
// In lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt
sealed class WorkflowEvent : WorkflowState() {
    sealed class Suspension : WorkflowEvent()

    /**
     * Event emitted when an emit task publishes a CloudEvent.
     * The CloudEvent is included for the runner to publish to the external channel.
     */
    @Serializable
    @SerialName("emitStarted")
    data class EmitStarted(
        override val nodeStack: NodeStack,
        val cloudEvent: CloudEvent,
        val rawOutput: JsonElement  // Pass-through: output = input for emit task
    ) : Suspension() {
        @Transient
        override val nodePosition = nodeStack.lastPosition

        fun resume() = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )
    }
}
```

### 2. Runner Module Changes (lemline-runner)

#### 2.1 Third Channel: CloudEvents Channel

Introduce a dedicated channel for emitting CloudEvents, separate from the internal workflow channels:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  COMMANDS CHANNEL (internal workflow state)                  │
│  commands-in ──► WorkflowCommandHandler ──► commands-out                    │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                  EVENTS CHANNEL (database persistence)                       │
│  events-in ──► WorkflowEventHandler ──► Database                            │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                  CLOUDEVENTS CHANNEL (external systems) [NEW]                │
│  WorkflowCommandHandler ──► cloudevents-out ──► External Consumers          │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Rationale**:
- CloudEvents are for external consumers, not internal workflow state
- Separation allows different reliability guarantees (fire-and-forget vs exactly-once)
- External consumers can subscribe without seeing internal workflow messages
- Different serialization format (CloudEvents JSON vs internal InstanceMessage)

#### 2.2 CloudEvent Emitter

Add a dedicated emitter for CloudEvents:

```kotlin
// In lemline-runner/src/main/kotlin/com/lemline/runner/messaging/cloudevents/CloudEventEmitter.kt
@ApplicationScoped
class CloudEventEmitter {
    @Channel("cloudevents-out")
    @Inject
    lateinit var emitter: Emitter<String>

    fun send(cloudEvent: CloudEvent, idempotentKey: IDV7) {
        val payload = CloudEventJson.encode(cloudEvent)
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(idempotentKey.toString())
            .build()
        emitter.send(Message.of(payload).addMetadata(metadata))
    }
}
```

#### 2.3 WorkflowCommandHandler Integration

Handle the emit event in the command handler:

```kotlin
// In WorkflowCommandHandler.handle()
when (val event = orchestrator.run()) {
    // Existing handlers...

    is WorkflowEvent.EmitStarted -> {
        // Emit CloudEvent to external channel (fire-and-forget)
        val messageId = event.nodeStack.deriveIdempotentId("-cloudevent")
        cloudEventEmitter.send(event.cloudEvent, messageId)

        // Immediately continue workflow execution
        copyWith(event.resume())
    }
}
```

#### 2.4 Configuration

Add configuration for the CloudEvents channel:

```kotlin
// In LemlineConfiguration.kt
interface CloudEventsConfig {
    /**
     * Whether CloudEvents emission is enabled.
     * Default: true
     */
    @WithDefault("true")
    fun enabled(): Boolean

    /**
     * Topic/queue name for CloudEvents.
     * Default: lemline-cloudevents
     */
    @WithDefault("lemline-cloudevents")
    fun topic(): String
}
```

### 3. CloudEvent Format

Emitted events follow the CloudEvents v1.0 specification in JSON format:

```json
{
    "specversion": "1.0",
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "source": "https://petstore.com",
    "type": "com.petstore.order.placed.v1",
    "time": "2024-01-15T10:30:00Z",
    "datacontenttype": "application/json",
    "data": {
        "client": {
            "firstName": "Cruella",
            "lastName": "de Vil"
        },
        "items": [
            {"breed": "dalmatian", "quantity": 101}
        ]
    },
    "lemlineworkflowid": "019abc12-3456-7890-abcd-ef1234567890",
    "lemlineworkflownamespace": "petstore",
    "lemlineworkflowname": "order-processing",
    "lemlineworkflowversion": "1.0.0"
}
```

Extension attributes (prefixed with `lemline`) provide workflow context for tracing and debugging.

### 4. Execution Flow

```
1. WorkflowCommandHandler receives command
2. Orchestrator executes until emit task
3. EmitProcessor throws EmitStartedException
4. Orchestrator catches, returns EmitStarted
5. WorkflowCommandHandler:
   a. Publishes CloudEvent to cloudevents-out channel (fire-and-forget)
   b. Immediately continues workflow by emitting resume command to commands-out
6. Workflow continues to next task
```

### 5. Idempotency

CloudEvent IDs are derived deterministically from workflow state:
- Base: `workflowId + nodePosition + workflowStep`
- Suffix: `-cloudevent`

This ensures:
- Same CloudEvent ID for replayed workflows
- Downstream consumers can deduplicate
- No duplicate events even if workflow message is reprocessed

### 6. No Database Persistence (Fire-and-Forget)

Unlike wait/retry/fork events, emit events are **not persisted to the database**:
- CloudEvents are published directly to the external channel
- Workflow continues immediately without waiting for acknowledgment
- Reliability depends on the message broker's guarantees

**Rationale**:
- Emit is fire-and-forget by design (specification implies no waiting)
- Persisting would add latency and complexity
- Broker provides at-least-once delivery
- Idempotent CloudEvent IDs handle duplicates

## Consequences

### Positive

- **Specification Compliance**: Implements the emit task as defined in Serverless Workflow DSL v1.0
- **Clean Separation**: External CloudEvents channel separates internal state from external notifications
- **Low Latency**: Fire-and-forget design doesn't block workflow execution
- **Idempotency**: Deterministic CloudEvent IDs enable safe replay and deduplication
- **Standard Format**: CloudEvents v1.0 ensures interoperability with external systems
- **Minimal Core Changes**: Follows existing exception-driven control flow pattern
- **Workflow Context**: Extension attributes provide traceability back to source workflow

### Negative

- **No Delivery Guarantees**: Fire-and-forget means no confirmation of event delivery
- **Additional Channel**: Requires configuring and managing a third message channel
- **Message Broker Dependency**: Reliability depends entirely on broker configuration
- **No Retry on Failure**: If CloudEvent emission fails, it's not retried

### Mitigations for Negative Consequences

1. **Delivery Guarantees**: Use a reliable message broker (Kafka with replication, RabbitMQ with confirms)
2. **Channel Management**: Reuse existing broker infrastructure, just different topic/queue
3. **Observability**: Log CloudEvent emissions with correlation IDs for debugging
4. **Future Enhancement**: Could add optional persistence for critical events (ADR-TBD)

## Alternatives Considered

### Alternative 1: Use Events Channel for CloudEvents

Route CloudEvents through the existing events channel with database persistence.

**Rejected because**:
- Events channel is for internal workflow state, not external notifications
- Would add unnecessary database overhead for fire-and-forget events
- Would expose internal message format to external consumers
- Different reliability requirements (internal = exactly-once, external = at-least-once)

### Alternative 2: Synchronous HTTP Emission

Emit CloudEvents via HTTP directly from the workflow (like HTTP call task).

**Rejected because**:
- Would block workflow execution waiting for HTTP response
- Specification implies asynchronous, fire-and-forget semantics
- HTTP failures would need complex retry logic in the workflow
- Doesn't leverage existing message broker infrastructure

### Alternative 3: Emit via Outbox Pattern

Persist CloudEvents to database, emit via outbox processor.

**Rejected because**:
- Adds significant latency (database write + outbox poll interval)
- Over-engineered for fire-and-forget use case
- Emit task implies immediate emission, not eventual
- Could be added as optional enhancement later if needed

### Alternative 4: Emit Directly to External Broker

Allow configuring external broker endpoints per emit task.

**Rejected because**:
- Adds connection management complexity
- Each emit would need broker credentials/config
- Harder to monitor and debug
- Single CloudEvents channel is simpler and more consistent

## References

- [Serverless Workflow DSL v1.0 - Emit Task](https://github.com/serverlessworkflow/specification/blob/main/dsl.md#emitting-events)
- [Serverless Workflow DSL Reference - Emit](https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#emit)
- [CloudEvents Specification v1.0](https://cloudevents.io/)
- [CloudEvents JSON Format](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/formats/json-format.md)
- [ADR-0003 Messaging Architecture](0003-messaging-architecture.md)
