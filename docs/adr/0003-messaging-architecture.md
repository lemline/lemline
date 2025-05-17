# [ADR-0003] Messaging Architecture

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires a robust messaging architecture to handle communication between workflow components, external systems, and to support event-driven workflow execution. We needed to decide on a messaging architecture that would be reliable, scalable, and aligned with the Serverless Workflow specification.

## Decision

We have decided to implement a messaging architecture based on SmallRye Reactive Messaging with the following characteristics:

1. **Event-driven Communication**: The system uses an event-driven approach where components communicate through events and messages.

2. **Reactive Messaging**: We use SmallRye Reactive Messaging, which provides a reactive programming model for handling messages.

3. **Outbox Pattern**: We implement the Outbox pattern to ensure reliable message delivery, even in the face of failures.

4. **Message Types**: We define specific message types for different kinds of communication, such as workflow events, task events, and system events.

5. **Asynchronous Processing**: Messages are processed asynchronously using Kotlin coroutines, allowing for efficient resource utilization.

6. **Error Handling**: We implement robust error handling for message processing, including retry mechanisms and dead-letter queues.

7. **Backpressure Management**: We implement backpressure management to handle high message volumes without overwhelming the system.

## Implementation Details

The messaging architecture is implemented in the `lemline-runner` module. Key components include:

### Message Structure

The core message structure is defined in `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/Message.kt`:

```kotlin
data class Message(
    @SerialName("n") val name: String,
    @SerialName("v") val version: String,
    @SerialName("s") val states: Map<NodePosition, NodeState>,
    @SerialName("p") val position: NodePosition,
)
```

Each Message contains:
- The workflow name and version
- The states of the workflow at different positions
- The current active position

Messages can be created using the `newInstance` method which initializes a new workflow message with:
- A unique ID (based on time-ordered UUID)
- Root state with the input payload
- Starting timestamp

### Message Consumer

The `MessageConsumer` class in `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageConsumer.kt` is responsible for:

1. Consuming messages from the incoming channel (`workflows-in`)
2. Processing messages using the appropriate workflow definition
3. Managing the workflow instance lifecycle
4. Handling different message states (WAITING, RUNNING, COMPLETED, FAULTED, etc.)
5. Sending output messages to the outgoing channel (`workflows-out`)

The consumer uses Kotlin coroutines to process messages asynchronously and implements error handling to track failed message processing.

### Outbox Pattern Implementation

The outbox pattern is implemented using abstract and concrete outbox classes:

- `AbstractOutbox`: Base implementation with scheduling for outbox processing and cleanup
- `RetryOutbox`: Handles retry logic for failed message processing
- `WaitOutbox`: Manages delayed message processing

The outbox processor manages:
- Batch processing of pending messages
- Configurable retry attempts
- Cleanup of processed messages after a configured time period

### Configuration

The configuration for messaging is handled via the `MessagingConfig` interface in `LemlineConfiguration.kt`, which supports three types of messaging systems:

1. In-Memory (for testing and development)
2. Kafka
3. RabbitMQ

Each messaging type has its own specific configuration properties, and the system automatically generates the correct SmallRye Reactive Messaging properties based on the selected messaging type.

## Adding a New Messaging Technology

To add support in Lemline for a messaging technology that's already supported by SmallRye Reactive Messaging, follow these steps:

### 1. Add Connector Dependency

First, add the SmallRye connector dependency to the `lemline-runner` module's `pom.xml`:

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>smallrye-reactive-messaging-[connector-name]</artifactId>
    <version>${smallrye-reactive-messaging.version}</version>
</dependency>
```

For example, to add support for Apache Pulsar (which is supported by SmallRye):

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>smallrye-reactive-messaging-pulsar</artifactId>
    <version>${smallrye-reactive-messaging.version}</version>
</dependency>
```

### 2. Update Configuration Constants

Add new constants in `LemlineConfigConstants.kt` for the connector type:

