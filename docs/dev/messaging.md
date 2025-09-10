# Messaging Architecture

## Overview

Lemline implements a robust messaging architecture based on SmallRye Reactive Messaging to handle communication between workflow components, external systems, and to support event-driven workflow execution.

## Implementation Details

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

The `MessageConsumer` in `messaging/MessageConsumer.kt` handles:

1. Consuming messages from the incoming channel (`workflows-in`)
2. Processing messages using the appropriate workflow definition
3. Managing the workflow instance lifecycle
4. Handling different message states (WAITING, RUNNING, COMPLETED, FAULTED, etc.)
5. Sending output messages to the outgoing channel (`workflows-out`)

The consumer uses Kotlin coroutines to process messages asynchronously and implements error handling to track failed message processing.

### Outbox Pattern Implementation

For reliable message delivery, Lemline implements the Outbox pattern:

- `AbstractOutbox`: Base implementation with scheduling for outbox processing and cleanup
- `RetryOutbox`: Handles retry logic for failed message processing
- `WaitOutbox`: Manages delayed message processing

The outbox processor manages:
- Batch processing of pending messages
- Configurable retry attempts
- Cleanup of processed messages after a configured time period

### Supported Messaging Systems

Lemline currently supports these messaging systems:

1. **In-Memory** (for testing and development)
2. **Kafka**
3. **RabbitMQ**

## Configuration

Messaging configuration is defined in the `MessagingConfig` interface in `LemlineConfiguration.kt`. Each messaging system has specific properties, and the system automatically generates the correct SmallRye Reactive Messaging properties.

Example configuration:
```yaml
lemline:
  messaging:
    type: kafka  # Options: in-memory, kafka, rabbitmq
    consumer:
      enabled: true
    producer:
      enabled: true
    kafka:
      brokers: localhost:9092
      topic: lemline-workflows
      groupId: lemline-group
```

## Adding Support for a New Messaging Technology

### Step 1: Add Connector Dependency

Add the SmallRye connector to `lemline-runner` module's dependencies:

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>smallrye-reactive-messaging-[connector-name]</artifactId>
    <version>${smallrye-reactive-messaging.version}</version>
</dependency>
```

For example, to add Apache Pulsar:

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>smallrye-reactive-messaging-pulsar</artifactId>
    <version>${smallrye-reactive-messaging.version}</version>
</dependency>
```

### Step 2: Update Configuration Constants

Add new constants in `LemlineConfigConstants.kt`:

```kotlin
const val MSG_TYPE_PULSAR = "pulsar"
const val PULSAR_CONNECTOR = "smallrye-pulsar"
```

### Step 3: Add Configuration Interface

Create a new interface in `LemlineConfiguration.kt`:

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
    
    fun topicDlq(): Optional<String>
    fun topicOut(): Optional<String>
}
```

### Step 4: Update MessagingConfig Interface

Update the `MessagingConfig` interface:

```kotlin
interface MessagingConfig {
    // Existing code...
    
    @WithDefault("in-memory")
    @Pattern(regexp = "in-memory|kafka|rabbitmq|pulsar")
    fun type(): String
    
    // Add new broker specific setting
    fun pulsar(): PulsarConfig
}
```

### Step 5: Update toQuarkusProperties Method

Add a new branch in the `toQuarkusProperties` method:

```kotlin
companion object {
    fun toQuarkusProperties(config: MessagingConfig): Map<String, String> {
        // Existing code...
        
        when (config.type()) {
            // Existing cases...
            
            MSG_TYPE_PULSAR -> {
                val pulsarConfig = config.pulsar()
                
                // Server configuration
                props["pulsar.client.serviceUrl"] = pulsarConfig.serviceUrl()
                
                // Incoming channel
                if (config.consumer().enabled()) {
                    props["$incoming.connector"] = PULSAR_CONNECTOR
                    props["$incoming.topic"] = pulsarConfig.topic()
                    props["$incoming.subscription-name"] = "lemline-subscription"
                    props["$incoming.subscription-type"] = "Shared"
                    
                    pulsarConfig.topicDlq().ifPresent { 
                        props["$incoming.dead-letter-topic"] = it 
                    }
                }
                
                // Outgoing channel
                if (config.producer().enabled()) {
                    props["$outgoing.connector"] = PULSAR_CONNECTOR
                    props["$outgoing.topic"] = pulsarConfig.topicOut().orElse(pulsarConfig.topic())
                }
            }
        }
    }
}
```

### Step 6: Testing

Create integration tests in `test/kotlin/com/lemline/runner/tests/`:

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

### Step 7: Usage

Configure your application to use the new messaging technology:

```properties
lemline.messaging.type=pulsar
lemline.messaging.pulsar.serviceUrl=pulsar://localhost:6650
lemline.messaging.pulsar.topic=lemline-workflows
lemline.messaging.consumer.enabled=true
lemline.messaging.producer.enabled=true
```

## Troubleshooting

### Common Issues

- **Message not being consumed**: Check that the consumer is enabled and the topic is correct
- **Connection failures**: Verify broker addresses and credentials
- **Message serialization errors**: Ensure the message format matches the expected structure

### Debugging Tips

- Enable DEBUG logging for SmallRye Reactive Messaging: `quarkus.log.category."io.smallrye.reactive.messaging".level=DEBUG`
- Monitor the message broker's admin interface to verify message flow
- Use the in-memory connector for testing to isolate messaging issues from broker connectivity issues 