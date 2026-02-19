# Protobuf Message Inventory (PROTO-00)

- `Last updated`: 2026-02-19
- `Scope`: internal runner commands/events + persisted workflow state payloads

## Commands Channel

### Producers

- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandEmitter.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`

### Consumers

- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandSubscriber.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`

### Message Types

- `WorkflowCommand.ResumeFromTask`
- `WorkflowCommand.ResumeWithCompletedTask`
- `WorkflowCommand.ResumeWithFailedTask`

## Events Channel

### Producers

- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventEmitter.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`

### Consumers

- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventSubscriber.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`

### Message Types

- `WorkflowEvent.WorkflowCompleted`
- `WorkflowEvent.WorkflowFailed`
- `WorkflowEvent.ForkBranchCompleted`
- `WorkflowEvent.ForkBranchFailed`
- `WorkflowEvent.ForEachCompleted`
- `WorkflowEvent.TaskScheduled`
- `WorkflowEvent.WaitStarted`
- `WorkflowEvent.TaskRetryScheduled`
- `WorkflowEvent.RunWorkflowStarted`
- `WorkflowEvent.ForkStarted`
- `WorkflowEvent.ListenStarted`
- `WorkflowEvent.EmitStarted`
- `WorkflowEvent.CallHttpStarted`
- `WorkflowEvent.RunScriptStarted`
- `WorkflowEvent.RunShellStarted`

## Persistence Paths

### Shared Columns

- `workflow_state`
  - `/Users/gilles/dev/lemline/lemline/lemline-runner-common/src/main/kotlin/com/lemline/runner/common/repositories/ops/InstanceRepository.kt`

### Serialization Points (migrated to protobuf codecs)

- `/Users/gilles/dev/lemline/lemline/lemline-runner-common/src/main/kotlin/com/lemline/runner/common/messaging/InstanceMessage.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner-common/src/main/kotlin/com/lemline/runner/common/messaging/InstanceMessageCodec.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-core/src/main/kotlin/com/lemline/core/states/protobuf/WorkflowStateProtobufMapper.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-core/src/main/kotlin/com/lemline/core/states/protobuf/NodeStackProtobufMapper.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageEmitter.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`
- `/Users/gilles/dev/lemline/lemline/lemline-runner-failures/src/main/kotlin/com/lemline/runner/failures/FailureRepository.kt`

## Protobuf Contracts Introduced

- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/common.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/state/node_state.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/state/node_stack.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/workflow/configs.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/workflow/commands.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/workflow/events.proto`
- `/Users/gilles/dev/lemline/lemline/lemline-messages-proto/src/main/proto/internal/workflow/envelope.proto`