```kotlin
// In LemlineConfigConstants.kt
const val MSG_TYPE_PULSAR = "pulsar"
const val PULSAR_CONNECTOR = "smallrye-pulsar"
```

### 3. Add Configuration Interface

Create a new interface in `LemlineConfiguration.kt` for the connector's configuration properties:

```kotlin
/**
 * Pulsar-specific configuration.
 * Required when messaging.type is "pulsar".
 */
interface PulsarConfig {
    @WithDefault("pulsar://localhost:6650")
    fun serviceUrl(): String

    @WithDefault("lemline")
    fun topic(): String

    // Add other necessary configuration properties based on the SmallRye connector requirements
    
    fun topicDlq(): Optional<String>
    fun topicOut(): Optional<String>
}
```

### 4. Update MessagingConfig Interface

Update the `MessagingConfig` interface to include the new messaging type:

```kotlin
interface MessagingConfig {
    // Existing code...
    
    /**
     * Messaging type. Must be one of: in-memory, kafka, rabbitmq, pulsar
     * Default: in-memory
     */
    @WithDefault("in-memory")
    @Pattern(regexp = "in-memory|kafka|rabbitmq|pulsar")
    fun type(): String
    
    // Existing broker specific settings...
    fun kafka(): KafkaConfig
    fun rabbitmq(): RabbitMQConfig
    // Add new broker specific setting
    fun pulsar(): PulsarConfig
    
    // Existing companion object...
}
```

### 5. Update toQuarkusProperties Method

Add a new branch in the `toQuarkusProperties` method of the `MessagingConfig` companion object to handle the new connector type:

```kotlin
companion object {
    val logger = logger()

    fun toQuarkusProperties(config: MessagingConfig): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val incoming = "mp.messaging.incoming.$WORKFLOW_IN"
        val outgoing = "mp.messaging.outgoing.$WORKFLOW_OUT"
        props["$outgoing.merge"] = "true"

        // set the messaging type (only if the app is on the consumer profile)
        when (config.type()) {
            // Existing cases...
            
            MSG_TYPE_PULSAR -> {
                val pulsarConfig = config.pulsar()
                
                // Server configuration
                props["pulsar.client.serviceUrl"] = pulsarConfig.serviceUrl()
                
                // Incoming channel
                if (config.consumer().enabled()) {
                    logger.info { "✅ Consumer Pulsar enabled" }
                    props["$incoming.connector"] = PULSAR_CONNECTOR
                    props["$incoming.topic"] = pulsarConfig.topic()
                    // Add other configuration properties needed by the SmallRye Pulsar connector
                    props["$incoming.subscription-name"] = "lemline-subscription"
                    props["$incoming.subscription-type"] = "Shared"
                    props["$incoming.subscription-initial-position"] = "Earliest"
                    // Configure DLQ if provided
                    pulsarConfig.topicDlq().ifPresent { 
                        props["$incoming.dead-letter-topic"] = it 
                    }
                } else {
                    logger.info { "❌ Consumer Pulsar disabled" }
                }
                
                // Outgoing channel
                if (config.producer().enabled()) {
                    logger.info { "✅ Producer Pulsar enabled" }
                    props["$outgoing.connector"] = PULSAR_CONNECTOR
                    props["$outgoing.topic"] = pulsarConfig.topicOut().orElse(pulsarConfig.topic())
                    // Add other configuration properties needed by the SmallRye Pulsar connector
                } else {
                    logger.info { "❌ Producer Pulsar disabled" }
                }
            }
        }
        
        return props
    }
}
```

### 6. Add a Test Profile (Optional)

For testing, add a test profile in `test/kotlin/com/lemline/runner/tests/profiles`:

```kotlin
class PulsarProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration (typically in-memory for tests)
            "lemline.database.type" to DB_TYPE_IN_MEMORY,

            // Messaging configuration
            "lemline.messaging.type" to MSG_TYPE_PULSAR,
            CONSUMER_ENABLED to "true",
            PRODUCER_ENABLED to "true",
        )
    }

    override fun testResources(): List<TestResourceEntry> {
        return listOf(TestResourceEntry(PulsarTestResource::class.java))
    }
    
    override fun tags(): Set<String> {
        return setOf(MSG_TYPE_PULSAR)
    }
}
```

