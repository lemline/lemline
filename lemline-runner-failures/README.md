# lemline-runner-failures

> Failed workflow tracking and dead letter storage

## Purpose

This module captures and stores workflow failures for debugging and observability:
- **Failure recording** when workflow processing fails permanently
- **Error categorization** with low-cardinality reasons for metrics
- **Dead letter storage** preserving workflow state and error details

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    lemline-runner-failures                      │
├─────────────────────────────────────────────────────────────────┤
│  FailureModel               ← Database entity                  │
│  ├── id                     ← Unique failure ID                │
│  ├── instanceMessage        ← Workflow state (if available)    │
│  ├── payload                ← Raw message (if deser. failed)   │
│  ├── errorReason            ← Low-cardinality category         │
│  ├── errorClass             ← Exception class name             │
│  ├── errorMessage           ← Exception message                │
│  └── errorStackTrace        ← Full stack trace                 │
│                                                                 │
│  FailureReasons             ← Error categorization constants   │
│  └── getFailureReason()     ← Map exception → category         │
│                                                                 │
│  FailureRepository          ← Database operations              │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Failure Reasons** | Low-cardinality error categories for metrics and alerting |
| **Dead Letter** | Preserved workflow state for manual investigation and replay |
| **Dual Storage** | Either `instanceMessage` (parsed) or `payload` (raw) is stored |

## Failure Reasons

| Reason | Description |
|--------|-------------|
| `deserialization_failure` | Failed to parse incoming message |
| `serialization_failure` | Failed to serialize outgoing message |
| `definition_missing` | Workflow definition not found in cache |
| `workflow_execution_failure` | Error during workflow processing |
| `message_emission_failure` | Failed to send message to broker |
| `database_failure` | SQLException during persistence |
| `io_failure` | Network/file I/O error |
| `illegal_state_failure` | Invalid application state |
| `general_processing_failure` | Uncategorized exception |

## File Reference

| File | Responsibility |
|------|----------------|
| `FailureModel.kt` | Data class for failure records with factory methods |
| `FailureReasons.kt` | Error categorization logic and constants |
| `FailureRepository.kt` | Database operations for failures table |

## How It Works

### Failure Recording Flow

1. **Exception occurs** in message handler or processor
2. **Categorize** via `FailureReasons.getFailureReason(exception)`
3. **Create model** with either parsed instance or raw payload
4. **Persist** to `lemline_failures` table
5. **Alert** via metrics using low-cardinality reason

### Model Creation

```kotlin
// From parsed workflow instance
FailureModel.from(
    id = derivedId,
    instance = instanceMessage,
    exception = exception,
    reason = getFailureReason(exception)
)

// From raw payload (deserialization failure)
FailureModel.from(
    payload = rawMessageString,
    exception = deserializationException,
    reason = DESERIALIZATION_FAILURE
)
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (message handlers) |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **New failure reason** | Add constant to `FailureReasons` |
| **Custom categorization** | Extend `getFailureReason()` logic |
| **Failure notifications** | Add observer in repository |

## Database Table

### `lemline_failures`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Unique failure identifier |
| `instance_message` | TEXT | Serialized workflow state (JSON) |
| `payload` | TEXT | Raw message if deserialization failed |
| `error_reason` | VARCHAR(50) | Low-cardinality failure category |
| `error_class` | VARCHAR(255) | Exception class name |
| `error_message` | TEXT | Exception message |
| `error_stacktrace` | TEXT | Full stack trace |
| `created_at` | TIMESTAMP | When failure was recorded |
