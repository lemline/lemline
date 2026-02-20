<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-listeners

CloudEvent listener management. Handles event-driven workflow triggering and listen tasks.

## Key Files

| File | Description |
|------|-------------|
| `ListenerService.kt` | Listener lifecycle management |
| `ListenerRepository.kt` | SQL persistence for listener registrations |
| `ListenerModel.kt` | Listener entity with event filter criteria |
| `ListenerQueryKey.kt` | Composite key for listener lookups |
| `ListenerStrategy.kt` | Strategy pattern for listener matching |
| `ListenerEventService.kt` | Incoming event processing and matching |
| `ListenerEventModel.kt` | Received event entity |
| `ListenerEventRepository.kt` | SQL persistence for received events |
| `CloudEventService.kt` | CloudEvent parsing and validation |
| `DefinitionListenService.kt` | Definition-level listen task handling |
| `ListenerConfig.kt` | Listener feature configuration |
| `outbox/` | Multiple specialized outbox relays |
| `cleaner/` | Cleanup for listener and event records |

## For AI Agents

### Working In This Directory
- **Non-standard**: has multiple specialized outboxes (unlike other feature modules)
- Two entity types: listeners (registrations) and listener events (received)
- Matching logic pairs incoming events to registered listeners
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-listeners:test
```

<!-- MANUAL: -->