### 7. Create a Test Resource (Optional)

Create a TestResource to manage the test instance of your messaging broker:

```kotlin
class PulsarTestResource : QuarkusTestResourceLifecycleManager {
    private var pulsarContainer: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        pulsarContainer = GenericContainer("apachepulsar/pulsar:3.0.0")
            .withExposedPorts(6650)
            .withCommand("bin/pulsar", "standalone")
        
        pulsarContainer?.start()
        
        val host = pulsarContainer?.host ?: "localhost"
        val port = pulsarContainer?.getMappedPort(6650) ?: 6650
        
        return mapOf(
            "lemline.messaging.pulsar.serviceUrl" to "pulsar://$host:$port"
        )
    }

    override fun stop() {
        pulsarContainer?.stop()
    }
}
```

### 8. Usage Example

Now you can configure your application to use the new messaging technology:

```properties
# config.properties or application.properties
lemline.messaging.type=pulsar
lemline.messaging.pulsar.serviceUrl=pulsar://localhost:6650
lemline.messaging.pulsar.topic=lemline-workflows
lemline.messaging.consumer.enabled=true
lemline.messaging.producer.enabled=true
```

### 9. Testing the Integration

Create tests to verify that your Lemline application correctly integrates with the new messaging technology:

```kotlin
@QuarkusTest
@TestProfile(PulsarProfile::class)
class PulsarMessagingTest {
    @Test
    fun testWorkflowExecution() {
        // Test workflow execution with Pulsar messaging
    }
}
```

## Consequences

### Positive

- **Reliability**: The Outbox pattern ensures that messages are not lost, even if the system fails during processing.
- **Scalability**: The asynchronous and reactive approach allows the system to scale to handle high message volumes.
- **Flexibility**: The event-driven architecture makes it easier to add new components and integrate with external systems.
- **Resource Efficiency**: Asynchronous processing with coroutines allows for efficient use of system resources.
- **Resilience**: Robust error handling and retry mechanisms make the system resilient to failures.
- **Extensibility**: The architecture makes it easy to add support for new messaging technologies.

### Negative

- **Complexity**: Event-driven architectures introduce complexity, especially for debugging and understanding the flow of execution.
- **Eventual Consistency**: The asynchronous nature of the system means that it operates under eventual consistency, which can be challenging to reason about.
- **Operational Overhead**: Managing message brokers and ensuring reliable message delivery adds operational overhead.

## Alternatives Considered

### Synchronous Communication

A synchronous communication approach would use direct method calls or synchronous API calls between components. This approach was rejected because:
- It would make the system less resilient to failures, as a failure in one component would directly impact others.
- It would limit scalability, as components would be tightly coupled.
- It would make it harder to implement features like asynchronous workflow execution and event-driven transitions.

### Custom Messaging Implementation

Implementing a custom messaging solution instead of using SmallRye Reactive Messaging was considered. This approach was rejected because:
- It would require significant development effort to implement features that are already provided by SmallRye Reactive Messaging.
- It would likely result in a less robust and well-tested solution.
- It would not leverage the integration with Quarkus and other components that SmallRye Reactive Messaging provides.

## References

- [SmallRye Reactive Messaging](https://smallrye.io/smallrye-reactive-messaging/)
- [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Reactive Streams Specification](https://www.reactive-streams.org/)
- [SmallRye Reactive Messaging Connectors Guide](https://smallrye.io/smallrye-reactive-messaging/smallrye-reactive-messaging/3.4/connectors/connectors.html)
- [Contributing Connectors to SmallRye Reactive Messaging](https://smallrye.io/smallrye-reactive-messaging/4.19.0/concepts/contributing-connectors/)